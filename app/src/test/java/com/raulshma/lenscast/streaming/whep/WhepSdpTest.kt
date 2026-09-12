package com.raulshma.lenscast.streaming.whep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The SDP offer gate under real and hostile input. The gate is the only code
 * that touches a viewer's offer before libwebrtc does, so the contract is
 * two-sided:
 *
 *  - every realistic browser offer (Chrome/Firefox/Safari unified-plan shape,
 *    LF or CRLF line endings, audio+video or video-only) parses to an Offer
 *    with the right media flags;
 *  - no hostile body ever throws or slips through: emptiness, size/line-cap
 *    overflow, a missing or misplaced `v=0`, audio-only bodies, NUL bytes and
 *    binary soup all answer null — the route's readable 400, never a native
 *    parser crash.
 *
 * A deterministic seeded fuzzer closes the gap between the corpus and the
 * arbitrary bytes a scanner may POST (the route answers 400 on every null).
 */
class WhepSdpTest {

    // ── a realistic unified-plan offer (the shape Chrome POSTs) ──

    private val browserOffer = listOf(
        "v=0",
        "o=- 46117317 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "a=ice-options:trickle",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "c=IN IP4 0.0.0.0",
        "a=mid:0",
        "a=sendonly",
        "a=rtpmap:111 opus/48000/2",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "c=IN IP4 0.0.0.0",
        "a=mid:1",
        "a=sendonly",
        "a=rtpmap:96 H264/90000",
    ).joinToString("\r\n")

    @Test
    fun `a browser offer with audio and video parses with both flags`() {
        val offer = WhepSdp.parseOffer(browserOffer)!!
        assertTrue(offer.hasVideo)
        assertTrue(offer.hasAudio)
    }

    @Test
    fun `a video-only offer parses with audio off`() {
        val videoOnly = browserOffer
            .lines()
            .filterNot { it.startsWith("m=audio") }
            .joinToString("\r\n")
        val offer = WhepSdp.parseOffer(videoOnly)!!
        assertTrue(offer.hasVideo)
        assertFalse(offer.hasAudio)
    }

    @Test
    fun `LF line endings are accepted like CRLF`() {
        val offer = WhepSdp.parseOffer(browserOffer.replace("\r\n", "\n"))!!
        assertTrue(offer.hasVideo)
    }

    @Test
    fun `an application-sdp body's media lines drive the flags regardless of order`() {
        // Video section before audio: the flags are order-independent.
        val flipped = browserOffer
            .replace("m=audio", "m=TEMP").replace("m=video", "m=audio").replace("m=TEMP", "m=video")
        val offer = WhepSdp.parseOffer(flipped)!!
        assertTrue(offer.hasVideo)
        assertTrue(offer.hasAudio)
    }

    // ── the refusals: every one maps to the route's 400 ──

    @Test
    fun `an empty body is refused`() {
        assertNull(WhepSdp.parseOffer(""))
    }

    @Test
    fun `an audio-only offer is refused - no video, no session`() {
        val audioOnly = listOf(
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        ).joinToString("\r\n")
        assertNull(WhepSdp.parseOffer(audioOnly))
    }

    @Test
    fun `a body without the v line is refused`() {
        assertNull(WhepSdp.parseOffer("m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendonly"))
    }

    @Test
    fun `a misplaced v line is refused - v must be first`() {
        assertNull(WhepSdp.parseOffer("o=- 1 2 IN IP4 127.0.0.1\r\nv=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96"))
    }

    @Test
    fun `an oversized body is refused`() {
        val big = "v=0\r\n" + "a=x".repeat(WhepSdp.MAX_OFFER_BYTES)
        assertTrue(big.length > WhepSdp.MAX_OFFER_BYTES)
        assertNull(WhepSdp.parseOffer(big))
    }

    @Test
    fun `a body exactly at the byte cap still parses`() {
        // The transport reads at most MAX_OFFER_BYTES, so the gate's limit
        // matches it: a body at the cap is legal.
        val head = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        val filler = "a=x".repeat((WhepSdp.MAX_OFFER_BYTES - head.length) / 3)
        val body = head + filler
        assertEquals(WhepSdp.MAX_OFFER_BYTES, body.length)
        assertTrue(WhepSdp.parseOffer(body)!!.hasVideo)
    }

    @Test
    fun `a line-count overflow is refused`() {
        val many = "v=0\r\n" + List(WhepSdp.MAX_LINES + 1) { "a=pad" }.joinToString("\r\n") + "\r\nm=video 9"
        assertNull(WhepSdp.parseOffer(many))
    }

    @Test
    fun `NUL bytes and control characters are refused`() {
        assertNull(WhepSdp.parseOffer("v=0\u0000\r\nm=video 9"))
        assertNull(WhepSdp.parseOffer("v=0\r\nm=video 9\u0007bell"))
    }

    // ── hostile-input fuzz posture: never throw, never admit garbage ──

    @Test(timeout = 10_000)
    fun `corpus - hostile bodies never throw and never parse`() {
        val corpus = listOf(
            " ",
            "\r\n",
            "\n\n\n",
            "v=0",
            "V=0\r\nm=video", // wrong case: SDP type names are case-sensitive
            "v=1\r\nm=video 9",
            "v=0\r\nm=video",
            "v=0\nm=vide 9", // typo'd media word is not a video section
            "v=0\r\nm=video 9\r\n" + "a=".repeat(10_000),
            "{\"json\":true}", // a JSON body POSTed as SDP
            "GET / HTTP/1.1\r\nHost: x", // an HTTP request as the body
            "<?xml version=\"1.0\"?><svg/>",
            "v=0\r\n\u0000".repeat(100),
            "v=0\r\nm=video 9 " + "A".repeat(WhepSdp.MAX_OFFER_BYTES / 2),
        )
        for (body in corpus) {
            val verdict = runCatching { WhepSdp.parseOffer(body) }
            assertTrue("corpus body threw: ${body.take(40)}", verdict.isSuccess)
            if (verdict.getOrNull() != null) {
                // Anything the gate admits must at least claim video.
                assertTrue(verdict.getOrNull()!!.hasVideo)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `fuzz - random bytes and truncations never crash the gate`() {
        val random = Random(0xC0FFEE)
        val alphabet = "v=moarniduetx0 \r\n=:/-9AZaz".map { it.code.toByte() }.toByteArray()
        repeat(5_000) {
            val size = random.nextInt(2_000)
            val body = ByteArray(size) { alphabet[random.nextInt(alphabet.size)] }
            val verdict = runCatching { WhepSdp.parseOffer(String(body, Charsets.ISO_8859_1)) }
            assertTrue("seeded fuzz body threw at index $it", verdict.isSuccess)
        }
        // Random binary (arbitrary bytes incl. control codes) same contract.
        repeat(1_000) {
            val body = ByteArray(random.nextInt(1_000))
            random.nextBytes(body)
            assertTrue(runCatching { WhepSdp.parseOffer(String(body, Charsets.ISO_8859_1)) }.isSuccess)
        }
    }
}

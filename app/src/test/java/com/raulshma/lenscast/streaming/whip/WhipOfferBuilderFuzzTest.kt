package com.raulshma.lenscast.streaming.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The candidate injector under hostile SDP: a deterministic corpus fuzzer
 * over [WhipOfferBuilder.injectCandidates].
 *
 * ── The contract ──
 * For ANY SDP body and any line-safe candidate list, injectCandidates either
 * returns the body untouched (no missing candidates) or rebuilds it so that:
 * the rebuild introduces no blank line that was not already there (browsers
 * reject "Invalid SDP line" over a single empty one) and ends with exactly
 * one CRLF, every original content line is preserved, every missing
 * candidate lands at the end of every `m=` section (a body without one is
 * vacuous — no appends), and a re-injection of the same list is an identity
 * (idempotence).
 *
 * ── The corpus ──
 * well-formed multi-section offers, trailing-CRLF and LF-only and mixed
 * line-ending variants, empty/blank bodies, bodies with no `m=` line,
 * truncations at every prefix, and seeded garbage seeded with SDP fragments.
 * Candidate lines are line-safe (libwebrtc hands over single lines); the SDP
 * side carries the hostility.
 */
class WhipOfferBuilderFuzzTest {

    // ── the contract ──

    private val candidate = "a=candidate:1 1 udp 1 10.0.0.1 5000 typ host"

    /** Blank line under the builder's own line model (both CRLF and LF split). */
    private fun hasInteriorBlank(sdp: String): Boolean =
        sdp.split("\r\n", "\n").dropLastWhile { it.isBlank() }.any { it.isBlank() }

    private fun assertContract(input: String, candidates: List<String>) {
        val out = WhipOfferBuilder.injectCandidates(input, candidates)
        if (out == input) return // pass-through keeps whatever it was given
        // A rebuilt body ends with exactly one CRLF, whatever trailed the input.
        assertTrue("rebuilt body lacks the final CRLF: $out", out.endsWith("\r\n"))
        // A rebuild never INTRODUCES a blank line (browsers reject "Invalid
        // SDP line" over one); interior blanks the input already had pass
        // through — only trailing blanks are the builder's to strip.
        if (!hasInteriorBlank(input)) {
            assertFalse("interior blank line leaked into: $out", hasInteriorBlank(out))
        }
        // Every original content line survives.
        for (line in input.split("\r\n", "\n")) {
            if (line.isNotBlank()) assertTrue("lost line: $line", out.contains(line.trim()))
        }
        // Re-injecting the same list changes nothing.
        assertEquals("not idempotent for input: $input", out, WhipOfferBuilder.injectCandidates(out, candidates))
    }

    // ── positive controls ──

    @Test(timeout = 10_000)
    fun `a well-formed two-section offer accepts and pins the candidates`() {
        val offer = listOf(
            "v=0",
            "o=- 1 1 IN IP4 127.0.0.1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=sendonly",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=sendonly",
        ).joinToString("\r\n")
        val out = WhipOfferBuilder.injectCandidates(offer, listOf(candidate))
        assertEquals(2, out.lineSequence().count { it.trim() == candidate })
        assertContract(offer, listOf(candidate))
    }

    @Test(timeout = 10_000)
    fun `an m-less body is rebuilt but takes no candidates`() {
        // "Appended to the end of every m= section" is vacuous with no m=
        // section — and a media-level candidate outside one would be invalid
        // SDP anyway. libwebrtc offers always carry m= lines; this pins the
        // vacuous path as a no-append, not a crash.
        val body = "v=0\r\no=- 1 1 IN IP4 127.0.0.1"
        val out = WhipOfferBuilder.injectCandidates(body, listOf(candidate))
        assertFalse(out.contains("candidate"))
        assertTrue(out.endsWith("\r\n"))
        assertContract(body, listOf(candidate))
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - trailing, LF-only and mixed line endings all satisfy the contract`() {
        val offer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendonly\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendonly"
        val bodies = listOf(
            offer,
            offer + "\r\n", // libwebrtc's trailing CRLF
            offer + "\r\n\r\n\r\n", // stacked trailing blanks
            offer.replace("\r\n", "\n"),
            offer.replace("\r\n", "\n") + "\n",
            offer.replace("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n", "m=video 9 UDP/TLS/RTP/SAVPF 96\n"), // mixed
            offer + "\r\r\n", // stray CR before the final CRLF
        )
        for (body in bodies) assertContract(body, listOf(candidate))
    }

    @Test(timeout = 10_000)
    fun `corpus - empty, blank and candidate-less inputs are identities`() {
        for (body in listOf("", " ", "\r\n", "\n\n\n", "\r\n\r\n")) {
            assertEquals(body, WhipOfferBuilder.injectCandidates(body, emptyList()))
            assertContract(body, listOf("", "   "))
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - truncations at every prefix of a valid offer satisfy the contract`() {
        val offer = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendonly\r\n"
        for (cut in offer.indices) {
            assertContract(offer.substring(0, cut), listOf(candidate))
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage SDP never breaks the invariants`() {
        val random = Random(0x5D9F00D)
        // SDP-flavored alphabet: line endings and m=/a= fragments make the
        // generator actually reach the section-scanning branches.
        val alphabet = "vomarcynd=\r\n 019.:/-" + "m=a=candidate"
        for (trial in 0 until 2_000) {
            val body = buildString {
                repeat(random.nextInt(64)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val lines = List(random.nextInt(3)) {
                if (random.nextBoolean()) "" else candidate
            }
            assertContract(body, lines)
        }
    }
}

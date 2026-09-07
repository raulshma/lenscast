package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SdpBuilderTest {

    // Minimal but real-looking parameter sets (SPS carries profile/level bytes).
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xD9.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38.toByte())

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun build(
        sessionId: String = "session-1",
        ip: String = "192.168.1.10",
        videoBitrate: Int = 2_000_000,
        audioEnabled: Boolean = false,
        audioSampleRateHz: Int = 48_000,
        audioChannelCount: Int = 1,
        sps: ByteArray? = this.sps,
        pps: ByteArray? = this.pps,
        asc: ByteArray? = null,
    ) = SdpBuilder.build(
        sessionId, ip, videoBitrate, audioEnabled, audioSampleRateHz, audioChannelCount,
        sps, pps, asc
    )

    // ── video-only ──

    @Test
    fun `video-only sdp emits the exact session and video lines in order`() {
        val sdp = build()

        val expected = listOf(
            "v=0",
            "o=- session-1 1 IN IP4 192.168.1.10",
            "s=LensCast Camera Stream",
            "t=0 0",
            "a=tool:LensCast",
            "a=type:broadcast",
            "a=control:*",
            "a=range:npt=0-",
            "m=video 0 RTP/AVP 96",
            "c=IN IP4 0.0.0.0",
            "b=AS:2000",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 packetization-mode=1;profile-level-id=42c01f;" +
                "sprop-parameter-sets=${b64(sps)},${b64(pps)}",
            "a=control:stream",
        )
        val actual = sdp.trimEnd('\n').split('\n')
        assertEquals(expected, actual)
        assertFalse(sdp.contains("m=audio"))
    }

    @Test
    fun `sdp ends with a trailing newline per line`() {
        val sdp = build()
        assertTrue(sdp.endsWith("\n"))
        assertFalse(sdp.endsWith("\n\n"))
    }

    @Test
    fun `blank parameter sets omit sprop and keep the fmtp well-formed`() {
        val sdp = build(sps = null, pps = null)
        assertTrue(sdp.contains("a=fmtp:96 packetization-mode=1;profile-level-id=42c01f\n"))
        assertFalse(sdp.contains("sprop-parameter-sets"))
        assertFalse(sdp.contains(";;"))
    }

    @Test
    fun `sps without pps omits sprop entirely rather than a dangling pair`() {
        val sdp = build(sps = sps, pps = null)
        assertFalse(sdp.contains("sprop-parameter-sets"))
        assertTrue(sdp.contains("a=fmtp:96 packetization-mode=1;profile-level-id=42c01f\n"))
    }

    @Test
    fun `unknown profile falls back to the baseline default profile-level-id`() {
        val shortSps = byteArrayOf(0x67) // fewer than 4 bytes → default
        val sdp = build(sps = shortSps, pps = pps)
        assertTrue(sdp.contains("profile-level-id=42c01f"))
        // The short SPS is still advertised in sprop-parameter-sets.
        assertTrue(sdp.contains("sprop-parameter-sets=${b64(shortSps)},${b64(pps)}"))
    }

    @Test
    fun `bitrate scales the b=AS line in kbps`() {
        assertTrue(build(videoBitrate = 750_000).contains("b=AS:750"))
        assertTrue(build(videoBitrate = 500_000).contains("b=AS:500"))
    }

    // ── audio ──

    @Test
    fun `audio section appends the exact mpeg4-generic lines after video`() {
        val sdp = build(audioEnabled = true, audioSampleRateHz = 48_000, audioChannelCount = 1, asc = byteArrayOf(0x12, 0x10))

        val audioLines = listOf(
            "m=audio 0 RTP/AVP 97",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:97 mpeg4-generic/48000/1",
            "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;" +
                "indexlength=3;indexdeltalength=3;config=1210",
            "a=control:trackID=1",
        )
        val actual = sdp.trimEnd('\n').split('\n')
        val audioStart = actual.indexOf("m=audio 0 RTP/AVP 97")
        assertTrue(audioStart > 0)
        assertEquals(audioLines, actual.subList(audioStart, actual.size))
        // Video section still intact and ordered before audio.
        assertTrue(actual.indexOf("m=video 0 RTP/AVP 96") < audioStart)
    }

    @Test
    fun `audio without an ASC falls back to the AAC-LC 48kHz mono config`() {
        val sdp = build(audioEnabled = true, asc = null)
        assertTrue(sdp.contains("config=1190"))
    }

    @Test
    fun `audio rtpmap reflects the configured sample rate and channel count`() {
        val sdp = build(audioEnabled = true, audioSampleRateHz = 44_100, audioChannelCount = 2)
        assertTrue(sdp.contains("a=rtpmap:97 mpeg4-generic/44100/2"))
    }

    // ── session identity placement ──

    @Test
    fun `session id and ip land in the origin line between v and s`() {
        val sdp = build(sessionId = "abc123", ip = "10.0.0.7")
        val lines = sdp.trimEnd('\n').split('\n')
        assertEquals("v=0", lines[0])
        assertEquals("o=- abc123 1 IN IP4 10.0.0.7", lines[1])
        assertEquals("s=LensCast Camera Stream", lines[2])
        assertEquals("t=0 0", lines[3])
    }
}

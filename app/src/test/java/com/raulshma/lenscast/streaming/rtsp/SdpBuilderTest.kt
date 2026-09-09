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

    // H.265 parameter sets (2-byte headers: 0x40 = VPS type 32, 0x42 = SPS 33, 0x44 = PPS 34).
    private val vps = byteArrayOf(0x40, 0x01, 0x0A)
    private val h265Sps = byteArrayOf(0x42, 0x01, 0x0B)
    private val h265Pps = byteArrayOf(0x44, 0x01, 0x0C)

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
        codec: RtspVideoCodec = RtspVideoCodec.H264,
        vps: ByteArray? = null,
    ) = SdpBuilder.build(
        sessionId, ip, videoBitrate, audioEnabled, audioSampleRateHz, audioChannelCount,
        sps, pps, asc, codec, vps
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
        assertTrue(sdp.contains("config=1188"))
    }

    @Test
    fun `audio fallback ASC follows the configured rate and channel count`() {
        // 44.1 kHz stereo: index 4, two channels → 0x12 0x10.
        val sdp = build(audioEnabled = true, audioSampleRateHz = 44_100, audioChannelCount = 2, asc = null)
        assertTrue(sdp.contains("config=1210"))
        assertTrue(sdp.contains("a=rtpmap:97 mpeg4-generic/44100/2"))
    }

    @Test
    fun `audio with a short ASC still falls back instead of crashing`() {
        val sdp = build(audioEnabled = true, asc = byteArrayOf(0x11))
        assertTrue(sdp.contains("config=1188"))
    }

    @Test
    fun `audio rtpmap reflects the configured sample rate and channel count`() {
        val sdp = build(audioEnabled = true, audioSampleRateHz = 44_100, audioChannelCount = 2)
        assertTrue(sdp.contains("a=rtpmap:97 mpeg4-generic/44100/2"))
    }

    // ── H.265 ──

    @Test
    fun `h265 sdp advertises the hevc rtpmap and the sprop triple fmtp`() {
        val sdp = build(
            codec = RtspVideoCodec.H265,
            sps = h265Sps,
            pps = h265Pps,
            vps = vps,
        )

        val videoLines = listOf(
            "m=video 0 RTP/AVP 96",
            "c=IN IP4 0.0.0.0",
            "b=AS:2000",
            "a=rtpmap:96 H265/90000",
            "a=fmtp:96 sprop-vps=${b64(vps)};sprop-sps=${b64(h265Sps)};sprop-pps=${b64(h265Pps)}",
            "a=control:stream",
        )
        val actual = sdp.trimEnd('\n').split('\n')
        val videoStart = actual.indexOf("m=video 0 RTP/AVP 96")
        assertTrue(videoStart > 0)
        assertEquals(videoLines, actual.subList(videoStart, actual.size))
        assertFalse(sdp.contains("H264"))
        assertFalse(sdp.contains("sprop-parameter-sets"))
    }

    @Test
    fun `h265 without learned parameter sets omits the fmtp line entirely`() {
        val sdp = build(codec = RtspVideoCodec.H265, sps = null, pps = null, vps = null)

        assertTrue(sdp.contains("a=rtpmap:96 H265/90000\n"))
        assertFalse(sdp.contains("a=fmtp:96"))
        assertFalse(sdp.contains(";;"))
    }

    @Test
    fun `h265 with only some parameter sets omits fmtp rather than a partial sprop`() {
        val sdp = build(codec = RtspVideoCodec.H265, sps = h265Sps, pps = h265Pps, vps = null)
        assertFalse(sdp.contains("a=fmtp:96"))

        val sdpNoPps = build(codec = RtspVideoCodec.H265, sps = h265Sps, pps = null, vps = vps)
        assertFalse(sdpNoPps.contains("a=fmtp:96"))
    }

    @Test
    fun `payload type stays 96 for h265`() {
        val sdp = build(codec = RtspVideoCodec.H265, sps = h265Sps, pps = h265Pps, vps = vps)
        assertTrue(sdp.contains("m=video 0 RTP/AVP 96"))
        assertTrue(sdp.contains("a=rtpmap:96 H265/90000"))
        assertTrue(sdp.contains("a=fmtp:96 "))
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

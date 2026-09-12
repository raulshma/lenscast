package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the RTSP sub-stream's pure routing: the /sub URI grammar
 * ([RtspUriPolicy]), the video-only SDP with its own aggregate control path
 * ([SdpBuilder]), and the fixed sub-stream constants
 * ([StreamDefaults.RTSP_SUB_*] — dimensions and the bitrate clamp).
 */
class RtspSubStreamPolicyTest {

    // ── URI grammar ──

    @Test
    fun `DESCRIBE answers the sub aggregate`() {
        assertTrue(RtspUriPolicy.isRequestUriAllowed("DESCRIBE", "rtsp://host:8554/sub"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("DESCRIBE", "/sub"))
    }

    @Test
    fun `SETUP accepts the sub aggregate and its track0`() {
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "/sub"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "rtsp://host:8554/sub/trackID=0"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "/SUB/trackid=0"))
    }

    @Test
    fun `SETUP rejects the sub audio track`() {
        assertFalse(RtspUriPolicy.isRequestUriAllowed("SETUP", "/sub/trackID=1"))
    }

    @Test
    fun `PLAY and TEARDOWN accept the sub paths`() {
        assertTrue(RtspUriPolicy.isRequestUriAllowed("PLAY", "/sub"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("TEARDOWN", "/sub/trackID=0"))
    }

    @Test
    fun `the sub grammar never swallows the main stream paths`() {
        assertFalse(RtspUriPolicy.isSubControlUri("/stream"))
        assertFalse(RtspUriPolicy.isSubControlUri("/"))
        assertFalse(RtspUriPolicy.isSubControlUri("/stream/trackID=0"))
        assertFalse(RtspUriPolicy.isSubControlUri("/submarine"))
        assertTrue(RtspUriPolicy.isSubControlUri("/sub"))
        assertTrue(RtspUriPolicy.isSubControlUri("/sub/trackID=0"))
    }

    @Test
    fun `main track resolution still works alongside the sub`() {
        assertEquals(0, RtspUriPolicy.resolveTrackId("/stream"))
        assertEquals(1, RtspUriPolicy.resolveTrackId("/stream/trackID=1"))
    }

    // ── SDP for /sub ──

    private fun subSdp(): String = SdpBuilder.build(
        sessionId = "1_abc",
        ip = "192.168.1.10",
        videoBitrate = StreamDefaults.RTSP_SUB_VIDEO_BITRATE,
        audioEnabled = false,
        audioSampleRateHz = 48_000,
        audioChannelCount = 1,
        sps = byteArrayOf(0x67.toByte(), 0x42.toByte(), 0xC0.toByte(), 0x1E.toByte()),
        pps = byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x3C.toByte(), 0x80.toByte()),
        audioSpecificConfig = null,
        codec = RtspVideoCodec.H264,
        vps = null,
        controlPath = RtspUriPolicy.SUB_STREAM_PATH,
    )

    @Test
    fun `sub SDP anchors control at the sub path`() {
        assertTrue(subSdp().contains("a=control:sub"))
        assertFalse(subSdp().contains("a=control:stream"))
    }

    @Test
    fun `sub SDP is video-only`() {
        val sdp = subSdp()
        assertFalse(sdp.contains("m=audio"))
        assertTrue(sdp.contains("m=video 0 RTP/AVP 96"))
        assertTrue(sdp.contains("a=rtpmap:96 H264/90000"))
    }

    @Test
    fun `sub SDP advertises the sub bitrate`() {
        // b=AS is kbps: 500_000 bps → 500.
        assertTrue(subSdp().contains("b=AS:500"))
    }

    @Test
    fun `main SDP keeps its aggregate control path by default`() {
        val sdp = SdpBuilder.build(
            sessionId = "1_abc",
            ip = "192.168.1.10",
            videoBitrate = 2_000_000,
            audioEnabled = false,
            audioSampleRateHz = 48_000,
            audioChannelCount = 1,
            sps = null,
            pps = null,
            audioSpecificConfig = null,
        )
        assertTrue(sdp.contains("a=control:stream"))
        assertFalse(sdp.contains("a=control:sub"))
    }

    // ── fixed sub-stream constants ──

    @Test
    fun `sub dimensions are the fixed 480p detect size`() {
        assertEquals(640, StreamDefaults.RTSP_SUB_VIDEO_WIDTH)
        assertEquals(480, StreamDefaults.RTSP_SUB_VIDEO_HEIGHT)
    }

    @Test
    fun `sub bitrate sits at the encoder floor`() {
        // The low-res cap equals the sane minimum, so the clamp is a no-op.
        val clamped = StreamDefaults.RTSP_SUB_VIDEO_BITRATE
            .coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX)
        assertEquals(StreamDefaults.VIDEO_BITRATE_MIN, clamped)
        assertTrue(clamped < StreamDefaults.RTSP_VIDEO_BITRATE)
    }

    @Test
    fun `sub path name matches the URI grammar`() {
        assertEquals("sub", RtspUriPolicy.SUB_STREAM_PATH)
    }
}

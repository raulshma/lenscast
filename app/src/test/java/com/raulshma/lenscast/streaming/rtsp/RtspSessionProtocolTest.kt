package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspSessionProtocolTest {

    // ── SETUP transport header ──

    @Test
    fun `tcp interleaved header yields its channel pair`() {
        val verdict = RtspSessionProtocol.parseTransportHeader("RTP/AVP/TCP;unicast;interleaved=0-1")
        assertEquals(
            RtspSessionProtocol.InterleavedChannels(rtp = 0, rtcp = 1),
            (verdict as RtspSessionProtocol.TransportVerdict.Interleaved).channels,
        )
    }

    @Test
    fun `transport matching is case-insensitive`() {
        val verdict = RtspSessionProtocol.parseTransportHeader("rtp/avp/tcp;INTERLEAVED=4-5")
        assertEquals(
            RtspSessionProtocol.InterleavedChannels(rtp = 4, rtcp = 5),
            (verdict as RtspSessionProtocol.TransportVerdict.Interleaved).channels,
        )
    }

    @Test
    fun `tcp header without interleaved delivery stays unsupported like the wire's 461`() {
        // The server has always required an explicit interleaved marker on the Transport header.
        val verdict = RtspSessionProtocol.parseTransportHeader("RTP/AVP/TCP;unicast")
        assertTrue(verdict is RtspSessionProtocol.TransportVerdict.Unsupported)
    }

    @Test
    fun `udp transport is unsupported`() {
        val verdict = RtspSessionProtocol.parseTransportHeader(
            "RTP/AVP/UDP;unicast;client_port=5000-5001"
        )
        assertTrue(verdict is RtspSessionProtocol.TransportVerdict.Unsupported)
    }

    @Test
    fun `missing transport header is unsupported`() {
        assertTrue(RtspSessionProtocol.parseTransportHeader(null) is RtspSessionProtocol.TransportVerdict.Unsupported)
        assertTrue(RtspSessionProtocol.parseTransportHeader("") is RtspSessionProtocol.TransportVerdict.Unsupported)
    }

    // ── CSeq monotonicity ladder ──

    @Test
    fun `first request accepts any non-negative cseq`() {
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Ok(0),
            RtspSessionProtocol.cseqVerdict(lastCSeq = -1, cseqHeader = "0"),
        )
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Ok(7),
            RtspSessionProtocol.cseqVerdict(lastCSeq = -1, cseqHeader = "7"),
        )
    }

    @Test
    fun `missing unparsable or negative cseq rejects with a response cseq of zero`() {
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Reject(0, "missing, unparsable, or negative CSeq"),
            RtspSessionProtocol.cseqVerdict(lastCSeq = 5, cseqHeader = null),
        )
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Reject(0, "missing, unparsable, or negative CSeq"),
            RtspSessionProtocol.cseqVerdict(lastCSeq = 5, cseqHeader = "abc"),
        )
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Reject(0, "missing, unparsable, or negative CSeq"),
            RtspSessionProtocol.cseqVerdict(lastCSeq = -1, cseqHeader = "-1"),
        )
    }

    @Test
    fun `non-increasing cseq rejects with its own value`() {
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Reject(5, "CSeq does not strictly increase"),
            RtspSessionProtocol.cseqVerdict(lastCSeq = 5, cseqHeader = "5"),
        )
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Reject(4, "CSeq does not strictly increase"),
            RtspSessionProtocol.cseqVerdict(lastCSeq = 5, cseqHeader = "4"),
        )
    }

    @Test
    fun `strictly increasing cseq is accepted`() {
        assertEquals(
            RtspSessionProtocol.CSeqVerdict.Ok(6),
            RtspSessionProtocol.cseqVerdict(lastCSeq = 5, cseqHeader = "6"),
        )
    }

    // ── Session header ──

    @Test
    fun `session header parses before the first parameter separator`() {
        assertEquals("abc_123", RtspSessionProtocol.parseSessionHeader("abc_123;timeout=60"))
        assertEquals("plain", RtspSessionProtocol.parseSessionHeader("plain"))
    }

    @Test
    fun `session header is trimmed`() {
        assertEquals("s1", RtspSessionProtocol.parseSessionHeader("  s1  "))
    }

    @Test
    fun `missing session header parses to null`() {
        assertNull(RtspSessionProtocol.parseSessionHeader(null))
    }

    // ── RTP-Info ──

    @Test
    fun `rtp-info renders video only without an audio entry`() {
        val header = RtspSessionProtocol.buildRtpInfo(
            RtspSessionProtocol.RtpInfoEntry("rtsp://10.0.0.1:8554/stream", 101, 90000),
            audio = null,
        )
        assertEquals("url=rtsp://10.0.0.1:8554/stream;seq=101;rtptime=90000", header)
    }

    @Test
    fun `rtp-info appends the audio entry comma-joined`() {
        val header = RtspSessionProtocol.buildRtpInfo(
            RtspSessionProtocol.RtpInfoEntry("rtsp://10.0.0.1:8554/stream", 101, 90000),
            audio = RtspSessionProtocol.RtpInfoEntry("rtsp://10.0.0.1:8554/stream/trackID=1", 42, 1024),
        )
        assertEquals(
            "url=rtsp://10.0.0.1:8554/stream;seq=101;rtptime=90000," +
                "url=rtsp://10.0.0.1:8554/stream/trackID=1;seq=42;rtptime=1024",
            header,
        )
    }

    // ── RTCP sender report ──

    /** Big-endian int32, matching the sender report's wire order. */
    private fun beInt(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun expectedSenderReport(
        ssrc: Int,
        rtpTimestamp: Long,
        packets: Long,
        octets: Long,
        ntpSeconds: Long,
        ntpFraction: Long,
    ): ByteArray = byteArrayOf(
        0x80.toByte(), 200.toByte(), 0x00, 0x06,
    ) + beInt(ssrc.toLong() and 0xFFFFFFFFL) +
        beInt(ntpSeconds) + beInt(ntpFraction) +
        beInt(rtpTimestamp and 0xFFFFFFFFL) +
        beInt(packets and 0xFFFFFFFFL) +
        beInt(octets and 0xFFFFFFFFL)

    @Test
    fun `sender report is the exact 28-byte packet for a whole-second wall clock`() {
        // 1970-01-01T00:33:20Z + the NTP offset → whole seconds, zero fraction.
        val report = RtspSessionProtocol.senderReport(
            ssrc = 0x11223344,
            rtpTimestamp = 90000L,
            packets = 3L,
            octets = 1200L,
            wallClockMs = 20_000L,
        )
        // (20_000 + 2_208_988_800_000) / 1000 = 2_208_988_820, fraction 0.
        assertArrayEquals(
            expectedSenderReport(
                ssrc = 0x11223344,
                rtpTimestamp = 90000L,
                packets = 3L,
                octets = 1200L,
                ntpSeconds = 2_208_988_820L,
                ntpFraction = 0L,
            ),
            report,
        )
        assertEquals(28, report.size)
    }

    @Test
    fun `sender report fraction derives from the sub-second milliseconds`() {
        // 500 ms → (500 shl 32) / 1000 = 2147483648 → 0x80000000.
        val report = RtspSessionProtocol.senderReport(
            ssrc = 1,
            rtpTimestamp = 0L,
            packets = 0L,
            octets = 0L,
            wallClockMs = 1_500L,
        )
        assertArrayEquals(
            expectedSenderReport(
                ssrc = 1,
                rtpTimestamp = 0L,
                packets = 0L,
                octets = 0L,
                ntpSeconds = 2_208_988_801L,
                ntpFraction = 2_147_483_648L,
            ),
            report,
        )
    }

    @Test
    fun `sender report wraps counters and rtp timestamps to 32 bits`() {
        val report = RtspSessionProtocol.senderReport(
            ssrc = -1,
            rtpTimestamp = 0x1_FFFF_FFFFL,
            packets = 0x1_0000_0001L,
            octets = -1L,
            wallClockMs = 0L,
        )
        // Only pin the wrap behavior on the counter fields; the NTP fields are covered above.
        assertArrayEquals(beInt(0xFFFF_FFFFL), report.copyOfRange(16, 20))  // rtpTimestamp & 0xFFFFFFFF
        assertArrayEquals(beInt(1L), report.copyOfRange(20, 24))            // packets & 0xFFFFFFFF
        assertArrayEquals(beInt(0xFFFF_FFFFL), report.copyOfRange(24, 28))  // octets & 0xFFFFFFFF
    }

    // ── Interleaved framing ──

    @Test
    fun `interleaved frame prefixes the dollar magic, channel, and big-endian size`() {
        val frame = RtspSessionProtocol.interleavedFrame(channel = 0, payload = byteArrayOf(1, 2, 3))
        assertEquals(byteArrayOf(0x24, 0x00, 0x00, 0x03, 1, 2, 3).toList(), frame.toList())
    }

    @Test
    fun `interleaved frame splits a two-byte size high-low`() {
        val payload = ByteArray(300)
        val frame = RtspSessionProtocol.interleavedFrame(channel = 2, payload = payload)
        assertEquals(304, frame.size)
        assertEquals(0x24.toByte(), frame[0])
        assertEquals(0x02.toByte(), frame[1])
        assertEquals(0x01.toByte(), frame[2]) // 300 shr 8
        assertEquals(0x2C.toByte(), frame[3]) // 300 and 0xFF
    }
}

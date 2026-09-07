package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AacRtpPacketizerTest {

    private fun auHeaderBits(p: ByteArray): Int =
        ((p[14].toInt() and 0xFF) shl 8) or (p[15].toInt() and 0xFF)

    @Test
    fun `packet layout carries one au in aac-hbr framing`() {
        val packetizer = AacRtpPacketizer()
        val au = ByteArray(101) { (it * 7 + 3).toByte() }
        val p = packetizer.packetize(au, timestamp = 90_000L)

        // 12-byte RTP header + 2 bytes AU-headers-length + 2 bytes AU-header + AU data.
        assertEquals(12 + 4 + 101, p.size)
        assertEquals(0x00, p[12].toInt() and 0xFF)
        assertEquals(0x10, p[13].toInt() and 0xFF) // one 16-bit AU-header
        // AU-header: AU-size in the top 13 bits, AU-index 0 in the low 3 bits.
        assertEquals(101 shl 3, auHeaderBits(p))
        assertEquals(0, auHeaderBits(p) and 0b111)
        assertArrayEquals(au, p.copyOfRange(16, p.size))
    }

    @Test
    fun `rtp header fields match the audio stream contract`() {
        val packetizer = AacRtpPacketizer()
        val p1 = packetizer.packetize(ByteArray(10), timestamp = 3_750L)
        val p2 = packetizer.packetize(ByteArray(10), timestamp = 7_500L)

        assertEquals(0x80, p1[0].toInt() and 0xFF) // V=2
        // Marker always set (one complete AAC frame per packet) with PT 97.
        assertEquals(0x80 or 97, p1[1].toInt() and 0xFF)
        assertEquals(0x80 or 97, p2[1].toInt() and 0xFF)

        val ts = { p: ByteArray ->
            ((p[4].toInt() and 0xFF) shl 24) or ((p[5].toInt() and 0xFF) shl 16) or
                ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        }
        assertEquals(3_750L, ts(p1).toLong() and 0xFFFFFFFFL)
        assertEquals(7_500L, ts(p2).toLong() and 0xFFFFFFFFL)

        // Sequence advances by one per AU; SSRC is the stream state's.
        val seq = { p: ByteArray -> ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF) }
        assertEquals(0, seq(p1))
        assertEquals(1, seq(p2))
        assertEquals(1, packetizer.currentSeq)
        val ssrcOf = { p: ByteArray ->
            ((p[8].toInt() and 0xFF) shl 24) or ((p[9].toInt() and 0xFF) shl 16) or
                ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
        }
        assertEquals(ssrcOf(p1), ssrcOf(p2))
    }

    @Test
    fun `au header packs a 13-bit size at the boundary`() {
        val packetizer = AacRtpPacketizer()
        val p = packetizer.packetize(ByteArray(8_191), timestamp = 0)
        assertEquals(8_191 shl 3, auHeaderBits(p))
        assertEquals(8_191, auHeaderBits(p) ushr 3)
    }

    @Test
    fun `empty access unit still produces the framing bytes`() {
        val packetizer = AacRtpPacketizer()
        val p = packetizer.packetize(ByteArray(0), timestamp = 0)
        assertEquals(16, p.size)
        assertEquals(0, auHeaderBits(p)) // AU-size 0, AU-index 0
        assertArrayEquals(ByteArray(0), p.copyOfRange(16, p.size))
    }

    @Test
    fun `sequence advances once per access unit and is exposed via currentSeq`() {
        val packetizer = AacRtpPacketizer()
        packetizer.packetize(ByteArray(100), timestamp = 0)
        packetizer.packetize(ByteArray(50), timestamp = 3_750)
        packetizer.packetize(ByteArray(50), timestamp = 7_500)

        assertEquals(2, packetizer.currentSeq)
    }
}

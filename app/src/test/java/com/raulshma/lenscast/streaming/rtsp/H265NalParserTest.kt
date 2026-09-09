package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H265NalParserTest {

    // H.265 NAL header builders: byte0 = forbidden(1) | type(6) | layerIdHigh(1),
    // byte1 = layerIdLow(5) | temporalIdPlus1(3). Bodies avoid 00 00 01 sequences,
    // exactly like the H264NalParser test vectors.

    private fun nal(type: Int, size: Int, layerIdHigh: Int = 0, byte1: Int = 0x01): ByteArray =
        ByteArray(size) { i ->
            when (i) {
                0 -> (((type and 0x3F) shl 1) or (layerIdHigh and 0x01)).toByte()
                1 -> byte1.toByte()
                else -> ((i % 251) + 1).toByte()
            }
        }

    @Test
    fun `nal type reads the six type bits out of the two-byte header`() {
        // VPS(32): byte0 = 32 << 1 = 0x40.
        assertEquals(32, H265NalParser.nalType(byteArrayOf(0x40, 0x01)))
        // SPS(33) → 0x42, PPS(34) → 0x44.
        assertEquals(33, H265NalParser.nalType(byteArrayOf(0x42, 0x01)))
        assertEquals(34, H265NalParser.nalType(byteArrayOf(0x44, 0x01)))
        // IDR_W_RADL(19) → 0x26, IDR_N_LP(20) → 0x28, TRAIL_R(1) → 0x02.
        assertEquals(19, H265NalParser.nalType(byteArrayOf(0x26, 0x01)))
        assertEquals(20, H265NalParser.nalType(byteArrayOf(0x28, 0x01)))
        assertEquals(1, H265NalParser.nalType(byteArrayOf(0x02, 0x01)))
    }

    @Test
    fun `layer id high bit and temporal id do not disturb the type`() {
        // layerIdHigh=1 shifts byte0 to 0x41; type is still 32.
        assertEquals(32, H265NalParser.nalType(byteArrayOf(0x41, 0x00)))
        // byte1 carries layerIdLow|TID only — never part of the type.
        assertEquals(33, H265NalParser.nalType(byteArrayOf(0x42, 0x7F.toByte())))
        // All type bits set (63): byte0 = 0x7E.
        assertEquals(63, H265NalParser.nalType(byteArrayOf(0x7E, 0x01)))
    }

    @Test
    fun `forbidden bit set still decodes the type`() {
        // F=1 with type 1: byte0 = 0x80 or 0x02 = 0x82.
        assertEquals(1, H265NalParser.nalType(byteArrayOf(0x82.toByte(), 0x01)))
    }

    @Test
    fun `short nals answer negative not a bogus type`() {
        assertEquals(-1, H265NalParser.nalType(ByteArray(0)))
        assertEquals(-1, H265NalParser.nalType(byteArrayOf(0x42))) // one byte cannot carry an H.265 header
    }

    @Test
    fun `vps sps and pps detection matches types 32 33 34`() {
        assertTrue(H265NalParser.isVps(nal(32, 12)))
        assertFalse(H265NalParser.isVps(nal(33, 12)))
        assertTrue(H265NalParser.isSps(nal(33, 12)))
        assertFalse(H265NalParser.isSps(nal(34, 12)))
        assertTrue(H265NalParser.isPps(nal(34, 6)))
        assertFalse(H265NalParser.isPps(nal(32, 6)))
    }

    @Test
    fun `idr detection covers both idr variants and rejects cra and trail`() {
        assertTrue(H265NalParser.isIdr(nal(19, 20)))   // IDR_W_RADL
        assertTrue(H265NalParser.isIdr(nal(20, 20)))   // IDR_N_LP
        assertFalse(H265NalParser.isIdr(nal(21, 20)))  // CRA — not a guaranteed-clean join point
        assertFalse(H265NalParser.isIdr(nal(1, 20)))   // TRAIL_R
    }

    @Test
    fun `containsIdr detects idr among parameter sets and non-idr nals`() {
        val au = listOf(nal(32, 10), nal(33, 10), nal(34, 10), nal(20, 3000))
        assertTrue(H265NalParser.containsIdr(au))
        assertFalse(H265NalParser.containsIdr(listOf(nal(32, 10), nal(1, 20))))
        assertFalse(H265NalParser.containsIdr(emptyList()))
    }

    @Test
    fun `annex-b extraction reuses the h264 framing on two-byte-header nals`() {
        val vps = nal(32, 12)
        val sps = nal(33, 15)
        val idr = nal(20, 2000)
        val stream = byteArrayOf(0, 0, 0, 1) + vps + byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + idr

        val units = H265NalParser.extractAnnexBNalUnits(stream)

        assertEquals(3, units.size)
        assertArrayEquals(vps, units[0])
        assertArrayEquals(sps, units[1])
        assertArrayEquals(idr, units[2])
        assertEquals(listOf(32, 33, 20), units.map { H265NalParser.nalType(it) })
    }

    @Test
    fun `stripStartCode removes four-byte start code`() {
        val vps = nal(32, 10)
        assertArrayEquals(vps, H265NalParser.stripStartCode(byteArrayOf(0, 0, 0, 1) + vps))
        assertArrayEquals(vps, H265NalParser.stripStartCode(vps.copyOf()))
    }

    @Test
    fun `fmtp carries the base64 sprop triple when all parameter sets exist`() {
        val fmtp = H265NalParser.buildFmtp("QHgA", "SIH+", "aO4N")
        assertEquals("sprop-vps=QHgA;sprop-sps=SIH+;sprop-pps=aO4N", fmtp)
    }

    @Test
    fun `fmtp is null unless all three parameter sets are available`() {
        assertEquals(null, H265NalParser.buildFmtp(null, "SIH+", "aO4N"))
        assertEquals(null, H265NalParser.buildFmtp("QHgA", null, "aO4N"))
        assertEquals(null, H265NalParser.buildFmtp("QHgA", "SIH+", null))
        assertEquals(null, H265NalParser.buildFmtp("", "", ""))
    }
}

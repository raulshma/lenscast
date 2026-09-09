package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264NalParserTest {

    private fun nal(type: Int, size: Int): ByteArray =
        // Body avoids zero bytes: real H.264 NAL payloads never contain raw
        // 00 00 01 / 00 00 00 01 (emulation prevention), so test vectors must not either.
        ByteArray(size) { i ->
            if (i == 0) ((type and 0x1F) or 0x60).toByte() else ((i % 251) + 1).toByte()
        }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    @Test
    fun `annex-b buffer with sps pps and idr splits into three nals`() {
        val sps = nal(7, 12)
        val pps = nal(8, 4)
        val idr = nal(5, 2000)
        val units = H264NalParser.extractNalUnits(annexB(sps, pps, idr))

        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
        assertEquals(7, H264NalParser.nalType(units[0]))
        assertEquals(8, H264NalParser.nalType(units[1]))
        assertEquals(5, H264NalParser.nalType(units[2]))
    }

    @Test
    fun `three-byte start codes are supported`() {
        val a = nal(1, 40)
        val b = nal(1, 60)
        val stream = byteArrayOf(0, 0, 1) + a + byteArrayOf(0, 0, 1) + b
        val units = H264NalParser.extractNalUnits(stream)

        assertEquals(2, units.size)
        assertArrayEquals(a, units[0])
        assertArrayEquals(b, units[1])
    }

    @Test
    fun `mixed three and four byte start codes are supported`() {
        val a = nal(6, 10) // SEI
        val b = nal(5, 500)
        val stream = byteArrayOf(0, 0, 0, 1) + a + byteArrayOf(0, 0, 1) + b
        val units = H264NalParser.extractNalUnits(stream)

        assertEquals(listOf(6, 5), units.map { H264NalParser.nalType(it) })
    }

    @Test
    fun `avcc length-prefixed buffer is parsed when no annex-b start code present`() {
        val sps = nal(7, 15)
        val idr = nal(5, 300)
        val avcc = java.io.ByteArrayOutputStream()
        for (n in listOf(sps, idr)) {
            avcc.write(byteArrayOf((n.size shr 24).toByte(), (n.size shr 16).toByte(), (n.size shr 8).toByte(), n.size.toByte()))
            avcc.write(n)
        }
        val units = H264NalParser.extractNalUnits(avcc.toByteArray())

        assertEquals(2, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(idr, units[1])
    }

    @Test
    fun `garbage without start codes or valid avcc yields no nals`() {
        val garbage = ByteArray(64) { (it * 7 + 3).toByte() } // no 00 00 01, invalid sizes
        assertTrue(H264NalParser.extractNalUnits(garbage).isEmpty())
    }

    @Test
    fun `trailing zero bytes are tolerated on last nal`() {
        val idr = nal(5, 100)
        val stream = annexB(idr) + byteArrayOf(0, 0)
        val units = H264NalParser.extractNalUnits(stream)

        assertEquals(1, units.size)
        // Trailing zeros after the final NAL are consumed by the end-scan and kept; no crash, no loss.
        assertTrue(units[0].size >= idr.size)
        assertArrayEquals(idr, units[0].copyOf(idr.size))
    }

    @Test
    fun `stripStartCode removes four-byte start code`() {
        val sps = nal(7, 10)
        val csd = byteArrayOf(0, 0, 0, 1) + sps
        assertArrayEquals(sps, H264NalParser.stripStartCode(csd))
    }

    @Test
    fun `stripStartCode keeps csd without start code intact`() {
        val sps = nal(7, 10)
        assertArrayEquals(sps, H264NalParser.stripStartCode(sps.copyOf()))
    }

    @Test
    fun `profileLevelId derives high profile level 31 from sps`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F) + ByteArray(8)
        assertEquals("64001f", H264NalParser.profileLevelId(sps))
    }

    @Test
    fun `profileLevelId falls back to baseline when sps missing`() {
        assertEquals("42c01f", H264NalParser.profileLevelId(null))
        assertEquals("42c01f", H264NalParser.profileLevelId(byteArrayOf(0x67)))
    }

    @Test
    fun `fmtp includes sprop when both parameter sets available`() {
        val fmtp = H264NalParser.buildFmtp("64001f", "Z2QAH6y0AoAt", "aO4Niw==")
        assertEquals("packetization-mode=1;profile-level-id=64001f;sprop-parameter-sets=Z2QAH6y0AoAt,aO4Niw==", fmtp)
    }

    @Test
    fun `fmtp omits sprop cleanly without dangling separator when parameter sets missing`() {
        val fmtp = H264NalParser.buildFmtp("42c01f", null, null)
        assertEquals("packetization-mode=1;profile-level-id=42c01f", fmtp)
        assertTrue(!fmtp.endsWith(";"))
    }

    @Test
    fun `containsIdr detects idr slice among parameter sets and non-idr nals`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1f)
        val pps = byteArrayOf(0x68.toByte(), 0xa0.toByte())
        val idr = byteArrayOf(0x65, 0x00, 0x01)
        val nonIdr = byteArrayOf(0x41, 0x00, 0x02)
        assertTrue(H264NalParser.containsIdr(listOf(sps, pps, idr)))
        assertTrue(!H264NalParser.containsIdr(listOf(sps, pps, nonIdr)))
        assertTrue(!H264NalParser.containsIdr(emptyList()))
    }
}

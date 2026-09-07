package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpStreamStateTest {

    private fun readInt16(b2: Byte, b3: Byte): Int =
        ((b2.toInt() and 0xFF) shl 8) or (b3.toInt() and 0xFF)

    private fun readInt32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    // ── header layout ──

    @Test
    fun `header byte layout is version2 pt marker big-endian seq ts and ssrc`() {
        val state = RtpStreamState(ssrc = 0x11223344L)
        val header = state.nextHeader(
            timestamp = 3_750L, marker = false, payloadType = 96, payloadSize = 100
        )

        assertEquals(12, header.size)
        assertEquals(0x80, header[0].toInt() and 0xFF) // V=2, P=0, X=0, CC=0
        assertEquals(96, header[1].toInt() and 0x7F) // PT without marker
        assertEquals(0, header[1].toInt() and 0x80) // marker clear
        assertEquals(0, readInt16(header[2], header[3])) // first sequence is 0
        assertEquals(3_750, readInt32(header, 4).toLong() and 0xFFFFFFFFL)
        assertEquals(0x11223344, readInt32(header, 8))
    }

    @Test
    fun `marker bit sets the high bit of the m-plus-pt byte`() {
        val state = RtpStreamState(ssrc = 1L)
        val marked = state.nextHeader(0, marker = true, payloadType = 96, payloadSize = 0)
        assertEquals((0x80 or 96), marked[1].toInt() and 0xFF)

        val unmarked = state.nextHeader(0, marker = false, payloadType = 97, payloadSize = 0)
        assertEquals(97, unmarked[1].toInt() and 0xFF)
    }

    @Test
    fun `sequence numbers increment monotonically and currentSeq tracks the wire value`() {
        val state = RtpStreamState(ssrc = 1L)
        for (expected in 0..4) {
            val header = state.nextHeader(0, marker = false, payloadType = 96, payloadSize = 0)
            assertEquals(expected, readInt16(header[2], header[3]))
            assertEquals(expected, state.currentSeq)
        }
    }

    @Test
    fun `sequence wraps from 0xFFFF back to zero on the wire`() {
        val state = RtpStreamState(ssrc = 1L)
        // Call N emits sequence N-1, so 0xFFFF calls land on 0xFFFE.
        repeat(0xFFFF) { state.nextHeader(0, false, 96, 0) }
        assertEquals(0xFFFE, state.currentSeq)
        val last = state.nextHeader(0, false, 96, 0)
        assertEquals(0xFFFF, readInt16(last[2], last[3]))
        assertEquals(0xFFFF, state.currentSeq)
        // The next packet wraps to zero.
        val wrapped = state.nextHeader(0, false, 96, 0)
        assertEquals(0, readInt16(wrapped[2], wrapped[3]))
        assertEquals(0, state.currentSeq)
    }

    @Test
    fun `timestamps are truncated to 32 bits`() {
        val state = RtpStreamState(ssrc = 1L)
        val header = state.nextHeader(
            timestamp = 0x1_0000_0000L + 3_750, marker = false, payloadType = 96, payloadSize = 0
        )
        assertEquals(3_750, readInt32(header, 4).toLong() and 0xFFFFFFFFL)
    }

    // ── SSRC handling ──

    @Test
    fun `wireSsrc is the low 32 bits of the constructor ssrc and lands in the header`() {
        val state = RtpStreamState(ssrc = 0x1_AA_BB_CC_DDL)
        assertEquals(0xAA_BB_CC_DD.toInt(), state.wireSsrc)
        val header = state.nextHeader(0, false, 96, 0)
        assertEquals(
            state.wireSsrc.toLong() and 0xFFFFFFFFL,
            readInt32(header, 8).toLong() and 0xFFFFFFFFL
        )
    }

    @Test
    fun `negative ssrc masks to its unsigned wire form`() {
        val state = RtpStreamState(ssrc = -1L)
        assertEquals(-1, state.wireSsrc) // 0xFFFFFFFF as Int
        val header = state.nextHeader(0, false, 96, 0)
        assertArrayEquals(
            arrayOf<Byte>(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            arrayOf(header[8], header[9], header[10], header[11])
        )
    }

    // ── RTCP counters ──

    @Test
    fun `packet and octet counters accumulate payload sizes across headers`() {
        val state = RtpStreamState(ssrc = 1L)
        state.nextHeader(0, false, 96, payloadSize = 100)
        state.nextHeader(0, false, 96, payloadSize = 250)
        state.nextHeader(0, false, 96, payloadSize = 0)

        assertEquals(3L, state.sentPacketCount)
        assertEquals(350L, state.sentOctetCount)
    }

    @Test
    fun `two states produce independent sequence and counter spaces`() {
        val a = RtpStreamState(ssrc = 10L)
        val b = RtpStreamState(ssrc = 20L)
        a.nextHeader(0, false, 96, 0)
        a.nextHeader(0, false, 96, 0)
        val bHeader = b.nextHeader(0, false, 96, 0)
        assertTrue(a.sentPacketCount != b.sentPacketCount || a.wireSsrc != b.wireSsrc)
        assertEquals(0, readInt16(bHeader[2], bHeader[3]))
    }
}

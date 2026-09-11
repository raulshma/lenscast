package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RtmpHandshakeTest {

    // ── C0/C1 build ──

    @Test
    fun `c0c1 is the version byte plus a 1536-byte body`() {
        val out = RtmpHandshake.c0c1(Random(42))
        assertEquals(1 + RtmpHandshake.HANDSHAKE_SIZE, out.size)
        assertEquals(RtmpHandshake.RTMP_VERSION.toByte(), out[0])
    }

    @Test
    fun `c0c1 carries the time field and a zero ping payload`() {
        val timeMs = 0x01020304
        val out = RtmpHandshake.c0c1(Random(42), timeMs = timeMs)
        assertEquals(0x01, out[1].toInt() and 0xFF)
        assertEquals(0x02, out[2].toInt() and 0xFF)
        assertEquals(0x03, out[3].toInt() and 0xFF)
        assertEquals(0x04, out[4].toInt() and 0xFF)
        // Bytes 5..8 are the plain handshake's zero ping payload.
        for (i in 5..8) {
            assertEquals(0.toByte(), out[i])
        }
    }

    @Test
    fun `c0c1 random part is nonzero and nondeterministic across seeds`() {
        val a = RtmpHandshake.c0c1(Random(1))
        val b = RtmpHandshake.c0c1(Random(2))
        assertFalse(a.contentEquals(b))
        // At least a healthy share of the 1528 random bytes are nonzero.
        val nonzero = a.count { it != 0.toByte() }
        assertTrue("random body looks empty: $nonzero nonzero bytes", nonzero > 1000)
    }

    // ── S0/S1 parse ──

    @Test
    fun `s1From extracts the 1536-byte body after a plausible version byte`() {
        val s0s1 = ByteArray(1 + RtmpHandshake.HANDSHAKE_SIZE)
        s0s1[0] = 0x03
        val marker = ByteArray(RtmpHandshake.HANDSHAKE_SIZE) { (it % 251).toByte() }
        System.arraycopy(marker, 0, s0s1, 1, marker.size)

        val s1 = RtmpHandshake.s1From(s0s1)
        assertNotNull(s1)
        assertArrayEquals(marker, s1)
    }

    @Test
    fun `s1From refuses a short payload or an implausible version`() {
        assertNull(RtmpHandshake.s1From(ByteArray(RtmpHandshake.HANDSHAKE_SIZE))) // no S0 byte
        val badVersion = ByteArray(1 + RtmpHandshake.HANDSHAKE_SIZE)
        badVersion[0] = 0x80.toByte() // outside 0..0x7F
        assertNull(RtmpHandshake.s1From(badVersion))
        assertTrue(RtmpHandshake.isPlausibleS0(0x03))
        assertTrue(RtmpHandshake.isPlausibleS0(0x7F))
        assertFalse(RtmpHandshake.isPlausibleS0(0x80))
    }

    @Test
    fun `s1TimeMs reads the big-endian time field`() {
        val s1 = ByteArray(RtmpHandshake.HANDSHAKE_SIZE)
        s1[0] = 0x00; s1[1] = 0x01; s1[2] = 0xE2.toByte(); s1[3] = 0x40 // 123456
        assertEquals(123456, RtmpHandshake.s1TimeMs(s1))
        assertNull(RtmpHandshake.s1TimeMs(ByteArray(3)))
    }

    // ── C2 ──

    @Test
    fun `c2 echoes s1 verbatim`() {
        val s1 = ByteArray(RtmpHandshake.HANDSHAKE_SIZE) { (it * 7).toByte() }
        val c2 = RtmpHandshake.c2FromS1(s1)
        assertArrayEquals(s1, c2)
        // An independent copy: mutating the echo must not touch the S1.
        c2[0] = 0x55
        assertFalse(s1[0] == 0x55.toByte())
    }
}

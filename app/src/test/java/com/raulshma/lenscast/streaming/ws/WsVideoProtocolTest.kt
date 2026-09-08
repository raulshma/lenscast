package com.raulshma.lenscast.streaming.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsVideoProtocolTest {

    private fun nal(type: Int, size: Int = 8): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = type.toByte()
        for (i in 1 until size) bytes[i] = i.toByte()
        return bytes
    }

    private fun annexB(vararg units: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (unit in units) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(unit)
        }
        return out.toByteArray()
    }

    @Test
    fun `avcc conversion length-prefixes every nal`() {
        val a = nal(5, 10)
        val b = nal(1, 6)
        val avcc = WsVideoProtocol.nalUnitsToAvcc(listOf(a, b))
        assertEquals(4 + a.size + 4 + b.size, avcc.size)
        assertEquals(0, avcc[0].toInt())
        assertEquals(0, avcc[1].toInt())
        assertEquals(0, avcc[2].toInt())
        assertEquals(a.size, avcc[3].toInt())
        assertEquals(a.size.toLong(), 10L)
    }

    @Test
    fun `parameter sets are found by nal type`() {
        val sps = nal(7)
        val pps = nal(8)
        val idr = nal(5)
        val (foundSps, foundPps) = WsVideoProtocol.extractParameterSets(listOf(idr, sps, pps))!!
        assertTrue(foundSps.contentEquals(sps))
        assertTrue(foundPps.contentEquals(pps))
        assertEquals(null, WsVideoProtocol.extractParameterSets(listOf(idr)))
    }

    @Test
    fun `keyframe detection by idr type`() {
        assertTrue(WsVideoProtocol.containsKeyframe(listOf(nal(5))))
        assertTrue(!WsVideoProtocol.containsKeyframe(listOf(nal(1))))
    }

    @Test
    fun `avcc record carries sps and pps with 4-byte lengths`() {
        val sps = nal(7, 12)
        val pps = nal(8, 4)
        val record = WsVideoProtocol.avcC(sps, pps)
        assertEquals(1, record[0].toInt())
        assertEquals(sps[1], record[1])
        assertEquals(sps[2], record[2])
        assertEquals(sps[3], record[3])
        assertEquals(0xFF, record[4].toInt() and 0xFF)
        assertEquals(0xE1, record[5].toInt() and 0xFF)
        val spsLen = ((record[6].toInt() and 0xFF) shl 8) or (record[7].toInt() and 0xFF)
        assertEquals(sps.size, spsLen)
    }

    @Test
    fun `envelope frames carry magic and big-endian length`() {
        val payload = ByteArray(300) { it.toByte() }
        val framed = WsVideoProtocol.videoFrameAvcc(payload, isKeyFrame = true)
        assertEquals('L'.code.toByte(), framed[0])
        assertEquals('C'.code.toByte(), framed[1])
        assertEquals('K'.code.toByte(), framed[2])
        assertEquals('1'.code.toByte(), framed[3])
        assertEquals(300, ((framed[4].toInt() and 0xFF) shl 24) or ((framed[5].toInt() and 0xFF) shl 16) or
            ((framed[6].toInt() and 0xFF) shl 8) or (framed[7].toInt() and 0xFF))
        assertEquals(8 + payload.size, framed.size)
    }
}

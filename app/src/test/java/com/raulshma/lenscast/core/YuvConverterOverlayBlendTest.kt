package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoded-path overlay blend ([YuvConverter.blendNv21Overlay]) and the
 * ARGB→YUVA strip conversion ([YuvConverter.argbToYuvaStrip]) — exact NV21
 * byte math, no Android types.
 */
class YuvConverterOverlayBlendTest {

    // ── argbToYuvaStrip: BT.601 studio swing ──

    @Test
    fun `white maps to studio-swing white with neutral chroma`() {
        val strip = YuvConverter.argbToYuvaStrip(intArrayOf(0xFFFFFFFF.toInt()), 1, 1)
        assertEquals(235, strip[0].toInt() and 0xFF)
        assertEquals(128, strip[1].toInt() and 0xFF)
        assertEquals(128, strip[2].toInt() and 0xFF)
        assertEquals(255, strip[3].toInt() and 0xFF)
    }

    @Test
    fun `black maps to studio-swing black`() {
        val strip = YuvConverter.argbToYuvaStrip(intArrayOf(0xFF000000.toInt()), 1, 1)
        assertEquals(16, strip[0].toInt() and 0xFF)
        assertEquals(128, strip[1].toInt() and 0xFF)
        assertEquals(128, strip[2].toInt() and 0xFF)
    }

    @Test
    fun `mid gray maps near neutral`() {
        val strip = YuvConverter.argbToYuvaStrip(intArrayOf(0xFF808080.toInt()), 1, 1)
        assertEquals(126, strip[0].toInt() and 0xFF)
        assertEquals(128, strip[1].toInt() and 0xFF)
        assertEquals(128, strip[2].toInt() and 0xFF)
    }

    @Test
    fun `pure red keeps its BT-601 chroma signature`() {
        val strip = YuvConverter.argbToYuvaStrip(intArrayOf(0xFFFF0000.toInt()), 1, 1)
        assertEquals(81, strip[0].toInt() and 0xFF)
        assertEquals(90, strip[1].toInt() and 0xFF)
        assertEquals(240, strip[2].toInt() and 0xFF)
    }

    @Test
    fun `alpha passes through per pixel`() {
        val strip = YuvConverter.argbToYuvaStrip(intArrayOf(0x80FF0000.toInt(), 0x00FF0000), 2, 1)
        assertEquals(0x80, strip[3].toInt() and 0xFF)
        assertEquals(0, strip[7].toInt() and 0xFF)
    }

    // ── blendNv21Overlay ──

    private fun nv21Frame(width: Int, height: Int, luma: Int = 0, chroma: Int = 127): ByteArray =
        ByteArray(width * height * 3 / 2) { i ->
            if (i < width * height) luma.toByte() else chroma.toByte()
        }

    private fun strip(width: Int, height: Int, y: Int, u: Int, v: Int, a: Int): ByteArray =
        ByteArray(width * height * 4) { i ->
            when (i % 4) {
                0 -> y.toByte()
                1 -> u.toByte()
                2 -> v.toByte()
                else -> a.toByte()
            }
        }

    @Test
    fun `opaque strip replaces luma and block chroma exactly`() {
        val frame = nv21Frame(64, 64)
        val blended = YuvConverter.blendNv21Overlay(
            frame, 64, 64, strip(4, 2, y = 200, u = 54, v = 242, a = 255), 4, 2, 16, 16,
        )
        assertTrue(blended)

        for (row in 16..17) {
            for (x in 16..19) {
                assertEquals(200, frame[row * 64 + x].toInt() and 0xFF)
            }
        }
        // NV21 chroma: VU pair of block (cx=8, cy=8) covers luma (16..17, 16..17).
        val ySize = 64 * 64
        assertEquals(242, frame[ySize + 8 * 64 + 16].toInt() and 0xFF)
        assertEquals(54, frame[ySize + 8 * 64 + 17].toInt() and 0xFF)
        // Outside the strip: untouched.
        assertEquals(0, frame[15 * 64 + 16].toInt() and 0xFF)
        assertEquals(0, frame[18 * 64 + 16].toInt() and 0xFF)
    }

    @Test
    fun `fully transparent strip leaves the frame untouched`() {
        val frame = nv21Frame(64, 64)
        val before = frame.copyOf()
        YuvConverter.blendNv21Overlay(frame, 64, 64, strip(4, 2, y = 200, u = 54, v = 242, a = 0), 4, 2, 16, 16)
        org.junit.Assert.assertArrayEquals(before, frame)
    }

    @Test
    fun `partial alpha lerps luma toward the strip value`() {
        val frame = nv21Frame(64, 64, luma = 0)
        // src 200, dst 0, alpha 128 → (200*128 + 0*127 + 127) / 255 = 100
        YuvConverter.blendNv21Overlay(frame, 64, 64, strip(2, 2, y = 200, u = 128, v = 128, a = 128), 2, 2, 16, 16)
        assertEquals(100, frame[16 * 64 + 16].toInt() and 0xFF)
    }

    @Test
    fun `odd origins snap onto the even chroma lattice`() {
        val frame = nv21Frame(64, 64)
        YuvConverter.blendNv21Overlay(frame, 64, 64, strip(4, 2, y = 200, u = 54, v = 242, a = 255), 4, 2, 17, 15)
        // left floors 17→16, top floors 15→14: rows 14..15, cols 16..19.
        assertEquals(200, frame[14 * 64 + 16].toInt() and 0xFF)
        assertEquals(200, frame[15 * 64 + 19].toInt() and 0xFF)
        assertEquals(0, frame[16 * 64 + 16].toInt() and 0xFF)
        assertEquals(0, frame[14 * 64 + 15].toInt() and 0xFF)
    }

    @Test
    fun `a strip wider than the frame clamps instead of running over`() {
        val frame = nv21Frame(4, 4)
        val before = frame.copyOf()
        val blended = YuvConverter.blendNv21Overlay(
            frame, 4, 4, strip(8, 8, y = 200, u = 54, v = 242, a = 255), 8, 8, 0, 0,
        )
        assertTrue(blended)
        assertEquals(200, frame[3 * 4 + 3].toInt() and 0xFF)
        assertNotEquals(before[3 * 4 + 3], frame[3 * 4 + 3])
    }

    @Test
    fun `degenerate strip dimensions return false and leave the frame alone`() {
        val frame = nv21Frame(64, 64)
        val before = frame.copyOf()
        assertFalse(YuvConverter.blendNv21Overlay(frame, 64, 64, strip(1, 2, 200, 128, 128, 255), 1, 2, 0, 0))
        assertFalse(YuvConverter.blendNv21Overlay(frame, 64, 64, strip(2, 1, 200, 128, 128, 255), 2, 1, 0, 0))
        org.junit.Assert.assertArrayEquals(before, frame)
    }

    @Test
    fun `a frame buffer smaller than its declared size is rejected`() {
        val frame = ByteArray(10)
        assertFalse(YuvConverter.blendNv21Overlay(frame, 64, 64, strip(4, 2, 200, 128, 128, 255), 4, 2, 0, 0))
    }

    @Test
    fun `blending is idempotent for the same strip`() {
        val frame = nv21Frame(64, 64)
        val s = strip(4, 2, y = 200, u = 54, v = 242, a = 255)
        YuvConverter.blendNv21Overlay(frame, 64, 64, s, 4, 2, 16, 16)
        val once = frame.copyOf()
        YuvConverter.blendNv21Overlay(frame, 64, 64, s, 4, 2, 16, 16)
        org.junit.Assert.assertArrayEquals(once, frame)
    }
}

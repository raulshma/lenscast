package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.MaskingZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutPolicyTest {

    @Test
    fun `zone maps normally inside the frame`() {
        val zone = MaskingZone(x = 0.1f, y = 0.1f, width = 0.2f, height = 0.2f)
        val rect = OverlayLayoutPolicy.zoneToPixels(zone, 1000, 500)
        assertEquals(PixelRect(100, 50, 300, 150), rect)
        assertFalse(rect.isEmpty)
    }

    @Test
    fun `zone partially off-screen is coerced into bounds`() {
        val zone = MaskingZone(x = -0.1f, y = -0.1f, width = 0.3f, height = 0.3f)
        val rect = OverlayLayoutPolicy.zoneToPixels(zone, 1000, 1000)
        assertEquals(PixelRect(0, 0, 200, 200), rect)
    }

    @Test
    fun `zone overflowing the far edge is coerced`() {
        val zone = MaskingZone(x = 0.9f, y = 0.9f, width = 0.3f, height = 0.3f)
        val rect = OverlayLayoutPolicy.zoneToPixels(zone, 1000, 1000)
        assertEquals(PixelRect(900, 900, 1000, 1000), rect)
    }

    @Test
    fun `zone is empty when right equals left`() {
        val zone = MaskingZone(x = 0.5f, y = 0.5f, width = 0f, height = 0.2f)
        val rect = OverlayLayoutPolicy.zoneToPixels(zone, 1000, 1000)
        assertTrue(rect.isEmpty)
    }

    @Test
    fun `zero-size bitmap yields an empty rect`() {
        val zone = MaskingZone(x = 0.1f, y = 0.1f, width = 0.2f, height = 0.2f)
        assertTrue(OverlayLayoutPolicy.zoneToPixels(zone, 0, 500).isEmpty)
        assertTrue(OverlayLayoutPolicy.zoneToPixels(zone, 1000, 0).isEmpty)
    }

    @Test
    fun `pixelate 16px region by 16 collapses to 1x1`() {
        assertEquals(1 to 1, OverlayLayoutPolicy.pixelateDownscale(16, 16, 16))
    }

    @Test
    fun `pixelate zero and negative sizes are guarded to 1`() {
        assertEquals(100 to 100, OverlayLayoutPolicy.pixelateDownscale(100, 100, 0))
        assertEquals(100 to 100, OverlayLayoutPolicy.pixelateDownscale(100, 100, -5))
    }

    @Test
    fun `blur huge radius hits the 0_05 floor`() {
        // scale = (1 / (100 * 0.5)) = 0.02 -> coerced to 0.05
        assertEquals(10 to 5, OverlayLayoutPolicy.blurDownscale(200, 100, 100f))
    }

    @Test
    fun `blur tiny radius hits the 0_5 cap`() {
        // scale = (1 / (0.1 * 0.5)) = 20 -> coerced to 0.5
        assertEquals(100 to 50, OverlayLayoutPolicy.blurDownscale(200, 100, 0.1f))
    }

    @Test
    fun `blur zero and negative radius fall back to the floor`() {
        assertEquals(10 to 5, OverlayLayoutPolicy.blurDownscale(200, 100, 0f))
        assertEquals(10 to 5, OverlayLayoutPolicy.blurDownscale(200, 100, -4f))
    }

    @Test
    fun `color parses opaque white`() {
        assertEquals(0xFFFFFFFFL, OverlayLayoutPolicy.parseColorOrNull("#FFFFFF"))
    }

    @Test
    fun `color parses translucent black with and without hash`() {
        assertEquals(0x80000000L, OverlayLayoutPolicy.parseColorOrNull("#80000000"))
        assertEquals(0x80000000L, OverlayLayoutPolicy.parseColorOrNull("80000000"))
    }

    @Test
    fun `color returns null for invalid input`() {
        assertNull(OverlayLayoutPolicy.parseColorOrNull("notacolor"))
        assertNull(OverlayLayoutPolicy.parseColorOrNull("#ZZZ"))
        assertNull(OverlayLayoutPolicy.parseColorOrNull("#FFF"))
    }
}

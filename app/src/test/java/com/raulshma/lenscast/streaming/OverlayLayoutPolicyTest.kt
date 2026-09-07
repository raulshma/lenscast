package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutPolicyTest {

    /** Overlay settings with every line source off; tests switch on what they exercise. */
    private fun overlaySettings(
        showTimestamp: Boolean = false,
        showBranding: Boolean = false,
        showStatus: Boolean = false,
        showCustomText: Boolean = false,
        brandingText: String = "LensCast",
        customText: String = "custom line",
    ) = OverlaySettings(
        enabled = true,
        showTimestamp = showTimestamp,
        showBranding = showBranding,
        showStatus = showStatus,
        showCustomText = showCustomText,
        brandingText = brandingText,
        customText = customText,
    )

    private fun buildLines(
        showTimestamp: Boolean = false,
        showBranding: Boolean = false,
        showStatus: Boolean = false,
        clients: Int = 0,
        showCustomText: Boolean = false,
    ): List<String> = OverlayLayoutPolicy.buildOverlayLines(
        overlaySettings(
            showTimestamp = showTimestamp,
            showBranding = showBranding,
            showStatus = showStatus,
            showCustomText = showCustomText,
        ),
        clients,
        nowMs = 0L,
    )

    @Test
    fun `timestamp toggles a non-blank line`() {
        val withTimestamp = buildLines(showTimestamp = true)
        assertEquals(1, withTimestamp.size)
        assertTrue(withTimestamp.single().isNotBlank())
        assertTrue(buildLines(showTimestamp = false).isEmpty())
    }

    @Test
    fun `branding renders only when shown, and blank text renders nothing`() {
        assertEquals(listOf("LensCast"), buildLines(showBranding = true))
        assertEquals(
            emptyList<String>(),
            OverlayLayoutPolicy.buildOverlayLines(
                overlaySettings(showBranding = true, brandingText = "   "),
                clientCount = 0,
                nowMs = 0L,
            ),
        )
    }

    @Test
    fun `status line follows the client pluralization - zero clients hide it`() {
        assertTrue(buildLines(showStatus = true, clients = 0).isEmpty())
        assertEquals(listOf("1 viewer"), buildLines(showStatus = true, clients = 1))
        assertEquals(listOf("2 viewers"), buildLines(showStatus = true, clients = 2))
    }

    @Test
    fun `custom text renders only when shown, and blank text renders nothing`() {
        assertEquals(listOf("custom line"), buildLines(showCustomText = true))
        assertEquals(
            emptyList<String>(),
            OverlayLayoutPolicy.buildOverlayLines(
                overlaySettings(showCustomText = true, customText = ""),
                clientCount = 0,
                nowMs = 0L,
            ),
        )
    }

    @Test
    fun `all line sources stack in order timestamp branding status custom`() {
        val lines = OverlayLayoutPolicy.buildOverlayLines(
            overlaySettings(
                showTimestamp = true,
                showBranding = true,
                showStatus = true,
                showCustomText = true,
            ),
            clientCount = 2,
            nowMs = 0L,
        )
        assertEquals(4, lines.size)
        assertTrue(lines[0].isNotBlank()) // the timestamp
        assertEquals("LensCast", lines[1])
        assertEquals("2 viewers", lines[2])
        assertEquals("custom line", lines[3])
    }

    @Test
    fun `position maps each corner with the margin inset`() {
        // 1000x500 frame, 200x100 overlay box, margin 16.
        assertEquals(
            PixelRect(16, 16, 216, 116),
            OverlayLayoutPolicy.computeOverlayPosition(OverlayPosition.TOP_LEFT, 1000, 500, 200, 100),
        )
        assertEquals(
            PixelRect(784, 16, 984, 116),
            OverlayLayoutPolicy.computeOverlayPosition(OverlayPosition.TOP_RIGHT, 1000, 500, 200, 100),
        )
        assertEquals(
            PixelRect(16, 384, 216, 484),
            OverlayLayoutPolicy.computeOverlayPosition(OverlayPosition.BOTTOM_LEFT, 1000, 500, 200, 100),
        )
        assertEquals(
            PixelRect(784, 384, 984, 484),
            OverlayLayoutPolicy.computeOverlayPosition(OverlayPosition.BOTTOM_RIGHT, 1000, 500, 200, 100),
        )
        assertEquals(16, OverlayLayoutPolicy.OVERLAY_MARGIN_PX)
    }

    @Test
    fun `an overlay larger than the frame keeps the unclamped corner math`() {
        val rect = OverlayLayoutPolicy.computeOverlayPosition(OverlayPosition.BOTTOM_RIGHT, 100, 100, 200, 200)
        assertEquals(PixelRect(-116, -116, 84, 84), rect)
    }

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

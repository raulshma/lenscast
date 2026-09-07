package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The overlay/masking clamp policy the Settings Store applies on save —
 * exactly the clamps the Web API settings handler used to inline.
 */
class OverlaySettingsNormalizeTest {

    private fun zone(
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 0.2f,
        height: Float = 0.2f,
        pixelateSize: Int = 16,
        blurRadius: Float = 10f,
    ) = MaskingZone(x = x, y = y, width = width, height = height, pixelateSize = pixelateSize, blurRadius = blurRadius)

    // OverlaySettings.normalized

    @Test
    fun `in-range values pass through untouched`() {
        val candidate = OverlaySettings(
            fontSize = 28,
            padding = 8,
            lineHeight = 4,
            maskingZones = listOf(zone()),
        )
        assertEquals(candidate, OverlaySettings.normalized(candidate))
    }

    @Test
    fun `font size clamps to 8 to 120`() {
        assertEquals(8, OverlaySettings.normalized(OverlaySettings(fontSize = 1)).fontSize)
        assertEquals(120, OverlaySettings.normalized(OverlaySettings(fontSize = 999)).fontSize)
    }

    @Test
    fun `padding clamps to 0 to 48`() {
        assertEquals(0, OverlaySettings.normalized(OverlaySettings(padding = -5)).padding)
        assertEquals(48, OverlaySettings.normalized(OverlaySettings(padding = 100)).padding)
    }

    @Test
    fun `line height clamps to 0 to 32`() {
        assertEquals(0, OverlaySettings.normalized(OverlaySettings(lineHeight = -1)).lineHeight)
        assertEquals(32, OverlaySettings.normalized(OverlaySettings(lineHeight = 64)).lineHeight)
    }

    @Test
    fun `non-clamped fields pass through`() {
        val candidate = OverlaySettings(
            enabled = true,
            brandingText = "hello",
            position = OverlayPosition.BOTTOM_RIGHT,
            textColor = "#FF0000",
        )
        val normalized = OverlaySettings.normalized(candidate)
        assertEquals(true, normalized.enabled)
        assertEquals("hello", normalized.brandingText)
        assertEquals(OverlayPosition.BOTTOM_RIGHT, normalized.position)
        assertEquals("#FF0000", normalized.textColor)
    }

    // MaskingZone.normalized

    @Test
    fun `normalized coordinates coerce to 0 to 1`() {
        val normalized = MaskingZone.normalized(zone(x = -0.5f, y = 1.5f))
        assertEquals(0f, normalized.x)
        assertEquals(1f, normalized.y)
    }

    @Test
    fun `zone size coerces into its valid range`() {
        val normalized = MaskingZone.normalized(zone(width = 0f, height = 2f))
        assertEquals(0.01f, normalized.width)
        assertEquals(1f, normalized.height)
    }

    @Test
    fun `pixelate size clamps to 4 to 64`() {
        assertEquals(4, MaskingZone.normalized(zone(pixelateSize = 0)).pixelateSize)
        assertEquals(64, MaskingZone.normalized(zone(pixelateSize = 500)).pixelateSize)
    }

    @Test
    fun `blur radius clamps to 1 to 50`() {
        assertEquals(1f, MaskingZone.normalized(zone(blurRadius = -3f)).blurRadius)
        assertEquals(50f, MaskingZone.normalized(zone(blurRadius = 99f)).blurRadius)
    }

    @Test
    fun `zone identity and metadata pass through`() {
        val candidate = MaskingZone(id = "z1", label = "face", enabled = false, type = MaskingType.PIXELATE)
        val normalized = MaskingZone.normalized(candidate)
        assertEquals("z1", normalized.id)
        assertEquals("face", normalized.label)
        assertEquals(false, normalized.enabled)
        assertEquals(MaskingType.PIXELATE, normalized.type)
    }

    @Test
    fun `overlay normalize normalizes every zone`() {
        val candidate = OverlaySettings(
            maskingZones = listOf(zone(x = -1f), zone(width = 5f)),
        )
        val normalized = OverlaySettings.normalized(candidate)
        assertEquals(0f, normalized.maskingZones[0].x)
        assertEquals(1f, normalized.maskingZones[1].width)
    }
}

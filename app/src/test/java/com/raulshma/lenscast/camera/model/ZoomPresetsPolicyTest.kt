package com.raulshma.lenscast.camera.model

import androidx.camera.core.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure zoom-preset ladder behind the camera screen's chip
 * row: per-lens ratios, clamping into the live range, the MAX convention, the
 * fallback ladder without lens info, and the snap/active verdicts.
 */
class ZoomPresetsPolicyTest {

    private fun lens(focal: Float, back: Boolean = true) = CameraLensInfo(
        id = "lens-$focal",
        label = "L",
        lensFacing = if (back) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT,
        focalLength = focal,
        cameraSelector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
    )

    @Test
    fun `multi lens inventory derives one preset per focal ratio at or above 1x`() {
        // 4.3mm wide (the 1x reference), 8.6mm 2x tele. The 2.2mm ultra-wide
        // is sub-1x — a lens-switch target, outside the zoom range's floor.
        val lenses = listOf(lens(2.2f), lens(4.3f), lens(8.6f))
        val presets = ZoomPresetsPolicy.presets(lenses, 1f..10f)
        assertEquals(listOf(1f, 2f), presets.map { it.ratio })
        assertEquals(listOf("1.0x", "2.0x"), presets.map { it.label })
    }

    @Test
    fun `intermediate tele ratios survive between 1x and max`() {
        // 4.3 wide, 6.0 (~1.4x), 8.6 (2x), 20.9 (5x).
        val lenses = listOf(lens(4.3f), lens(6f), lens(8.6f), lens(20.9f))
        val presets = ZoomPresetsPolicy.presets(lenses, 1f..10f)
        assertEquals(4, presets.size)
        assertEquals(1f, presets.first().ratio, 1e-6f)
        assertEquals(4.86f, presets.last().ratio, 1e-3f)
    }

    @Test
    fun `ratio above the range ceiling collapses onto a MAX chip at the ceiling`() {
        // A 5x tele on a device that only zooms to 3x: the tele becomes MAX.
        val lenses = listOf(lens(4.3f), lens(21.5f))
        val presets = ZoomPresetsPolicy.presets(lenses, 1f..3f)
        assertEquals(2, presets.size)
        assertEquals(1f, presets[0].ratio, 1e-6f)
        assertEquals(3f, presets[1].ratio, 1e-6f)
        assertEquals("MAX", presets[1].label)
    }

    @Test
    fun `no lens info degrades to 1x plus max`() {
        val presets = ZoomPresetsPolicy.presets(emptyList(), 1f..5f)
        assertEquals(listOf(1f, 5f), presets.map { it.ratio })
        assertEquals("1.0x", presets[0].label)
        assertEquals("MAX", presets[1].label)
    }

    @Test
    fun `front only inventory degrades to 1x plus max`() {
        val presets = ZoomPresetsPolicy.presets(listOf(lens(3.4f, back = false)), 1f..7f)
        assertEquals(listOf(1f, 7f), presets.map { it.ratio })
        assertEquals("MAX", presets[1].label)
    }

    @Test
    fun `wide plus ultra-wide only inventory collapses to a hidden row`() {
        // No tele: after dropping the sub-1x ultra-wide, only 1x remains.
        val presets = ZoomPresetsPolicy.presets(listOf(lens(2.2f), lens(4.3f)), 1f..5f)
        assertEquals(listOf(1f), presets.map { it.ratio })
        assertTrue(presets.size < ZoomPresetsPolicy.MIN_PRESETS_FOR_ROW)
    }

    @Test
    fun `one x only range collapses to a single preset`() {
        // A device whose ceiling is 1x: even the fallback ladder collapses to
        // one chip — the caller renders "no row" below two presets.
        val presets = ZoomPresetsPolicy.presets(emptyList(), 1f..1f)
        assertEquals(listOf(1f), presets.map { it.ratio })
        assertTrue(presets.size < ZoomPresetsPolicy.MIN_PRESETS_FOR_ROW)
    }

    @Test
    fun `zero focal lengths never divide by zero`() {
        // The enumeration-failure fallback reports focal 0 — no 1x reference.
        val presets = ZoomPresetsPolicy.presets(listOf(lens(0f), lens(0f)), 1f..4f)
        assertEquals(listOf(1f, 4f), presets.map { it.ratio })
    }

    @Test
    fun `isActive pins within the epsilon only`() {
        val preset = ZoomPresetsPolicy.ZoomPreset(2f, "2.0x")
        assertTrue(ZoomPresetsPolicy.isActive(preset, 2.0001f))
        assertTrue(ZoomPresetsPolicy.isActive(preset, 1.995f))
        assertFalse(ZoomPresetsPolicy.isActive(preset, 2.1f))
    }

    @Test
    fun `nextPreset cycles upward and wraps from the top`() {
        val presets = listOf(
            ZoomPresetsPolicy.ZoomPreset(0.5f, "0.5x"),
            ZoomPresetsPolicy.ZoomPreset(1f, "1.0x"),
            ZoomPresetsPolicy.ZoomPreset(2f, "2.0x"),
        )
        assertEquals(1f, ZoomPresetsPolicy.nextPreset(presets, 0.5f)!!.ratio)
        assertEquals(2f, ZoomPresetsPolicy.nextPreset(presets, 1f)!!.ratio)
        // From the top preset: wraps back to the first (1x-class) preset.
        assertEquals(0.5f, ZoomPresetsPolicy.nextPreset(presets, 2f)!!.ratio)
        // An arbitrary ratio between presets snaps to the next one above.
        assertEquals(2f, ZoomPresetsPolicy.nextPreset(presets, 1.3f)!!.ratio)
    }

    @Test
    fun `nextPreset is null with fewer than two presets`() {
        assertNull(ZoomPresetsPolicy.nextPreset(listOf(ZoomPresetsPolicy.ZoomPreset(1f, "1.0x")), 1f))
    }

    @Test
    fun `main focal length is the wide-band lens and ignores front lenses`() {
        // The ultra-wide (2.2mm) is never the 1x reference — the Wide band is.
        val lenses = listOf(lens(2.2f), lens(4.3f), lens(3.4f, back = false))
        assertEquals(4.3f, ZoomPresetsPolicy.mainFocalLength(lenses)!!, 1e-6f)
        // All-ultrawide inventories fall back to the smallest positive focal.
        assertEquals(2.2f, ZoomPresetsPolicy.mainFocalLength(listOf(lens(2.2f), lens(2.4f)))!!, 1e-6f)
        assertNull(ZoomPresetsPolicy.mainFocalLength(emptyList()))
        assertNull(ZoomPresetsPolicy.mainFocalLength(listOf(lens(0f))))
    }
}

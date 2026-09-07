package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGesturesTest {

    private val range = 1f..10f

    @Test
    fun `identity scale is never a zoom change`() {
        assertNull(PreviewGestures.onScale(1f, 1f, range))
        assertNull(PreviewGestures.onScale(5f, 1f, range))
    }

    @Test
    fun `scale below the deadband produces no update`() {
        // 5f * 1.001f moves the ratio by ~0.005 — under SCALE_DEADBAND.
        assertNull(PreviewGestures.onScale(5f, 1.001f, range))
        assertNull(PreviewGestures.onScale(2f, 1.005f, range))
    }

    @Test
    fun `scale past the deadband returns the multiplied ratio`() {
        assertEquals(5.1f, PreviewGestures.onScale(5f, 1.02f, range)!!, 1e-4f)
        assertEquals(2.5f, PreviewGestures.onScale(5f, 0.5f, range)!!, 1e-4f)
    }

    @Test
    fun `scale clamps at the upper end`() {
        assertEquals(10f, PreviewGestures.onScale(5f, 5f, range)!!, 1e-4f)
        assertEquals(CameraSettings.ZOOM_RATIO_MAX, PreviewGestures.onScale(9f, 99f, range)!!, 1e-4f)
    }

    @Test
    fun `scale clamps at the lower end`() {
        assertEquals(1f, PreviewGestures.onScale(2f, 0.2f, range)!!, 1e-4f)
    }

    @Test
    fun `clamped no-op at the current bound is below the deadband`() {
        // Already at the min and pinching in: no settings update should fire.
        assertNull(PreviewGestures.onScale(1f, 0.5f, range))
    }

    @Test
    fun `tap requires identity zoom and minimal pan`() {
        assertTrue(PreviewGestures.isTap(1f, 0f))
        assertTrue(PreviewGestures.isTap(1f, PreviewGestures.TAP_MAX_PAN_PX))
        assertFalse(PreviewGestures.isTap(1.01f, 0f))
        assertFalse(PreviewGestures.isTap(1f, PreviewGestures.TAP_MAX_PAN_PX + 1f))
    }

    @Test
    fun `constants keep their contract values`() {
        assertEquals(0.01f, PreviewGestures.SCALE_DEADBAND, 0f)
        assertEquals(800L, PreviewGestures.INDICATOR_HIDE_DELAY_MS)
    }
}

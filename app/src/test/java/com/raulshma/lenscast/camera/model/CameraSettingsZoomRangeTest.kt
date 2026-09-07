package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSettingsZoomRangeTest {

    @Test
    fun `a modest device ceiling wins`() {
        assertEquals(1f..5f, CameraSettings.effectiveZoomRange(5f))
    }

    @Test
    fun `the persistence ceiling clamps strong devices`() {
        assertEquals(1f..10f, CameraSettings.effectiveZoomRange(50f))
        assertEquals(1f..CameraSettings.ZOOM_RATIO_MAX, CameraSettings.effectiveZoomRange(CameraSettings.ZOOM_RATIO_MAX))
    }

    @Test
    fun `sub-1x device max degrades to a fixed 1x range instead of inverting`() {
        assertEquals(1f..1f, CameraSettings.effectiveZoomRange(0.5f))
        assertEquals(1f..1f, CameraSettings.effectiveZoomRange(0f))
    }

    @Test
    fun `default published range equals the persistence ceiling`() {
        assertEquals(
            1f..10f,
            CameraSettings.effectiveZoomRange(CameraSettings.ZOOM_RATIO_MAX),
        )
    }
}

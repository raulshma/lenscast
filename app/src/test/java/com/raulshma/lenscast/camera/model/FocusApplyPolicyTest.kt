package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusApplyPolicyTest {

    @Test
    fun `first apply always re-establishes metering`() {
        assertTrue(FocusApplyPolicy.needsReapply(null, CameraSettings()))
    }

    @Test
    fun `same settings never re-fire metering`() {
        val settings = CameraSettings(zoomRatio = 3f, iso = 800)
        assertFalse(FocusApplyPolicy.needsReapply(settings, settings))
    }

    @Test
    fun `focus mode change re-fires metering`() {
        val previous = CameraSettings(focusMode = FocusMode.AUTO)
        val next = previous.copy(focusMode = FocusMode.CONTINUOUS_VIDEO)
        assertTrue(FocusApplyPolicy.needsReapply(previous, next))
    }

    @Test
    fun `focus distance change re-fires metering`() {
        val previous = CameraSettings(focusMode = FocusMode.MANUAL, focusDistance = 2f)
        val next = previous.copy(focusDistance = 7f)
        assertTrue(FocusApplyPolicy.needsReapply(previous, next))
    }

    @Test
    fun `zoom-only change leaves metering alone`() {
        val previous = CameraSettings(zoomRatio = 1f)
        val next = previous.copy(zoomRatio = 8f)
        assertFalse(FocusApplyPolicy.needsReapply(previous, next))
    }

    @Test
    fun `iso-only change leaves metering alone`() {
        val previous = CameraSettings(iso = 100)
        val next = previous.copy(iso = 1600)
        assertFalse(FocusApplyPolicy.needsReapply(previous, next))
    }

    @Test
    fun `exposure-only change leaves metering alone`() {
        val previous = CameraSettings(exposureCompensation = 0)
        val next = previous.copy(exposureCompensation = 3)
        assertFalse(FocusApplyPolicy.needsReapply(previous, next))
    }

    @Test
    fun `white balance and frame rate changes leave metering alone`() {
        val previous = CameraSettings(whiteBalance = WhiteBalance.AUTO, frameRate = 24)
        assertFalse(
            FocusApplyPolicy.needsReapply(
                previous,
                previous.copy(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 5500),
            )
        )
        assertFalse(FocusApplyPolicy.needsReapply(previous, previous.copy(frameRate = 60)))
    }

    @Test
    fun `stabilization or resolution changes leave metering alone`() {
        val previous = CameraSettings()
        assertFalse(FocusApplyPolicy.needsReapply(previous, previous.copy(stabilization = false)))
        assertFalse(FocusApplyPolicy.needsReapply(previous, previous.copy(resolution = Resolution.UHD_4K)))
    }
}

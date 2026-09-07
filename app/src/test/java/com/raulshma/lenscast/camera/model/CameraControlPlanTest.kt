package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraControlPlanTest {

    private val isoRange = 100..3200
    private val exposureRange = 1L..1_000_000_000L

    @Test
    fun `auto settings keep AE on and scene disabled`() {
        val plan = CameraControlPlan.from(CameraSettings(), isoRange, exposureRange)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, plan.aeMode)
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, plan.sceneMode)
        assertEquals(24, plan.fpsLower)
        assertEquals(24, plan.fpsUpper)
        assertNull(plan.sensorSensitivity)
        assertNull(plan.sensorExposureTimeNs)
    }

    @Test
    fun `manual iso clamps to device range and defaults exposure to one frame period`() {
        val settings = CameraSettings(iso = 999999, frameRate = 30)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertEquals(3200, plan.sensorSensitivity)
        assertEquals(1_000_000_000L / 30, plan.sensorExposureTimeNs)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, plan.aeMode)
    }

    @Test
    fun `explicit exposure time passes through unclamped - only the default is clamped`() {
        val settings = CameraSettings(exposureTime = 5_000_000_000L)
        val plan = CameraControlPlan.from(settings, isoRange, 1_000L..100_000L)
        assertEquals(5_000_000_000L, plan.sensorExposureTimeNs)
    }

    @Test
    fun `night vision clamps fps and locks AE`() {
        val settings = CameraSettings(frameRate = 60, nightVisionMode = NightVisionMode.ON)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertEquals(10, plan.fpsLower)
        assertEquals(15, plan.fpsUpper)
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, plan.sceneMode)
        assertFalse(plan.aeLock!!)
    }

    @Test
    fun `manual focus maps to AF off with distance`() {
        val settings = CameraSettings(focusMode = FocusMode.MANUAL, focusDistance = 5.5f)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(5.5f, plan.lensFocusDistance)
    }

    @Test
    fun `hdr survives the night-vision-off scene reset`() {
        val settings = CameraSettings(hdrMode = HdrMode.ON)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_HDR, plan.sceneMode)
    }

    @Test
    fun `manual white balance passes color temperature through`() {
        val settings = CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 5500)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(5500, plan.colorTemperatureKelvin)
    }

    @Test
    fun `manual white balance clamps out-of-range color temperature`() {
        val low = CameraControlPlan.from(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 100),
            isoRange,
            exposureRange,
        )
        assertEquals(CameraSettings.COLOR_TEMPERATURE_MIN, low.colorTemperatureKelvin)

        val high = CameraControlPlan.from(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 99999),
            isoRange,
            exposureRange,
        )
        assertEquals(CameraSettings.COLOR_TEMPERATURE_MAX, high.colorTemperatureKelvin)
    }

    @Test
    fun `non-manual white balance ignores stored color temperature`() {
        val settings = CameraSettings(whiteBalance = WhiteBalance.AUTO, colorTemperature = 5500)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertNull(plan.colorTemperatureKelvin)
    }

    @Test
    fun `default settings carry no color temperature`() {
        val plan = CameraControlPlan.from(CameraSettings(), isoRange, exposureRange)
        assertNull(plan.colorTemperatureKelvin)
    }

    // ── the exposure-compensation decision ──

    @Test
    fun `exposure index passes through within the device range`() {
        val index = CameraControlPlan.exposureIndex(
            CameraSettings(exposureCompensation = 3),
            rangeLower = -6,
            rangeUpper = 6,
            currentIndex = 0,
        )
        assertEquals(3, index)
    }

    @Test
    fun `exposure index clamps to the device range`() {
        val low = CameraControlPlan.exposureIndex(
            CameraSettings(exposureCompensation = -12),
            rangeLower = -6,
            rangeUpper = 6,
            currentIndex = 0,
        )
        assertEquals(-6, low)

        val high = CameraControlPlan.exposureIndex(
            CameraSettings(exposureCompensation = 12),
            rangeLower = -6,
            rangeUpper = 6,
            currentIndex = 0,
        )
        assertEquals(6, high)
    }

    @Test
    fun `exposure index skips when the device already applies it`() {
        assertNull(
            CameraControlPlan.exposureIndex(
                CameraSettings(exposureCompensation = 2),
                rangeLower = -6,
                rangeUpper = 6,
                currentIndex = 2,
            ),
        )
    }

    @Test
    fun `clamped exposure equal to the current index still skips`() {
        assertNull(
            CameraControlPlan.exposureIndex(
                CameraSettings(exposureCompensation = 9),
                rangeLower = -6,
                rangeUpper = 6,
                currentIndex = 6,
            ),
        )
    }

    // ── the metering ladder ──

    @Test
    fun `continuous focus modes meter with the auto-cancel window`() {
        assertEquals(
            CameraControlPlan.MeteringDecision.AutoCancelMetering,
            CameraControlPlan.meteringOnApply(FocusMode.CONTINUOUS_PICTURE),
        )
        assertEquals(
            CameraControlPlan.MeteringDecision.AutoCancelMetering,
            CameraControlPlan.meteringOnApply(FocusMode.CONTINUOUS_VIDEO),
        )
    }

    @Test
    fun `manual focus fires no metering`() {
        assertEquals(
            CameraControlPlan.MeteringDecision.None,
            CameraControlPlan.meteringOnApply(FocusMode.MANUAL),
        )
    }

    @Test
    fun `remaining focus modes meter bare`() {
        assertEquals(
            CameraControlPlan.MeteringDecision.PlainMetering,
            CameraControlPlan.meteringOnApply(FocusMode.AUTO),
        )
        assertEquals(
            CameraControlPlan.MeteringDecision.PlainMetering,
            CameraControlPlan.meteringOnApply(FocusMode.MACRO),
        )
    }

    @Test
    fun `a tap always meters with the auto-cancel window`() {
        assertEquals(
            CameraControlPlan.MeteringDecision.AutoCancelMetering,
            CameraControlPlan.meteringOnTap(),
        )
    }

    @Test
    fun `the auto-cancel window is five seconds`() {
        assertEquals(5L, CameraControlPlan.METERING_AUTO_CANCEL_SECONDS)
    }
}

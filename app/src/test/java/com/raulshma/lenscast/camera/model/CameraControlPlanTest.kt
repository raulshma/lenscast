package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `explicit exposure time clamps to the sensor range and fills a paired iso`() {
        val settings = CameraSettings(exposureTime = 5_000_000_000L)
        val plan = CameraControlPlan.from(settings, isoRange, 1_000L..100_000L)
        assertEquals(100_000L, plan.sensorExposureTimeNs)
        assertEquals(1650, plan.sensorSensitivity)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, plan.aeMode)
    }

    @Test
    fun `a long manual exposure caps the fps range to one frame period`() {
        val settings = CameraSettings(iso = 400, exposureTime = 125_000_000L, frameRate = 30)
        val plan = CameraControlPlan.from(settings, isoRange, exposureRange)
        assertEquals(8, plan.fpsUpper)
        assertEquals(8, plan.fpsLower)
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

    // ── the capability fold: what the bound camera advertises ──

    private fun planFor(settings: CameraSettings, support: CaptureSupport? = null): CameraControlPlan =
        if (support == null) {
            CameraControlPlan.from(settings, isoRange, exposureRange)
        } else {
            CameraControlPlan.from(settings, isoRange, exposureRange, support)
        }

    @Test
    fun `the unrestricted default produces the same plan as before`() {
        // Every settings shape the plan knows, with and without the new
        // parameter: unknown capability lists must not move a single field,
        // and nothing may be reported as unsupported.
        val shapes = listOf(
            CameraSettings(),
            CameraSettings(iso = 800, exposureTime = 20_000_000L, frameRate = 30),
            CameraSettings(nightVisionMode = NightVisionMode.ON, frameRate = 60),
            CameraSettings(nightVisionMode = NightVisionMode.AUTO),
            CameraSettings(nightVisionMode = NightVisionMode.AUTO, iso = 400),
            CameraSettings(hdrMode = HdrMode.ON),
            CameraSettings(sceneMode = CaptureRequest.CONTROL_SCENE_MODE_SPORTS.toString()),
            CameraSettings(focusMode = FocusMode.MANUAL, focusDistance = 3f),
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CameraSettings(whiteBalance = WhiteBalance.CLOUDY, stabilization = false),
        )
        shapes.forEach { settings ->
            val before = planFor(settings)
            assertEquals(before, planFor(settings, CaptureSupport.UNRESTRICTED))
            assertEquals(before, planFor(settings, CaptureSupport()))
            assertTrue("nothing unsupported under unknown lists: $settings", before.unsupported.isEmpty())
        }
    }

    @Test
    fun `an unadvertised scene mode resets to disabled and is reported`() {
        val plan = planFor(
            CameraSettings(nightVisionMode = NightVisionMode.ON, frameRate = 60),
            CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, CaptureRequest.CONTROL_SCENE_MODE_HDR)),
        )

        // Only the scene key is neutralised: the night-vision fps range and
        // the AE unlock are the plan's own decisions and stand.
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, plan.sceneMode)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_SCENE_MODE), plan.unsupported)
        assertEquals(10, plan.fpsLower)
        assertEquals(15, plan.fpsUpper)
        assertFalse(plan.aeLock!!)
    }

    @Test
    fun `an advertised scene mode passes through`() {
        val plan = planFor(
            CameraSettings(nightVisionMode = NightVisionMode.ON),
            CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_NIGHT)),
        )

        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, plan.sceneMode)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `scene mode disabled is always valid even when the device omits it from its list`() {
        val plan = planFor(
            CameraSettings(),
            CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_HDR)),
        )

        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, plan.sceneMode)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `an unadvertised AE mode falls back to on`() {
        // Night-vision AUTO asks for ON_AUTO_FLASH; a camera without a flash
        // unit does not advertise it and gets plain automatic exposure.
        val plan = planFor(
            CameraSettings(nightVisionMode = NightVisionMode.AUTO),
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.CONTROL_AE_MODE_OFF)),
        )

        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, plan.aeMode)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_AE_MODE), plan.unsupported)
        // The rest of the night-vision AUTO plan is untouched.
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT, plan.sceneMode)
        assertNull(plan.aeLock)
    }

    @Test
    fun `an advertised AE mode passes through`() {
        val plan = planFor(
            CameraSettings(nightVisionMode = NightVisionMode.AUTO),
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)),
        )

        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH, plan.aeMode)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `an unadvertised white balance mode falls back to auto and drops the Kelvin value`() {
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CaptureSupport(awbModes = setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)),
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorTemperatureKelvin)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_WHITE_BALANCE), plan.unsupported)
    }

    @Test
    fun `an advertised white balance mode keeps its Kelvin value`() {
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CaptureSupport(awbModes = setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_OFF)),
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(4200, plan.colorTemperatureKelvin)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `a camera without manual post-processing falls back to automatic white balance`() {
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CaptureSupport(
                awbModes = setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_OFF),
                manualPostProcessing = false,
            ),
        )

        // Manual white balance is AWB OFF driven by the color-correction
        // gains, and the gains need MANUAL_POST_PROCESSING. AWB OFF without
        // them is not a manual white balance — it is white balance off at
        // the default gains — so the whole request goes back to automatic.
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorTemperatureKelvin)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_MANUAL_WHITE_BALANCE), plan.unsupported)
    }

    @Test
    fun `manual white balance without a temperature falls back to automatic white balance too`() {
        // The fallback keys on the plan wanting manual white balance (AWB
        // OFF), not on a Kelvin value: with no temperature there are no
        // gains to send either way, and bare AWB OFF is the same hazard.
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL),
            CaptureSupport(manualPostProcessing = false),
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorTemperatureKelvin)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_MANUAL_WHITE_BALANCE), plan.unsupported)
    }

    @Test
    fun `a camera with manual post-processing keeps manual white balance`() {
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CaptureSupport(manualPostProcessing = true),
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(4200, plan.colorTemperatureKelvin)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `manual white balance is reported once even when the camera lacks both ways to it`() {
        // AWB OFF is not advertised and there is no manual post-processing:
        // one fallback (to automatic), one name.
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CaptureSupport(awbModes = setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO), manualPostProcessing = false),
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_WHITE_BALANCE), plan.unsupported)
    }

    @Test
    fun `a null manual post-processing flag keeps the Kelvin value`() {
        val plan = planFor(
            CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200),
            CaptureSupport(manualPostProcessing = null),
        )

        assertEquals(4200, plan.colorTemperatureKelvin)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `a camera without AE off drops the sensor pair with the AE mode`() {
        val plan = planFor(
            CameraSettings(iso = 3200, exposureTime = 100_000_000L, nightVisionMode = NightVisionMode.ON),
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)),
        )

        // The HAL ignores SENSOR_* under automatic exposure: sent anyway they
        // would claim a manual exposure the device never runs.
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, plan.aeMode)
        assertNull(plan.sensorSensitivity)
        assertNull(plan.sensorExposureTimeNs)
        assertEquals(
            listOf(CameraControlPlan.UNSUPPORTED_AE_MODE, CameraControlPlan.UNSUPPORTED_MANUAL_EXPOSURE),
            plan.unsupported,
        )
        // The rest of the plan is the fold's usual business: the night scene
        // mode is advertised and stands.
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, plan.sceneMode)
    }

    @Test
    fun `a camera without AE off does not report manual exposure when none was requested`() {
        val plan = planFor(
            CameraSettings(nightVisionMode = NightVisionMode.AUTO),
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON)),
        )

        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, plan.aeMode)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_AE_MODE), plan.unsupported)
    }

    @Test
    fun `the sensor pair rides a plan if and only if its AE mode is off`() {
        // The bundle's sensor keys and its AE mode must agree on every plan
        // the service can build — requested, folded, defaulted. A sensor pair
        // under AE ON would be ignored by the device and would give the
        // read-back values to compare that never run.
        val shapes = listOf(
            CameraSettings(),
            CameraSettings(iso = 800),
            CameraSettings(exposureTime = 20_000_000L),
            CameraSettings(iso = 1600, exposureTime = 125_000_000L, frameRate = 30),
            CameraSettings(nightVisionMode = NightVisionMode.ON, frameRate = 60),
            CameraSettings(nightVisionMode = NightVisionMode.AUTO),
            CameraSettings(nightVisionMode = NightVisionMode.AUTO, iso = 400),
            CameraSettings(hdrMode = HdrMode.ON),
        )
        val supports = listOf(
            CaptureSupport.UNRESTRICTED,
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_OFF, CaptureRequest.CONTROL_AE_MODE_ON)),
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON)),
            CaptureSupport(
                aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH),
                sceneModes = emptySet(),
                awbModes = emptySet(),
                afModes = emptySet(),
                opticalStabilizationModes = emptySet(),
                videoStabilizationModes = emptySet(),
                manualPostProcessing = false,
            ),
        )
        shapes.forEach { settings ->
            supports.forEach { support ->
                val plan = planFor(settings, support)
                val carriesSensor = plan.sensorSensitivity != null || plan.sensorExposureTimeNs != null
                assertEquals(
                    "sensor keys iff AE OFF, for $settings under $support",
                    plan.aeMode == CaptureRequest.CONTROL_AE_MODE_OFF,
                    carriesSensor,
                )
                assertEquals(
                    "ISO and shutter travel together, for $settings under $support",
                    plan.sensorSensitivity != null,
                    plan.sensorExposureTimeNs != null,
                )
            }
        }
    }

    @Test
    fun `an unadvertised focus mode drops AF off and the focus distance`() {
        val plan = planFor(
            CameraSettings(focusMode = FocusMode.MANUAL, focusDistance = 5.5f),
            CaptureSupport(afModes = setOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)),
        )

        assertNull(plan.afMode)
        assertNull(plan.lensFocusDistance)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_FOCUS), plan.unsupported)
    }

    @Test
    fun `an advertised focus mode passes through`() {
        val plan = planFor(
            CameraSettings(focusMode = FocusMode.MANUAL, focusDistance = 5.5f),
            CaptureSupport(afModes = setOf(CaptureRequest.CONTROL_AF_MODE_OFF, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)),
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, plan.afMode)
        assertEquals(5.5f, plan.lensFocusDistance)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `unadvertised optical stabilization switches off and is reported`() {
        val plan = planFor(
            CameraSettings(),
            CaptureSupport(opticalStabilizationModes = setOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)),
        )

        assertFalse(plan.opticalStabilizationOn)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_OPTICAL_STABILIZATION), plan.unsupported)
        // Video stabilization is a different list and is untouched.
        assertTrue(plan.videoStabilizationOn)
    }

    @Test
    fun `unadvertised video stabilization switches off and is reported`() {
        val plan = planFor(
            CameraSettings(),
            CaptureSupport(videoStabilizationModes = setOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)),
        )

        assertFalse(plan.videoStabilizationOn)
        assertEquals(listOf(CameraControlPlan.UNSUPPORTED_VIDEO_STABILIZATION), plan.unsupported)
        assertTrue(plan.opticalStabilizationOn)
    }

    @Test
    fun `stabilization that is already off is not reported`() {
        // Nothing was asked of the lens, so nothing was dropped — the log
        // line must not cry wolf on every lens without OIS.
        val plan = planFor(
            CameraSettings(stabilization = false),
            CaptureSupport(
                opticalStabilizationModes = setOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF),
                videoStabilizationModes = setOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
            ),
        )

        assertFalse(plan.opticalStabilizationOn)
        assertFalse(plan.videoStabilizationOn)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `one unadvertised control leaves its neighbours untouched`() {
        // The isolation the per-group submit split was after, now on the
        // values: manual exposure, night vision, manual focus and manual
        // white balance all stand; only the OIS request is retired.
        val settings = CameraSettings(
            iso = 1600,
            exposureTime = 50_000_000L,
            nightVisionMode = NightVisionMode.ON,
            focusMode = FocusMode.MANUAL,
            focusDistance = 2f,
            whiteBalance = WhiteBalance.MANUAL,
            colorTemperature = 5000,
        )
        val requested = planFor(settings)

        val plan = planFor(
            settings,
            CaptureSupport(
                sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, CaptureRequest.CONTROL_SCENE_MODE_DISABLED),
                aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_OFF, CaptureRequest.CONTROL_AE_MODE_ON),
                awbModes = setOf(CaptureRequest.CONTROL_AWB_MODE_OFF, CaptureRequest.CONTROL_AWB_MODE_AUTO),
                afModes = setOf(CaptureRequest.CONTROL_AF_MODE_OFF),
                opticalStabilizationModes = setOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF),
                videoStabilizationModes = setOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
                manualPostProcessing = true,
            ),
        )

        assertEquals(requested.copy(opticalStabilizationOn = false, unsupported = listOf(CameraControlPlan.UNSUPPORTED_OPTICAL_STABILIZATION)), plan)
    }

    @Test
    fun `a fully capable camera returns the requested plan`() {
        val settings = CameraSettings(iso = 1600, nightVisionMode = NightVisionMode.ON, focusMode = FocusMode.MANUAL, focusDistance = 2f)
        val everything = CaptureSupport(
            sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, CaptureRequest.CONTROL_SCENE_MODE_DISABLED),
            aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_OFF, CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH),
            awbModes = setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_OFF),
            afModes = setOf(CaptureRequest.CONTROL_AF_MODE_OFF, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            opticalStabilizationModes = setOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            videoStabilizationModes = setOf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            manualPostProcessing = true,
        )

        assertEquals(planFor(settings), planFor(settings, everything))
    }

    @Test
    fun `the unsupported report names each dropped control once in a stable order`() {
        val plan = planFor(
            CameraSettings(
                nightVisionMode = NightVisionMode.AUTO,
                focusMode = FocusMode.MANUAL,
                focusDistance = 2f,
                whiteBalance = WhiteBalance.MANUAL,
                colorTemperature = 5000,
            ),
            CaptureSupport(
                sceneModes = emptySet(),
                aeModes = emptySet(),
                awbModes = emptySet(),
                afModes = emptySet(),
                opticalStabilizationModes = emptySet(),
                videoStabilizationModes = emptySet(),
                manualPostProcessing = false,
            ),
        )

        // Report order is the fold's: scene, AE, WB, focus, then the two
        // stabilization lists. Manual white balance is not reported twice —
        // the Kelvin value was already retired by the WB fallback above.
        assertEquals(
            listOf(
                CameraControlPlan.UNSUPPORTED_SCENE_MODE,
                CameraControlPlan.UNSUPPORTED_AE_MODE,
                CameraControlPlan.UNSUPPORTED_WHITE_BALANCE,
                CameraControlPlan.UNSUPPORTED_FOCUS,
                CameraControlPlan.UNSUPPORTED_OPTICAL_STABILIZATION,
                CameraControlPlan.UNSUPPORTED_VIDEO_STABILIZATION,
            ),
            plan.unsupported,
        )
    }
}

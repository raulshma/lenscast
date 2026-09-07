package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest

/**
 * The CaptureRequest *decisions* — fps range, exposure, scene mode, white
 * balance, color temperature, focus — computed from [CameraSettings] plus the
 * device's live ranges. Pure data + pure builder, so the ISO/fps/night-vision
 * math is unit-testable on the JVM; the service only translates this plan into
 * [android.hardware.camera2.CaptureRequest] options. The metering and
 * exposure-index decisions ([meteringOnApply], [exposureIndex]) live here too,
 * as pure data — the [FocusMeteringAction][androidx.camera.core.FocusMeteringAction]
 * construction itself stays in the service.
 */
data class CameraControlPlan(
    val fpsLower: Int,
    val fpsUpper: Int,
    val videoStabilizationOn: Boolean,
    val opticalStabilizationOn: Boolean,
    /** Camera2 scene mode; always resolved (OFF resets to DISABLED). */
    val sceneMode: Int,
    val aeMode: Int,
    /** Null leaves AE unlock unset (night AUTO); otherwise false. */
    val aeLock: Boolean?,
    val sensorSensitivity: Int?,
    val sensorExposureTimeNs: Long?,
    val awbMode: Int,
    /** Null keeps the default continuous AF; MANUAL maps to AF off. */
    val afMode: Int?,
    val lensFocusDistance: Float?,
    /**
     * Manual white-balance color temperature in Kelvin, clamped to
     * [CameraSettings.COLOR_TEMPERATURE_MIN]..[CameraSettings.COLOR_TEMPERATURE_MAX].
     * Non-null only when [WhiteBalance.MANUAL] is selected and a temperature was
     * provided; null otherwise (auto WB modes ignore any stored temperature).
     */
    val colorTemperatureKelvin: Int?,
) {
    /** How a metering action should fire — the mode ladder in [meteringOnApply]. */
    sealed interface MeteringDecision {
        /** Meter, then auto-cancel after [METERING_AUTO_CANCEL_SECONDS]. */
        data object AutoCancelMetering : MeteringDecision

        /** Meter bare — it holds until the next metering replaces it. */
        data object PlainMetering : MeteringDecision

        /** Don't touch metering; MANUAL focus is driver-controlled. */
        data object None : MeteringDecision
    }

    companion object {
        /** The auto-cancel window, in seconds, for deliberate metering. */
        const val METERING_AUTO_CANCEL_SECONDS = 5L

        fun from(
            settings: CameraSettings,
            isoRange: ClosedRange<Int>,
            sensorExposureTimeRange: LongRange,
        ): CameraControlPlan {
            val hasManualExposure = settings.iso != null || settings.exposureTime != null

            var fpsLower = settings.frameRate
            var fpsUpper = settings.frameRate

            val videoStabilizationOn = settings.stabilization
            val opticalStabilizationOn = settings.stabilization

            var sceneMode: Int? = if (settings.hdrMode == HdrMode.ON) {
                CaptureRequest.CONTROL_SCENE_MODE_HDR
            } else {
                null
            }

            val baseAeMode =
                if (hasManualExposure) CaptureRequest.CONTROL_AE_MODE_OFF
                else CaptureRequest.CONTROL_AE_MODE_ON

            val iso = settings.iso?.coerceIn(isoRange)
            val exposureTimeNs = if (hasManualExposure) {
                settings.exposureTime ?: run {
                    // Default to 1/frameRate when ISO is set without explicit exposure time
                    val defaultNs = 1_000_000_000L / settings.frameRate.coerceAtLeast(1)
                    defaultNs.coerceIn(sensorExposureTimeRange.first, sensorExposureTimeRange.last)
                }
            } else {
                null
            }

            val awbMode = when (settings.whiteBalance) {
                WhiteBalance.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                WhiteBalance.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                WhiteBalance.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                WhiteBalance.INDOOR -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                WhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                WhiteBalance.MANUAL -> CaptureRequest.CONTROL_AWB_MODE_OFF
            }

            val afMode = if (settings.focusMode == FocusMode.MANUAL) {
                CaptureRequest.CONTROL_AF_MODE_OFF
            } else {
                null
            }
            val lensFocusDistance = if (afMode != null) settings.focusDistance else null

            val colorTemperatureKelvin =
                if (settings.whiteBalance == WhiteBalance.MANUAL) {
                    settings.colorTemperature?.coerceIn(
                        CameraSettings.COLOR_TEMPERATURE_MIN,
                        CameraSettings.COLOR_TEMPERATURE_MAX,
                    )
                } else {
                    null
                }

            settings.sceneMode?.toIntOrNull()?.let { sceneMode = it }

            return when (settings.nightVisionMode) {
                NightVisionMode.ON -> {
                    sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT
                    val nightFpsLower = 10
                    val nightFpsUpper = settings.frameRate.coerceAtMost(15)
                    CameraControlPlan(
                        fpsLower = nightFpsLower,
                        fpsUpper = nightFpsUpper,
                        videoStabilizationOn = videoStabilizationOn,
                        opticalStabilizationOn = opticalStabilizationOn,
                        sceneMode = sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
                        aeMode = baseAeMode,
                        aeLock = false,
                        sensorSensitivity = iso,
                        sensorExposureTimeNs = exposureTimeNs,
                        awbMode = awbMode,
                        afMode = afMode,
                        lensFocusDistance = lensFocusDistance,
                        colorTemperatureKelvin = colorTemperatureKelvin,
                    )
                }
                NightVisionMode.AUTO -> {
                    sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT
                    val autoAeMode =
                        if (!hasManualExposure) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                        else baseAeMode
                    CameraControlPlan(
                        fpsLower = fpsLower,
                        fpsUpper = fpsUpper,
                        videoStabilizationOn = videoStabilizationOn,
                        opticalStabilizationOn = opticalStabilizationOn,
                        sceneMode = sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT,
                        aeMode = autoAeMode,
                        aeLock = null,
                        sensorSensitivity = iso,
                        sensorExposureTimeNs = exposureTimeNs,
                        awbMode = awbMode,
                        afMode = afMode,
                        lensFocusDistance = lensFocusDistance,
                        colorTemperatureKelvin = colorTemperatureKelvin,
                    )
                }
                NightVisionMode.OFF -> {
                    // Reset scene mode when disabling night vision
                    // (only if not overridden by HDR or manual scene mode)
                    if (settings.hdrMode != HdrMode.ON && settings.sceneMode == null) {
                        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED
                    }
                    val offAeMode =
                        if (!hasManualExposure) CaptureRequest.CONTROL_AE_MODE_ON
                        else baseAeMode
                    CameraControlPlan(
                        fpsLower = fpsLower,
                        fpsUpper = fpsUpper,
                        videoStabilizationOn = videoStabilizationOn,
                        opticalStabilizationOn = opticalStabilizationOn,
                        sceneMode = sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
                        aeMode = offAeMode,
                        aeLock = false,
                        sensorSensitivity = iso,
                        sensorExposureTimeNs = exposureTimeNs,
                        awbMode = awbMode,
                        afMode = afMode,
                        lensFocusDistance = lensFocusDistance,
                        colorTemperatureKelvin = colorTemperatureKelvin,
                    )
                }
            }
        }

        /**
         * The exposure-compensation decision: the persisted index clamped to
         * the device's live range, or null when the device already sits on
         * that index — re-writing the same index is skipped, not re-applied.
         */
        fun exposureIndex(
            settings: CameraSettings,
            rangeLower: Int,
            rangeUpper: Int,
            currentIndex: Int,
        ): Int? {
            val clamped = settings.exposureCompensation.coerceIn(rangeLower, rangeUpper)
            return if (clamped != currentIndex) clamped else null
        }

        /**
         * Which center AF/AE metering a settings apply fires, per focus mode:
         * the continuous modes meter with the auto-cancel window, MANUAL
         * leaves metering alone, everything else meters bare (it holds until
         * replaced).
         */
        fun meteringOnApply(focusMode: FocusMode): MeteringDecision =
            when (focusMode) {
                FocusMode.CONTINUOUS_PICTURE, FocusMode.CONTINUOUS_VIDEO ->
                    MeteringDecision.AutoCancelMetering
                FocusMode.MANUAL -> MeteringDecision.None
                else -> MeteringDecision.PlainMetering
            }

        /**
         * A deliberate tap always meters with the auto-cancel window,
         * whatever the focus mode.
         */
        fun meteringOnTap(): MeteringDecision = MeteringDecision.AutoCancelMetering
    }
}

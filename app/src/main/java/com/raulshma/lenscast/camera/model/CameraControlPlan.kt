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
 *
 * Built in two stages ([from]): what the settings ask for, then what the bound
 * camera advertises ([CaptureSupport]) — an unadvertised value is neutralised
 * here and listed in [unsupported], so the service can submit every control in
 * ONE capture-request bundle without one unsupported key costing the rest.
 */
data class CameraControlPlan(
    val fpsLower: Int,
    val fpsUpper: Int,
    val videoStabilizationOn: Boolean,
    val opticalStabilizationOn: Boolean,
    /** Camera2 scene mode; always resolved (OFF resets to DISABLED). */
    val sceneMode: Int,
    /**
     * Camera2 control mode — the switch that arms the scene mode: aside from
     * FACE_PRIORITY, a scene mode only takes effect while the control mode is
     * USE_SCENE_MODE, so this is always derived from [sceneMode] and the pair
     * can never disagree.
     */
    val controlMode: Int,
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
    /**
     * The controls this camera does not advertise, each neutralised to its
     * camera2 default above — short control names for the apply-time log
     * line. Empty when the device honours every requested value, and always
     * empty under [CaptureSupport.UNRESTRICTED]: an unknown list restricts
     * nothing.
     */
    val unsupported: List<String> = emptyList(),
) {
    /**
     * Folds the device's advertised capabilities onto this — the requested —
     * plan: a value the camera does not advertise is retired to the camera2
     * default every device honours and named in [unsupported], and everything
     * else passes untouched. This is the isolation a one-bundle submit needs
     * (CONTEXT.md, "Capture Options Submit").
     *
     * Two invariants hold on every folded plan. The sensor pair rides only an
     * AE OFF plan: the HAL ignores `SENSOR_*` under automatic exposure, so a
     * manual exposure the AE fallback takes away must not keep travelling.
     * And manual white balance rides only a camera that can apply it.
     */
    private fun limitedTo(support: CaptureSupport): CameraControlPlan {
        val fold = Retired()

        // DISABLED is always valid whether or not the device lists it.
        val supportedSceneMode =
            if (sceneMode == CaptureRequest.CONTROL_SCENE_MODE_DISABLED) {
                sceneMode
            } else {
                fold.mode(support.sceneModes, sceneMode, CaptureRequest.CONTROL_SCENE_MODE_DISABLED, UNSUPPORTED_SCENE_MODE)
            }
        val supportedAeMode = fold.mode(support.aeModes, aeMode, CaptureRequest.CONTROL_AE_MODE_ON, UNSUPPORTED_AE_MODE)

        val manualExposure = supportedAeMode == CaptureRequest.CONTROL_AE_MODE_OFF
        val supportedSensitivity = fold.value(
            sensorSensitivity,
            retired = !manualExposure && sensorSensitivity != null,
            fallback = null,
            name = UNSUPPORTED_MANUAL_EXPOSURE,
        )
        val supportedExposureTimeNs = if (manualExposure) sensorExposureTimeNs else null

        val advertisedAwbMode = fold.mode(support.awbModes, awbMode, CaptureRequest.CONTROL_AWB_MODE_AUTO, UNSUPPORTED_WHITE_BALANCE)
        // Manual white balance is AWB OFF driven by the color-correction
        // gains, and the gains need MANUAL_POST_PROCESSING. Without it AWB
        // OFF is not a manual white balance but white balance off at the
        // default gains, so the request goes back to automatic altogether.
        val supportedAwbMode = fold.value(
            advertisedAwbMode,
            retired = advertisedAwbMode == CaptureRequest.CONTROL_AWB_MODE_OFF && support.manualPostProcessing == false,
            fallback = CaptureRequest.CONTROL_AWB_MODE_AUTO,
            name = UNSUPPORTED_MANUAL_WHITE_BALANCE,
        )
        val supportedKelvin = colorTemperatureKelvin.takeIf { supportedAwbMode == CaptureRequest.CONTROL_AWB_MODE_OFF }

        // Back to cameraX's default continuous AF when AF OFF is not
        // advertised: the lens is the autofocus algorithm's again, so a
        // stored distance must not ride along.
        val supportedAfMode = fold.value(
            afMode,
            retired = afMode != null && !support.afModes.advertises(afMode),
            fallback = null,
            name = UNSUPPORTED_FOCUS,
        )
        val supportedFocusDistance = if (supportedAfMode != null) lensFocusDistance else null

        // Only ON can be unadvertised — camera2 lists OFF on every device —
        // so a retired request still sends OFF: the mode switches off, it is
        // never left dangling on the previous request's value.
        val supportedOpticalStabilization = fold.value(
            opticalStabilizationOn,
            retired = opticalStabilizationOn &&
                !support.opticalStabilizationModes.advertises(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            fallback = false,
            name = UNSUPPORTED_OPTICAL_STABILIZATION,
        )
        val supportedVideoStabilization = fold.value(
            videoStabilizationOn,
            retired = videoStabilizationOn &&
                !support.videoStabilizationModes.advertises(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            fallback = false,
            name = UNSUPPORTED_VIDEO_STABILIZATION,
        )

        return copy(
            videoStabilizationOn = supportedVideoStabilization,
            opticalStabilizationOn = supportedOpticalStabilization,
            sceneMode = supportedSceneMode,
            controlMode = controlModeFor(supportedSceneMode),
            aeMode = supportedAeMode,
            sensorSensitivity = supportedSensitivity,
            sensorExposureTimeNs = supportedExposureTimeNs,
            awbMode = supportedAwbMode,
            afMode = supportedAfMode,
            lensFocusDistance = supportedFocusDistance,
            colorTemperatureKelvin = supportedKelvin,
            unsupported = fold.names,
        )
    }

    /**
     * The capability fold's bookkeeping: every control [limitedTo] retires
     * is named here, once, in fold order.
     */
    private class Retired {
        val names = mutableListOf<String>()

        /** [requested] when [modes] advertises it; otherwise [default], retired under [name]. */
        fun mode(modes: Set<Int>?, requested: Int, default: Int, name: String): Int =
            value(requested, retired = !modes.advertises(requested), fallback = default, name = name)

        /** [requested] kept unless [retired]: then [fallback] takes its place and [name] is reported. */
        fun <T> value(requested: T, retired: Boolean, fallback: T, name: String): T {
            if (!retired) return requested
            names += name
            return fallback
        }
    }

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

        /**
         * The CONTROL_MODE [sceneMode] needs: aside from FACE_PRIORITY — the
         * documented exception that works under AUTO — a scene mode only
         * takes effect while the control mode itself is USE_SCENE_MODE, so
         * submitting CONTROL_SCENE_MODE with the control mode left at AUTO
         * is ignored by every device (issue #25).
         */
        private fun controlModeFor(sceneMode: Int): Int =
            if (sceneMode == CaptureRequest.CONTROL_SCENE_MODE_DISABLED ||
                sceneMode == CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
            ) {
                CaptureRequest.CONTROL_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
            }

        /** The [CameraControlPlan.unsupported] names — one per control the plan can neutralise. */
        const val UNSUPPORTED_SCENE_MODE = "scene mode"
        const val UNSUPPORTED_AE_MODE = "AE mode"
        const val UNSUPPORTED_MANUAL_EXPOSURE = "manual exposure"
        const val UNSUPPORTED_WHITE_BALANCE = "white balance mode"
        const val UNSUPPORTED_MANUAL_WHITE_BALANCE = "manual white balance (Kelvin)"
        const val UNSUPPORTED_FOCUS = "manual focus"
        const val UNSUPPORTED_OPTICAL_STABILIZATION = "optical stabilization"
        const val UNSUPPORTED_VIDEO_STABILIZATION = "video stabilization"

        /**
         * The plan in two stages: what the settings ask for ([requested]),
         * then what this camera can honour ([support] — the advertised
         * capability lists, defaulting to unknown/unrestricted so every
         * caller that does not know them gets the plan as requested).
         */
        fun from(
            settings: CameraSettings,
            isoRange: ClosedRange<Int>,
            sensorExposureTimeRange: LongRange,
            support: CaptureSupport = CaptureSupport.UNRESTRICTED,
        ): CameraControlPlan = requested(settings, isoRange, sensorExposureTimeRange).limitedTo(support)

        /** The plan exactly as the settings and the sensor's ranges ask for it — no capability fold yet. */
        private fun requested(
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
                // The explicit shutter is clamped too: a persisted value may
                // predate this device or exceed a sensor's range, and an
                // out-of-range SENSOR_EXPOSURE_TIME makes the whole capture
                // request invalid (all controls dropped) rather than degrading.
                val requestedNs = settings.exposureTime ?: run {
                    // Default to 1/frameRate when ISO is set without explicit
                    // exposure time.
                    val defaultNs = 1_000_000_000L / settings.frameRate.coerceAtLeast(1)
                    defaultNs.coerceIn(sensorExposureTimeRange.first, sensorExposureTimeRange.last)
                }
                requestedNs.coerceIn(sensorExposureTimeRange.first, sensorExposureTimeRange.last)
            } else {
                null
            }
            // AE OFF needs the full manual pair: a sensitivity out of the
            // device range would also invalidate the request. When only the
            // shutter was chosen, pair it with the range midpoint — a usable
            // default rather than leaving the sensor on its last value.
            val sensorSensitivity = if (hasManualExposure) {
                iso ?: (isoRange.start + (isoRange.endInclusive - isoRange.start) / 2)
            } else {
                null
            }
            // A shutter longer than the frame period is incoherent with the
            // requested fps range; the driver then either ignores the shutter
            // or stretches frames unpredictably. Cap the range at the fastest
            // rate the shutter physically allows.
            val (coherentFpsLower, coherentFpsUpper) =
                coherentFps(fpsLower, fpsUpper, exposureTimeNs)
            fpsLower = coherentFpsLower
            fpsUpper = coherentFpsUpper

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
                    val (nightFpsLower, nightFpsUpper) = coherentFps(
                        lower = 10,
                        upper = settings.frameRate.coerceAtMost(15),
                        exposureTimeNs = exposureTimeNs,
                    )
                    CameraControlPlan(
                        fpsLower = nightFpsLower,
                        fpsUpper = nightFpsUpper,
                        videoStabilizationOn = videoStabilizationOn,
                        opticalStabilizationOn = opticalStabilizationOn,
                        sceneMode = sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
                        controlMode = controlModeFor(sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_NIGHT),
                        aeMode = baseAeMode,
                        aeLock = false,
                        sensorSensitivity = sensorSensitivity,
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
                        controlMode = controlModeFor(sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT),
                        aeMode = autoAeMode,
                        aeLock = null,
                        sensorSensitivity = sensorSensitivity,
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
                        controlMode = controlModeFor(sceneMode ?: CaptureRequest.CONTROL_SCENE_MODE_DISABLED),
                        aeMode = offAeMode,
                        aeLock = false,
                        sensorSensitivity = sensorSensitivity,
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
         * Caps an fps range so a manual shutter fits inside one frame period:
         * a 1/8 s exposure cannot be sustained at 30 fps, so the range drops
         * to the fastest rate that exposure allows (never below 1 fps).
         */
        private fun coherentFps(
            lower: Int,
            upper: Int,
            exposureTimeNs: Long?,
        ): Pair<Int, Int> {
            if (exposureTimeNs == null) return lower to upper
            val fastestFps = (1_000_000_000L / exposureTimeNs.coerceAtLeast(1L))
                .toInt()
                .coerceAtLeast(1)
            val cappedUpper = upper.coerceAtMost(fastestFps)
            val cappedLower = lower.coerceAtMost(cappedUpper).coerceAtLeast(1)
            return cappedLower to cappedUpper
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

/** Whether an advertised list contains [value]; an unknown (null) list advertises everything. */
private fun <T> Set<T>?.advertises(value: T): Boolean = this == null || value in this

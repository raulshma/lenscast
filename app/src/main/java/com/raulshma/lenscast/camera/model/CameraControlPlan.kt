package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest

/**
 * The CaptureRequest *decisions* — fps range, exposure, scene mode, white
 * balance, focus — computed from [CameraSettings] plus the device's live
 * ranges. Pure data + pure builder, so the ISO/fps/night-vision math is
 * unit-testable on the JVM; the service only translates this plan into
 * [android.hardware.camera2.CaptureRequest] options.
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
) {
    companion object {
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
                    )
                }
            }
        }
    }
}

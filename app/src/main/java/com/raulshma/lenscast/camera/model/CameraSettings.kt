package com.raulshma.lenscast.camera.model

import android.util.Size

enum class FocusMode {
    AUTO, MANUAL, MACRO, CONTINUOUS_PICTURE, CONTINUOUS_VIDEO
}

enum class WhiteBalance {
    AUTO, DAYLIGHT, CLOUDY, INDOOR, FLUORESCENT, MANUAL
}

enum class Resolution(val size: Size) {
    SD_480P(Size(720, 480)),
    HD_720P(Size(1280, 720)),
    FHD_1080P(Size(1920, 1080)),
    QHD_1440P(Size(2560, 1440)),
    UHD_4K(Size(3840, 2160))
}

enum class HdrMode { OFF, ON, AUTO }

enum class NightVisionMode { OFF, AUTO, ON }

data class CameraSettings(
    val exposureCompensation: Int = 0,
    val iso: Int? = null,
    val exposureTime: Long? = null,
    val focusMode: FocusMode = FocusMode.AUTO,
    val focusDistance: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val colorTemperature: Int? = null,
    val zoomRatio: Float = 1.0f,
    val frameRate: Int = 24,
    val resolution: Resolution = Resolution.FHD_1080P,
    val sceneMode: String? = null,
    val stabilization: Boolean = true,
    val hdrMode: HdrMode = HdrMode.OFF,
    val nightVisionMode: NightVisionMode = NightVisionMode.OFF,
) {
    companion object {
        // Persistence/validation bounds — the single home, referenced by the
        // store coercion, the Web API DTO mapping, and the UI. The device's
        // live ranges (CameraService range flows) always win at apply time.
        const val EXPOSURE_COMPENSATION_MIN = -12
        const val EXPOSURE_COMPENSATION_MAX = 12
        const val FOCUS_DISTANCE_MAX = 20f
        const val COLOR_TEMPERATURE_MIN = 1000
        const val COLOR_TEMPERATURE_MAX = 15000
        const val ZOOM_RATIO_MIN = 0.1f
        const val ZOOM_RATIO_MAX = 10f
        const val FRAME_RATE_MIN = 1
        const val FRAME_RATE_MAX = 120

        // The slider span offered in the UI — narrower than the persistence
        // bounds above, matching what typical sensors support.
        const val FRAME_RATE_SLIDER_MIN = 15
        const val FRAME_RATE_SLIDER_MAX = 60
    }
}

/**
 * The power-of-two ISO stops available within [isoRange], headed by "Auto".
 * Shared by the camera quick-settings sheet and the settings screen.
 */
fun isoStops(isoRange: ClosedRange<Int>): List<String> {
    val stops = mutableListOf("Auto")
    var value = 100
    while (value <= isoRange.endInclusive) {
        if (value >= isoRange.start) stops.add(value.toString())
        value *= 2
    }
    return stops
}

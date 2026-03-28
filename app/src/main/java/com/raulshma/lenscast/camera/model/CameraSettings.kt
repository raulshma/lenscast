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

data class CameraSettings(
    val exposureCompensation: Int = 0,
    val iso: Int? = null,
    val exposureTime: Long? = null,
    val focusMode: FocusMode = FocusMode.AUTO,
    val focusDistance: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val colorTemperature: Int? = null,
    val zoomRatio: Float = 1.0f,
    val frameRate: Int = 30,
    val resolution: Resolution = Resolution.FHD_1080P,
    val sceneMode: String? = null,
    val stabilization: Boolean = true,
    val hdrMode: HdrMode = HdrMode.OFF,
)

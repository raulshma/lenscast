package com.raulshma.lenscast.camera.model

import java.util.UUID

enum class OverlayPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

enum class MaskingType {
    BLACKOUT,
    PIXELATE,
    BLUR
}

data class MaskingZone(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val enabled: Boolean = true,
    val type: MaskingType = MaskingType.BLACKOUT,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0.2f,
    val height: Float = 0.2f,
    val pixelateSize: Int = 16,
    val blurRadius: Float = 10f,
)

data class OverlaySettings(
    val enabled: Boolean = false,
    val showTimestamp: Boolean = true,
    val timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
    val showBranding: Boolean = false,
    val brandingText: String = "LensCast",
    val showStatus: Boolean = false,
    val customText: String = "",
    val showCustomText: Boolean = false,
    val position: OverlayPosition = OverlayPosition.TOP_LEFT,
    val fontSize: Int = 28,
    val textColor: String = "#FFFFFF",
    val backgroundColor: String = "#80000000",
    val padding: Int = 8,
    val lineHeight: Int = 4,
    val maskingEnabled: Boolean = false,
    val maskingZones: List<MaskingZone> = emptyList(),
)

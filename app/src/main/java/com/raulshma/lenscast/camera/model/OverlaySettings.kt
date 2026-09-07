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
) {
    companion object {
        /** Single source for the fallback values used when parsing persisted or wire data. */
        val DEFAULT = MaskingZone()

        // Wire/persistence clamp bounds — the one place they are written down.
        const val COORD_MIN = 0f
        const val COORD_MAX = 1f
        const val SIZE_MIN = 0.01f
        const val SIZE_MAX = 1f
        const val PIXELATE_SIZE_MIN = 4
        const val PIXELATE_SIZE_MAX = 64
        const val BLUR_RADIUS_MIN = 1f
        const val BLUR_RADIUS_MAX = 50f

        /**
         * Pure clamp policy for a zone coming from persisted or wire data:
         * normalized coordinates coerce to 0..1, zone size to 0.01..1,
         * pixelate size to 4..64 and blur radius to 1..50. Identity and
         * metadata (id/label/enabled/type) pass through untouched.
         */
        fun normalized(zone: MaskingZone): MaskingZone = zone.copy(
            x = zone.x.coerceIn(COORD_MIN, COORD_MAX),
            y = zone.y.coerceIn(COORD_MIN, COORD_MAX),
            width = zone.width.coerceIn(SIZE_MIN, SIZE_MAX),
            height = zone.height.coerceIn(SIZE_MIN, SIZE_MAX),
            pixelateSize = zone.pixelateSize.coerceIn(PIXELATE_SIZE_MIN, PIXELATE_SIZE_MAX),
            blurRadius = zone.blurRadius.coerceIn(BLUR_RADIUS_MIN, BLUR_RADIUS_MAX),
        )
    }
}

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
) {
    companion object {
        /** Single source for the fallback values used when parsing persisted or wire data. */
        val DEFAULT = OverlaySettings()

        // Wire/persistence clamp bounds — the one place they are written down.
        const val FONT_SIZE_MIN = 8
        const val FONT_SIZE_MAX = 120
        const val PADDING_MIN = 0
        const val PADDING_MAX = 48
        const val LINE_HEIGHT_MIN = 0
        const val LINE_HEIGHT_MAX = 32

        /**
         * Pure clamp policy for settings coming from persisted or wire data:
         * font size coerces to 8..120, padding to 0..48 and line height to
         * 0..32; every masking zone clamps through [MaskingZone.normalized].
         * Everything else (flags, text, colors, position) passes through.
         */
        fun normalized(candidate: OverlaySettings): OverlaySettings = candidate.copy(
            fontSize = candidate.fontSize.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX),
            padding = candidate.padding.coerceIn(PADDING_MIN, PADDING_MAX),
            lineHeight = candidate.lineHeight.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX),
            maskingZones = candidate.maskingZones.map { MaskingZone.normalized(it) },
        )
    }
}

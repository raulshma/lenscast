package com.raulshma.lenscast.camera.model

import java.util.UUID

/**
 * A motion detection zone: the inclusive counterpart of a [MaskingZone].
 * When at least one zone exists, only motion inside the zones counts;
 * with no zones the whole frame is the detection area.
 */
data class MotionZone(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val enabled: Boolean = true,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0.25f,
    val height: Float = 0.25f,
) {
    companion object {
        /** Single source for the fallback values used when parsing persisted or wire data. */
        val DEFAULT = MotionZone()

        // Wire/persistence clamp bounds — the one place they are written down.
        const val COORD_MIN = 0f
        const val COORD_MAX = 1f
        const val SIZE_MIN = 0.01f
        const val SIZE_MAX = 1f

        /**
         * Pure clamp policy for a zone coming from persisted or wire data:
         * normalized coordinates coerce to 0..1 and zone size to 0.01..1.
         * Identity and metadata (id/label/enabled) pass through untouched.
         */
        fun normalized(zone: MotionZone): MotionZone = zone.copy(
            x = zone.x.coerceIn(COORD_MIN, COORD_MAX),
            y = zone.y.coerceIn(COORD_MIN, COORD_MAX),
            width = zone.width.coerceIn(SIZE_MIN, SIZE_MAX),
            height = zone.height.coerceIn(SIZE_MIN, SIZE_MAX),
        )

        /**
         * Pure overlap verdict: does the normalized frame-space rect of this
         * zone intersect the sampling box? The detector samples tiles; a zone
         * arms motion when any sampled tile inside it breaches the threshold.
         */
        fun overlapsSample(zone: MotionZone, sampleX: Float, sampleY: Float, sampleW: Float, sampleH: Float): Boolean {
            val zoneRight = zone.x + zone.width
            val zoneBottom = zone.y + zone.height
            val sampleRight = sampleX + sampleW
            val sampleBottom = sampleY + sampleH
            return zone.x < sampleRight && zoneRight > sampleX &&
                zone.y < sampleBottom && zoneBottom > sampleY
        }
    }
}

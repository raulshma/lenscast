package com.raulshma.lenscast.camera.model

import kotlin.math.absoluteValue

/**
 * The preview's gesture math, pure: whether a pinch scale moves the zoom
 * past its deadband (and the clamped ratio if so), whether a transform
 * gesture was really a tap, and how long the zoom indicator lingers. The
 * composable only translates events into these calls.
 */
object PreviewGestures {

    /** Smallest zoom-ratio change a pinch must produce to count as a move. */
    const val SCALE_DEADBAND = 0.01f

    /** How long the zoom indicator stays on screen after the last pinch. */
    const val INDICATOR_HIDE_DELAY_MS = 800L

    /** Max finger travel (px) a gesture may show and still count as a tap. */
    const val TAP_MAX_PAN_PX = 8f

    /**
     * The new zoom ratio for a pinch [scale] event, or null when the gesture
     * is below the deadband (identity scale or a clamped no-op) and must not
     * update settings — every non-null result would otherwise re-fire the
     * settings apply path.
     */
    fun onScale(
        currentRatio: Float,
        scale: Float,
        range: ClosedFloatingPointRange<Float>,
    ): Float? {
        if (scale == 1f) return null
        val newRatio = (currentRatio * scale).coerceIn(range.start, range.endInclusive)
        if ((newRatio - currentRatio).absoluteValue <= SCALE_DEADBAND) return null
        return newRatio
    }

    /** A transform gesture with identity zoom and almost no pan is a tap. */
    fun isTap(scale: Float, panDistancePx: Float): Boolean =
        scale == 1f && panDistancePx <= TAP_MAX_PAN_PX
}

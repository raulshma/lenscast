package com.raulshma.lenscast.gallery

/**
 * The media viewer's zoom/pan policy, pure: double-tap verdict, pinch scale
 * clamps, and pan bounds — the [com.raulshma.lenscast.camera.model.PreviewGestures]
 * twin for the gallery. The composable only normalizes the gesture and
 * viewport coordinates into these calls; thresholds and clamps live here as
 * named constants so the viewer and any future surface share one policy.
 */
object ViewerZoomPolicy {

    /** The scale a double-tap zooms in to. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Above this scale a double-tap resets to identity instead of zooming in. */
    const val DOUBLE_TAP_RESET_THRESHOLD = 1.2f

    /** Pinch scale bounds: never smaller than the image's fit, at most 5x. */
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f

    /** The transform a gesture leaves the image in. */
    data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    /**
     * Double-tap: zoom in to [DOUBLE_TAP_SCALE] from the fit scale, reset to
     * identity (pan included) once zoomed past [DOUBLE_TAP_RESET_THRESHOLD].
     */
    fun onDoubleTap(currentScale: Float): Transform =
        if (currentScale > DOUBLE_TAP_RESET_THRESHOLD) {
            Transform(MIN_SCALE, 0f, 0f)
        } else {
            Transform(DOUBLE_TAP_SCALE, 0f, 0f)
        }

    /**
     * A pinch/pan event: the scale multiplies and clamps to
     * [MIN_SCALE]..[MAX_SCALE]; the pan accumulates and clamps to half the
     * scaled overflow per axis (so the image can never be dragged fully off
     * screen); at the fit scale the pan is pinned to center.
     */
    fun onTransform(
        currentScale: Float,
        currentOffsetX: Float,
        currentOffsetY: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Transform {
        val updatedScale = (currentScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val maxX = ((viewportWidth * (updatedScale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((viewportHeight * (updatedScale - 1f)) / 2f).coerceAtLeast(0f)
        return Transform(
            scale = updatedScale,
            offsetX = if (updatedScale == 1f) 0f else (currentOffsetX + panX).coerceIn(-maxX, maxX),
            offsetY = if (updatedScale == 1f) 0f else (currentOffsetY + panY).coerceIn(-maxY, maxY),
        )
    }
}

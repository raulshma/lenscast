package com.raulshma.lenscast.camera.model

/**
 * The pinch-zoom indicator's two-phase machine — one truth for the overlay
 * and its retire timer: [Hidden] / Visible(ratio).
 *
 * The gesture layer writes [onPinch] per zoom event, and the screen's retire
 * deadline writes [onRetire] ([PreviewGestures.INDICATOR_HIDE_DELAY_MS]
 * after the last event — the whole state is the LaunchedEffect key, so every
 * pinch write re-arms the deadline and the fire lands once the fingers
 * settle). The retired flag+float pair had no runnable exit transition: the
 * hide timer keyed on the flag it was also the only `false` writer of, so
 * the pill stayed over the viewfinder until the screen left composition.
 * The machine exists so the overlay, the timer, and the gesture writes all
 * read and write one state instead of two primitives whose pairing was the
 * contract.
 */
data class ZoomIndicatorState(
    val isVisible: Boolean,
    val ratio: Float,
) {

    /**
     * A zoom event passed the deadband: show at [newRatio]. Every write is a
     * new state value, which is what re-arms the screen's retire deadline.
     */
    fun onPinch(newRatio: Float): ZoomIndicatorState = copy(isVisible = true, ratio = newRatio)

    /**
     * The retire deadline fired: hide. The ratio is kept — it is unread
     * while hidden, and the next pinch overwrites it with the live value.
     */
    fun onRetire(): ZoomIndicatorState = copy(isVisible = false)

    companion object {

        /** The screen's initial state: no pinch has happened yet. */
        val Hidden = ZoomIndicatorState(isVisible = false, ratio = 1f)
    }
}

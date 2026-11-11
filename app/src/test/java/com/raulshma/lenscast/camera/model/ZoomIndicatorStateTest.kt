package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [ZoomIndicatorState] transitions — the overlay's show/hide machine.
 * The exit transition is the part with history: the retired flag+float pair
 * could never run it (the hide timer keyed on the flag it was the only
 * `false` writer of), so these tests pin that retire always terminates.
 */
class ZoomIndicatorStateTest {

    // ---- Hidden: the initial state --------------------------------------------------

    @Test
    fun `hidden is not visible at the default ratio`() {
        val hidden = ZoomIndicatorState.Hidden
        assertFalse(hidden.isVisible)
        assertEquals(1f, hidden.ratio)
    }

    // ---- onPinch: the zoom-event write ----------------------------------------------

    @Test
    fun `a pinch event shows the indicator at the event's ratio`() {
        val shown = ZoomIndicatorState.Hidden.onPinch(2.5f)
        assertTrue(shown.isVisible)
        assertEquals(2.5f, shown.ratio)
    }

    @Test
    fun `each pinch write is a new state so the retire deadline re-arms`() {
        // The screen keys its LaunchedEffect on the whole state: two pinch
        // events at different ratios must produce different values, or the
        // hide timer would run on the first event's deadline alone.
        assertNotEquals(ZoomIndicatorState.Hidden.onPinch(2f), ZoomIndicatorState.Hidden.onPinch(3f))
    }

    @Test
    fun `pinching while already visible just moves the ratio`() {
        val moved = ZoomIndicatorState.Hidden.onPinch(2f).onPinch(4f)
        assertTrue(moved.isVisible)
        assertEquals(4f, moved.ratio)
    }

    // ---- onRetire: the deadline's write ---------------------------------------------

    @Test
    fun `retire hides a visible indicator`() {
        val retired = ZoomIndicatorState.Hidden.onPinch(3f).onRetire()
        assertFalse(retired.isVisible)
    }

    @Test
    fun `retire while hidden stays hidden`() {
        assertEquals(ZoomIndicatorState.Hidden, ZoomIndicatorState.Hidden.onRetire())
    }

    @Test
    fun `the machine always terminates - retire inverts any pinch run`() {
        // The dead-exit regression guard: however many pinch events ran, one
        // retire write returns to a non-visible state.
        var state = ZoomIndicatorState.Hidden
        repeat(10) { index -> state = state.onPinch(1f + index) }
        assertFalse(state.onRetire().isVisible)
    }
}

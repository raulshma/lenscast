package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the post-bind controls-apply ladder: the first rung keeps the
 * empirically verified 400 ms settle window (DRG / Nokia 6.1 Plus), the
 * bounded second rung re-asserts past slower HAL settle windows, and the
 * budget reads are consistent with the rung list.
 */
class ControlsApplyPolicyTest {

    @Test
    fun `first rung keeps the empirically verified settle window`() {
        assertEquals(400L, ControlsApplyPolicy.applyDelaysMs().first())
    }

    @Test
    fun `ladder is bounded, ordered, and strictly increasing`() {
        val rungs = ControlsApplyPolicy.applyDelaysMs()
        assertTrue(rungs.size in 2..3)
        assertEquals(rungs, rungs.sorted())
        assertEquals(rungs.distinct(), rungs)
    }

    @Test
    fun `settle budget is the last rung`() {
        assertEquals(ControlsApplyPolicy.applyDelaysMs().last(), ControlsApplyPolicy.settleBudgetMs())
    }
}

package com.raulshma.lenscast.capture.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's init latch as pure state: only [DetectorInitGate.onInitFailure]
 * closes the gate, and it never reopens — which is exactly what keeps a
 * missing model file (the engine's no-latch path) from ever disabling the ML
 * gate before a late download.
 */
class DetectorInitGateTest {

    @Test
    fun `a fresh gate allows attempts`() {
        assertTrue(DetectorInitGate().canAttempt)
    }

    @Test
    fun `an init failure latches the gate for good`() {
        val gate = DetectorInitGate()
        gate.onInitFailure()

        assertFalse(gate.canAttempt)
    }
}

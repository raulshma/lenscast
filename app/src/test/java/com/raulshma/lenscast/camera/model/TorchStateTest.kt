package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [TorchState.from] mapping — the one live-torch-verdict + driven-strength
 * → state ladder the service publishes through. Each arm is named in the test.
 */
class TorchStateTest {

    // ---- Off -----------------------------------------------------------------------

    @Test
    fun `live torch off is Off regardless of any driven strength`() {
        assertEquals(TorchState.Off, TorchState.from(liveTorchOn = false, drivenStrengthLevel = null))
        // A stale driven level from a previous torch-on epoch must not light
        // the published fact while the flash is dark.
        assertEquals(TorchState.Off, TorchState.from(liveTorchOn = false, drivenStrengthLevel = 4))
    }

    // ---- On(null): device default ---------------------------------------------------

    @Test
    fun `live torch on with no driven level is On at device default`() {
        assertEquals(TorchState.On(null), TorchState.from(liveTorchOn = true, drivenStrengthLevel = null))
    }

    // ---- On(level): the driven verdict ----------------------------------------------

    @Test
    fun `live torch on with a driven level carries that level`() {
        assertEquals(TorchState.On(3), TorchState.from(liveTorchOn = true, drivenStrengthLevel = 3))
    }

    @Test
    fun `driven level of 1 is a real level, not a null default`() {
        // The strength floor is 1, so 1 must publish as On(1) — the torch is
        // lit at a driven level, not "at default".
        assertEquals(TorchState.On(1), TorchState.from(liveTorchOn = true, drivenStrengthLevel = 1))
    }

    // ---- the machine's shape --------------------------------------------------------

    @Test
    fun `On is distinct from Off as an is-check the toggle derives from`() {
        // isTorchOn() derives from the published flow with `is On` — the
        // payload must not participate in the on/off verdict.
        assertTrue(TorchState.from(true, null) is TorchState.On)
        assertTrue(TorchState.from(true, 2) is TorchState.On)
    }
}

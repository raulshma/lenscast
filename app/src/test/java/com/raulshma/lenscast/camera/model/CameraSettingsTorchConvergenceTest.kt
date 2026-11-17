package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared torch-convergence contract every wholesale [CameraSettings]
 * writer answers to (issue #16): untouched torch converges to the live
 * fact, an explicit torch write passes through verbatim — INCLUDING a
 * same-value write, which the editor's value-diff proxy cannot express and
 * which is exactly why the automation executor detects the touch by action
 * type: a Day rule's torch-off action while stored is already off must
 * still assert off against a live-only lit LED.
 */
class CameraSettingsTorchConvergenceTest {

    @Test
    fun `an untouched torch converges to the live fact`() {
        val base = CameraSettings(torchEnabled = false, torchStrength = 3)
        val written = base.copy(iso = 800)

        val converged = CameraSettings.convergeTorch(written, touchedTorch = false, liveTorchOn = true)

        assertTrue(converged.torchEnabled)
        // Brightness intent rides untouched.
        assertEquals(3, converged.torchStrength)
        assertEquals(800, converged.iso)
    }

    @Test
    fun `an explicit torch write passes through verbatim`() {
        val base = CameraSettings(torchEnabled = false)
        // Same value as the base: a value-diff proxy would converge this;
        // the explicit-touch verdict must not.
        val written = base.copy(torchEnabled = false)

        val converged = CameraSettings.convergeTorch(written, touchedTorch = true, liveTorchOn = true)

        assertEquals(false, converged.torchEnabled)
    }
}

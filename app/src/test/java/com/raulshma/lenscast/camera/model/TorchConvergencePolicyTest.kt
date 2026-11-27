package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchConvergencePolicyTest {

    @Test
    fun `cold start commits stored intent verbatim and clears live override`() {
        val requested = CameraSettings(torchEnabled = true, torchStrength = 4)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = false),
            settingsEverApplied = false,
            torchIntent = null,
            hasBoundCamera = false,
            liveTorchOn = false,
            liveTorchOverride = false,
            torchSettlePending = false,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested, result.effective)
        assertNull(result.persistTorchFact)
        assertTrue(result.clearLiveTorchOverride)
    }

    @Test
    fun `explicit torch intent commits verbatim even when pair matches snapshot`() {
        val requested = CameraSettings(torchEnabled = true)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = true),
            settingsEverApplied = true,
            torchIntent = true,
            hasBoundCamera = true,
            liveTorchOn = false,
            liveTorchOverride = false,
            torchSettlePending = false,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested, result.effective)
        assertNull(result.persistTorchFact)
        assertTrue(result.clearLiveTorchOverride)
    }

    @Test
    fun `moved torch pair passes through verbatim and clears live override`() {
        val requested = CameraSettings(torchEnabled = true, torchStrength = 2)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = false, torchStrength = 2),
            settingsEverApplied = true,
            torchIntent = null,
            hasBoundCamera = true,
            liveTorchOn = false,
            liveTorchOverride = false,
            torchSettlePending = false,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested, result.effective)
        assertNull(result.persistTorchFact)
        assertTrue(result.clearLiveTorchOverride)
    }

    @Test
    fun `untouched write preserves live override even while bind is settling dark`() {
        val requested = CameraSettings(torchEnabled = false, iso = 400)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = false, iso = 100),
            settingsEverApplied = true,
            torchIntent = null,
            hasBoundCamera = true,
            liveTorchOn = false,
            liveTorchOverride = true,
            torchSettlePending = true,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested.copy(torchEnabled = true), result.effective)
        assertEquals(true, result.persistTorchFact)
        assertFalse(result.clearLiveTorchOverride)
    }

    @Test
    fun `untouched write keeps persisted torch intent when no camera is bound`() {
        val requested = CameraSettings(torchEnabled = true, iso = 400)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = true, iso = 100),
            settingsEverApplied = true,
            torchIntent = null,
            hasBoundCamera = false,
            liveTorchOn = false,
            liveTorchOverride = null,
            torchSettlePending = false,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested, result.effective)
        assertNull(result.persistTorchFact)
        assertFalse(result.clearLiveTorchOverride)
    }

    @Test
    fun `untouched write keeps persisted torch intent during bind settle window`() {
        val requested = CameraSettings(torchEnabled = true, zoomRatio = 2.0f)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = true, zoomRatio = 1.0f),
            settingsEverApplied = true,
            torchIntent = null,
            hasBoundCamera = true,
            liveTorchOn = false,
            liveTorchOverride = null,
            torchSettlePending = true,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested, result.effective)
        assertNull(result.persistTorchFact)
    }

    @Test
    fun `untouched write honors pending hardware torch target before liveData catches up`() {
        val requested = CameraSettings(torchEnabled = true, zoomRatio = 2.0f)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = true, zoomRatio = 1.0f),
            settingsEverApplied = true,
            torchIntent = null,
            hasBoundCamera = true,
            liveTorchOn = false,
            liveTorchOverride = null,
            torchSettlePending = false,
            pendingHardwareTorchTarget = true,
        )

        assertEquals(requested, result.effective)
        assertNull(result.persistTorchFact)
    }

    @Test
    fun `untouched write converges to live torch fact once bound and settled`() {
        val requested = CameraSettings(torchEnabled = true, zoomRatio = 2.0f)
        val result = TorchConvergencePolicy.resolveApply(
            settings = requested,
            boundSettings = CameraSettings(torchEnabled = true, zoomRatio = 1.0f),
            settingsEverApplied = true,
            torchIntent = null,
            hasBoundCamera = true,
            liveTorchOn = false,
            liveTorchOverride = null,
            torchSettlePending = false,
            pendingHardwareTorchTarget = null,
        )

        assertEquals(requested.copy(torchEnabled = false), result.effective)
        assertEquals(false, result.persistTorchFact)
    }

    @Test
    fun `resolveControlTarget overrides toggle and drops strength when live override is set`() {
        val settings = CameraSettings(torchEnabled = false, torchStrength = 5)

        assertEquals(
            TorchConvergencePolicy.ControlTarget(enabled = true, strength = null),
            TorchConvergencePolicy.resolveControlTarget(settings, liveTorchOverride = true),
        )
        assertEquals(
            TorchConvergencePolicy.ControlTarget(enabled = false, strength = 5),
            TorchConvergencePolicy.resolveControlTarget(settings, liveTorchOverride = null),
        )
    }
}

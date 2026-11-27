package com.raulshma.lenscast.camera.model

/**
 * Pure torch-convergence and control-target decisions for
 * [com.raulshma.lenscast.camera.CameraService].
 *
 * Separating these rules from CameraX/Handler plumbing keeps the verdict
 * JVM-testable and prevents three classes of random on/off torch bugs:
 * 1. Live-only torch flips (`setTorchEnabled` via Web UI, deterrence, or
 *    `SET_TORCH`) diverge from persisted [CameraSettings.torchEnabled];
 *    rebinds (stream start/stop, recovery, lens/resolution/FPS changes) must
 *    preserve [liveTorchOverride] rather than snapping back to stale persisted
 *    settings.
 * 2. During a bind settle window ([torchSettlePending]) or an in-flight
 *    hardware torch transition ([pendingHardwareTorchTarget]), CameraX's
 *    `torchState` LiveData temporarily reports `OFF` after `unbindAll()`;
 *    an unrelated settings apply landing in that window must not "converge"
 *    `torchEnabled` to `false` and wipe the persisted flag.
 * 3. When no camera is bound (`!hasBoundCamera`), `TorchState.Off` reflects
 *    an unbound session rather than a live turn-off, so unrelated settings
 *    writes must leave the persisted intent untouched.
 */
object TorchConvergencePolicy {

    /**
     * Outcome of evaluating an incoming wholesale [CameraSettings] apply
     * against the previously applied settings and current session torch state.
     */
    data class ApplyResolution(
        val effective: CameraSettings,
        val persistTorchFact: Boolean?,
        val clearLiveTorchOverride: Boolean,
    )

    /**
     * Effective torch toggle and strength level for `applyCameraControls`.
     * Live-only overrides drive the device-default brightness (`strength = null`)
     * so they never borrow an unrelated persisted strength level on rebind.
     */
    data class ControlTarget(
        val enabled: Boolean,
        val strength: Int?,
    )

    fun resolveApply(
        settings: CameraSettings,
        boundSettings: CameraSettings,
        settingsEverApplied: Boolean,
        torchIntent: Boolean?,
        hasBoundCamera: Boolean,
        liveTorchOn: Boolean,
        liveTorchOverride: Boolean?,
        torchSettlePending: Boolean,
        pendingHardwareTorchTarget: Boolean?,
    ): ApplyResolution {
        val torchTouched = !settingsEverApplied ||
            torchIntent != null ||
            boundSettings.torchEnabled != settings.torchEnabled ||
            boundSettings.torchStrength != settings.torchStrength
        if (torchTouched) {
            return ApplyResolution(
                effective = settings,
                persistTorchFact = null,
                clearLiveTorchOverride = true,
            )
        }

        val convergedEnabled = when {
            liveTorchOverride != null -> liveTorchOverride
            !hasBoundCamera -> settings.torchEnabled
            torchSettlePending -> settings.torchEnabled
            pendingHardwareTorchTarget != null -> pendingHardwareTorchTarget
            else -> liveTorchOn
        }
        val effective = if (convergedEnabled == settings.torchEnabled) {
            settings
        } else {
            settings.copy(torchEnabled = convergedEnabled)
        }
        val persistFact = if (effective != settings) convergedEnabled else null
        return ApplyResolution(
            effective = effective,
            persistTorchFact = persistFact,
            clearLiveTorchOverride = false,
        )
    }

    fun resolveControlTarget(
        settings: CameraSettings,
        liveTorchOverride: Boolean?,
    ): ControlTarget = if (liveTorchOverride != null) {
        ControlTarget(enabled = liveTorchOverride, strength = null)
    } else {
        ControlTarget(enabled = settings.torchEnabled, strength = settings.torchStrength)
    }
}

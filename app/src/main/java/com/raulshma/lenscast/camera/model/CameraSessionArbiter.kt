package com.raulshma.lenscast.camera.model

/**
 * The pure demand/lifecycle arbiter for the camera session: one verdict per
 * service entry point, replacing the gate heap that used to be hand-assembled
 * at five call sites from different subsets of the service's mutable demand
 * fields — plus the gates that bypassed [ResolutionApplyPolicy] entirely
 * (the rebind path's exclusivity pre-check, stopPreview's exclusive-session
 * early return). The service mutates its fields as before, builds one
 * [CameraDemandState], consults [decide] once, and executes the returned
 * [CameraSessionAction] against CameraX — so the precedence rules that lived
 * only in call order (an exclusive session beats a preview rebind, resume
 * applies the deferred resolution, the headless switch only fires when free)
 * are JVM-testable data-in/data-out. Release is not arbitrated: its teardown
 * is device-bound bookkeeping with no gate to consult, so the service
 * executes it directly.
 *
 * The freedom and rebind-now verdicts stay folded through
 * [ResolutionApplyPolicy] — [decide] calls it, it is not absorbed — so its
 * own decision matrix keeps holding.
 */
object CameraSessionArbiter {

    /** Which service entry point is consulting — the tables differ per entry. */
    enum class Trigger {
        /**
         * The service's shared binding ladder (the ungated rebind entry, and
         * stopPreview's post-stop state): skip while an exclusive session
         * holds the camera, unbind when no demand remains, rebind otherwise.
         */
        RebindCheck,

        /**
         * The opportunistic gates — lens select, headless camera switch,
         * auto-recovery, and a resolution change landing (a settings apply
         * with a new size, the pending-resolution flush): rebind only while
         * the camera is free, never unbind. The caller logs the skip, or
         * parks a deferred resolution.
         */
        RebindIfFree,

        /**
         * The resume hook. PRESERVED asymmetry: the demand input here is
         * previewRequested alone — not the full preview-or-keep-alive demand
         * every other site uses — so a keep-alive-only session is not
         * restored on resume. Deliberately carried over from the old inline
         * gate; do not "fix" it without pinning the new behavior.
         */
        ActivityResumed,

        /**
         * The stop hook: detach the preview surface when one is attached.
         * Detach only — the view reference survives (unlike stopPreview,
         * which clears it), matching the old choreography.
         */
        ActivityStopping,
    }

    /**
     * One immutable read of the service's demand/lifecycle heap — the subset
     * of its mutable fields the verdicts consult. [trigger] names the entry
     * point asking; everything else is plain data.
     */
    data class CameraDemandState(
        val previewRequested: Boolean,
        val keepAliveRefCount: Int,
        val exclusiveSessionRefCount: Int,
        /**
         * Part of the heap the resume/stop choreography flips; the bind-time
         * attach gate (preview requested + surface attached + foreground) is
         * consumed at execution, not by these verdicts.
         */
        val activityForeground: Boolean,
        val previewSurfaceAttached: Boolean,
        /** Read only by [Trigger.ActivityResumed]; a resolution landing is inherently a change. */
        val resolutionChangePending: Boolean,
        val trigger: Trigger,
    ) {
        /** The service's old `hasActiveCameraDemand()`: preview or headless streaming. */
        val demandActive: Boolean get() = previewRequested || keepAliveRefCount > 0

        val exclusiveSessionActive: Boolean get() = exclusiveSessionRefCount > 0

        /** The freedom predicate — [ResolutionApplyPolicy.isCameraFree]'s, not a re-derivation. */
        val cameraFree: Boolean
            get() = ResolutionApplyPolicy.isCameraFree(demandActive, exclusiveSessionActive)
    }

    /** What the service executes against CameraX after one consult. */
    sealed interface CameraSessionAction {
        /** Detach the preview surface only; the binding and the view reference stay. */
        data object DetachSurface : CameraSessionAction

        /**
         * Flush the parked resolution into the current one, then rebind — the
         * flush consults its own freedom ([Trigger.RebindIfFree]) before
         * that rebind, exactly as the old resume → flush chain did.
         */
        data object ApplyPendingResolution : CameraSessionAction

        /** Nothing — the gate that produced no camera work. */
        data object Noop : CameraSessionAction
    }

    /**
     * The binding half of the vocabulary — the verdicts [executeBinding]
     * (in the service) runs against CameraX after the provider check.
     */
    sealed interface BindingAction : CameraSessionAction {
        /** Bind the standard use-case set into the effective lifecycle owner. */
        data object Rebind : BindingAction

        /** `unbindAll` and drop the bound use cases — no demand remains. */
        data object Unbind : BindingAction
    }

    /** The one verdict: the entry point's action over this state. */
    fun decide(state: CameraDemandState): CameraSessionAction = when (state.trigger) {
        Trigger.RebindCheck -> when {
            state.exclusiveSessionActive -> CameraSessionAction.Noop
            !state.demandActive -> BindingAction.Unbind
            else -> BindingAction.Rebind
        }

        // The opportunistic gate (including a resolution change landing):
        // free → rebind, otherwise leave the camera alone (the caller logs,
        // or parks the change).
        Trigger.RebindIfFree -> if (state.cameraFree) BindingAction.Rebind else CameraSessionAction.Noop

        Trigger.ActivityResumed -> when (
            val decision = ResolutionApplyPolicy.decide(
                // PRESERVED asymmetry: previewRequested alone, not
                // demandActive — resume does not restore a keep-alive-only
                // session. Named here so removing it is a deliberate change.
                demandActive = state.previewRequested,
                exclusiveActive = state.exclusiveSessionActive,
                resolutionChanged = state.resolutionChangePending,
            )
        ) {
            is ResolutionApplyPolicy.ResolutionDecision.RebindNow ->
                if (decision.withResolutionChange) CameraSessionAction.ApplyPendingResolution
                else BindingAction.Rebind
            ResolutionApplyPolicy.ResolutionDecision.Defer -> CameraSessionAction.Noop
        }

        Trigger.ActivityStopping ->
            if (state.previewSurfaceAttached) CameraSessionAction.DetachSurface
            else CameraSessionAction.Noop
    }
}

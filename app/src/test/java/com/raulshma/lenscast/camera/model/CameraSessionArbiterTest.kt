package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.camera.model.CameraSessionArbiter.CameraDemandState
import com.raulshma.lenscast.camera.model.CameraSessionArbiter.BindingAction
import com.raulshma.lenscast.camera.model.CameraSessionArbiter.CameraSessionAction
import com.raulshma.lenscast.camera.model.CameraSessionArbiter.Trigger
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSessionArbiterTest {

    private fun decide(
        trigger: Trigger,
        previewRequested: Boolean = false,
        keepAliveRefCount: Int = 0,
        exclusiveSessionRefCount: Int = 0,
        activityForeground: Boolean = true,
        previewSurfaceAttached: Boolean = false,
        resolutionChangePending: Boolean = false,
    ) = CameraSessionArbiter.decide(
        CameraDemandState(
            previewRequested = previewRequested,
            keepAliveRefCount = keepAliveRefCount,
            exclusiveSessionRefCount = exclusiveSessionRefCount,
            activityForeground = activityForeground,
            previewSurfaceAttached = previewSurfaceAttached,
            resolutionChangePending = resolutionChangePending,
            trigger = trigger,
        ),
    )

    // ── the shared binding ladder (the rebind entry / stopPreview's post-stop state) ──

    @Test
    fun `an active preview demand rebinds`() {
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindCheck, previewRequested = true),
        )
    }

    @Test
    fun `an exclusive session blocks the rebind even with an active demand`() {
        assertEquals(
            CameraSessionAction.Noop,
            decide(Trigger.RebindCheck, previewRequested = true, exclusiveSessionRefCount = 1),
        )
        // Higher ref counts gate identically — the verdict reads activity, not magnitude.
        assertEquals(
            CameraSessionAction.Noop,
            decide(Trigger.RebindCheck, previewRequested = true, exclusiveSessionRefCount = 3),
        )
    }

    @Test
    fun `no demand unbinds`() {
        assertEquals(BindingAction.Unbind, decide(Trigger.RebindCheck))
    }

    @Test
    fun `keep-alive alone is demand - stopPreview during headless streaming rebinds`() {
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindCheck, keepAliveRefCount = 1),
        )
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindCheck, keepAliveRefCount = 2),
        )
    }

    @Test
    fun `stopPreview during an exclusive session only detaches - no rebind, no unbind`() {
        // Recording holds the keep-alive; releasing the preview under it must
        // leave the recording binding untouched.
        assertEquals(
            CameraSessionAction.Noop,
            decide(Trigger.RebindCheck, keepAliveRefCount = 1, exclusiveSessionRefCount = 1),
        )
    }

    // ── the opportunistic gates (selectLens / headless switch / auto-recovery) ──

    @Test
    fun `lens select rebinds only while the camera is free`() {
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindIfFree, previewRequested = true),
        )
        assertEquals(
            CameraSessionAction.Noop,
            decide(Trigger.RebindIfFree, previewRequested = true, exclusiveSessionRefCount = 1),
        )
    }

    @Test
    fun `a free-gated consult never unbinds - that cell belongs to the shared ladder`() {
        // selectLens with no demand only logs "will switch on next active
        // session"; the ungated rebind entry unbinds. Same state, different
        // verdicts per trigger.
        assertEquals(CameraSessionAction.Noop, decide(Trigger.RebindIfFree))
        assertEquals(BindingAction.Unbind, decide(Trigger.RebindCheck))
    }

    @Test
    fun `the headless switch rebinds on keep-alive demand when free`() {
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindIfFree, keepAliveRefCount = 1),
        )
    }

    // ── resolution changes (settings apply / pending flush) — the same free-gated trigger, one merged verdict table ──

    @Test
    fun `a resolution change rebinds now while free`() {
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindIfFree, previewRequested = true),
        )
    }

    @Test
    fun `a resolution change is parked unless the camera is free`() {
        // An exclusive session holds it…
        assertEquals(
            CameraSessionAction.Noop,
            decide(Trigger.RebindIfFree, previewRequested = true, exclusiveSessionRefCount = 1),
        )
        // …and so does no active demand.
        assertEquals(CameraSessionAction.Noop, decide(Trigger.RebindIfFree))
    }

    // ── the resume hook ──

    @Test
    fun `resume with a pending resolution applies it`() {
        assertEquals(
            CameraSessionAction.ApplyPendingResolution,
            decide(
                Trigger.ActivityResumed,
                previewRequested = true,
                previewSurfaceAttached = true,
                resolutionChangePending = true,
            ),
        )
    }

    @Test
    fun `resume without a pending change rebinds to restore the preview`() {
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.ActivityResumed, previewRequested = true, previewSurfaceAttached = true),
        )
    }

    @Test
    fun `resume defers under an exclusive session even with a pending change`() {
        assertEquals(
            CameraSessionAction.Noop,
            decide(
                Trigger.ActivityResumed,
                previewRequested = true,
                exclusiveSessionRefCount = 1,
                resolutionChangePending = true,
            ),
        )
    }

    @Test
    fun `resume reads previewRequested alone - the preserved asymmetry`() {
        // Keep-alive-only streaming (no preview) is NOT restored on resume,
        // even though the same state lets a resolution flush rebind. The old
        // service gate passed previewRequested by itself; pinned here so
        // changing it is a deliberate, visible act.
        assertEquals(
            CameraSessionAction.Noop,
            decide(
                Trigger.ActivityResumed,
                keepAliveRefCount = 1,
                resolutionChangePending = true,
            ),
        )
        assertEquals(
            BindingAction.Rebind,
            decide(Trigger.RebindIfFree, keepAliveRefCount = 1, resolutionChangePending = true),
        )
    }

    // ── the stop hook ──

    @Test
    fun `stopping with an attached surface detaches it, without one it is a no-op`() {
        assertEquals(
            CameraSessionAction.DetachSurface,
            decide(Trigger.ActivityStopping, previewRequested = true, previewSurfaceAttached = true),
        )
        assertEquals(
            CameraSessionAction.Noop,
            decide(Trigger.ActivityStopping, previewRequested = true, previewSurfaceAttached = false),
        )
    }

    // ── the freedom verdict stays ResolutionApplyPolicy's ──

    @Test
    fun `the state's freedom verdict agrees with ResolutionApplyPolicy over the whole matrix`() {
        for (preview in listOf(false, true)) {
            for (keepAlive in listOf(0, 1)) {
                for (exclusive in listOf(false, true)) {
                    val state = CameraDemandState(
                        previewRequested = preview,
                        keepAliveRefCount = keepAlive,
                        exclusiveSessionRefCount = if (exclusive) 1 else 0,
                        activityForeground = true,
                        previewSurfaceAttached = preview,
                        resolutionChangePending = false,
                        trigger = Trigger.RebindCheck,
                    )
                    assertEquals(
                        "preview=$preview keepAlive=$keepAlive exclusive=$exclusive",
                        ResolutionApplyPolicy.isCameraFree(state.demandActive, state.exclusiveSessionActive),
                        state.cameraFree,
                    )
                }
            }
        }
    }
}

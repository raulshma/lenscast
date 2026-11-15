package com.raulshma.lenscast.camera.model

/**
 * The post-bind controls-apply ladder. CameraX drops control calls issued
 * synchronously inside the bind window (the camera2 session is still
 * settling): a fresh camera would come up at platform defaults — auto AE,
 * 1.0 zoom, torch dark — while [com.raulshma.lenscast.camera.model.CameraSettings]
 * says otherwise, which is exactly the "manual ISO/exposure reset after a
 * rebind" failure class. The first rung lands past the empirically verified
 * settle window (DRG / Nokia 6.1 Plus, 400 ms — the value the original single
 * deferred apply shipped with); slower HALs can still swallow that one
 * non-retried apply (the 0.2.1 screen-on reset reports), so a bounded second
 * rung re-asserts. Applies are idempotent — an extra rung costs nothing but
 * a re-assert, and a rung firing after a *newer* bind simply targets the
 * newer session's live camera.
 */
object ControlsApplyPolicy {

    /** The deferred-apply rungs, in fire order; every bind schedules one apply per rung. */
    fun applyDelaysMs(): List<Long> = listOf(FIRST_RUNG_MS, SECOND_RUNG_MS)

    /**
     * The settle budget the ladder gives a fresh session before the controls
     * are considered re-asserted (the last rung's delay) — the number the
     * logging and tests read.
     */
    fun settleBudgetMs(): Long = applyDelaysMs().last()

    private const val FIRST_RUNG_MS = 400L
    private const val SECOND_RUNG_MS = 1500L
}

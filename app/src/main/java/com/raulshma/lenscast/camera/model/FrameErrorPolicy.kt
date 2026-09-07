package com.raulshma.lenscast.camera.model

/**
 * The pure frame-error recovery verdict for the camera analysis path. A
 * burst of consecutive frame-processing errors means the bound pipeline is
 * wedged and the camera should rebind; a lone error after a long quiet gap
 * must not inherit an ancient streak. The counter bookkeeping stays in
 * [com.raulshma.lenscast.camera.CameraService] (it owns the frame callback);
 * this policy owns the two numbers that govern it — the streak threshold
 * and the reset window — so the recovery semantics are JVM-tested.
 */
object FrameErrorPolicy {

    /** Consecutive errors (within one reset window) that trigger recovery. */
    const val MAX_CONSECUTIVE_FRAME_ERRORS = 10

    /** Errors further apart than this are not consecutive — the streak resets. */
    const val ERROR_RESET_WINDOW_MS = 5000L

    /**
     * True when [nowMs] sits more than one reset window after
     * [lastErrorMs]: the recorded streak is stale and counting restarts
     * from zero. Errors exactly [ERROR_RESET_WINDOW_MS] apart are still
     * consecutive (the window is inclusive), matching the original
     * `elapsed > window` check.
     */
    fun streakExpired(nowMs: Long, lastErrorMs: Long): Boolean =
        nowMs - lastErrorMs > ERROR_RESET_WINDOW_MS

    /**
     * True when the error streak — already reset for an expired window and
     * incremented by the caller — has reached the recovery threshold. The
     * caller resets its counter after triggering recovery.
     */
    fun shouldRecover(consecutiveErrors: Int, nowMs: Long, lastErrorMs: Long): Boolean =
        !streakExpired(nowMs, lastErrorMs) && consecutiveErrors >= MAX_CONSECUTIVE_FRAME_ERRORS
}

package com.raulshma.lenscast.core

/**
 * Pure locked-mount wake verdict: should the full-screen-intent notification
 * be posted for this denial, or skipped, and why. [LockBypassController]
 * keeps the Android reads (keyguard state, POST_NOTIFICATIONS, the
 * full-screen-intent permission), the attempt bookkeeping, and the posting;
 * every decision here is a plain input, and the ladder plus the throttle
 * floor are JVM-tested.
 */
object LockBypassPolicy {

    enum class Verdict {
        /** All gates passed — post the wake notification and record the attempt. */
        POST,
        /** Not a locked-mount denial — the resume seam owns the unlocked case. */
        SKIP_UNLOCKED,
        /** No way to post any notification, let alone a full-screen one. */
        SKIP_NO_POST_NOTIFICATIONS,
        /** Android 14+ revoked the full-screen intent — a heads-up banner would wake-pretend. */
        SKIP_NO_FULL_SCREEN_INTENT,
        /** Inside the re-arm window — a retry loop must not re-launch the shim. */
        SKIP_THROTTLED,
    }

    /**
     * The retry floor between wake attempts: a watchdog pass that keeps
     * failing under lock re-lands on the denied promotion repeatedly, and
     * each pass must not re-launch the shim. One wake per episode plus a
     * re-arm window is enough — the shim itself heals within seconds.
     */
    const val MIN_ATTEMPT_INTERVAL_MS = 30_000L

    fun resolve(
        isKeyguardLocked: Boolean,
        postNotificationsGranted: Boolean,
        canUseFullScreenIntent: Boolean,
        nowMs: Long,
        lastAttemptAtMs: Long,
    ): Verdict {
        return when {
            !isKeyguardLocked -> Verdict.SKIP_UNLOCKED
            !postNotificationsGranted -> Verdict.SKIP_NO_POST_NOTIFICATIONS
            !canUseFullScreenIntent -> Verdict.SKIP_NO_FULL_SCREEN_INTENT
            // No prior attempt (negative = unset), no throttle: the first
            // wake of the process must never wait on the re-arm window.
            lastAttemptAtMs < 0 -> Verdict.POST
            nowMs - lastAttemptAtMs < MIN_ATTEMPT_INTERVAL_MS -> Verdict.SKIP_THROTTLED
            else -> Verdict.POST
        }
    }
}

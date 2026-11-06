package com.raulshma.lenscast.core

/**
 * Pure renew ladder for the streaming wake lock: a session outlives the
 * 60-minute acquire budget, and a suspended CPU stalls the frame pump, the
 * automation engine and every detection path at once. [PowerManager] keeps
 * the lock and the elapsed-time read and delegates every decision here; the
 * threshold and its boundary are JVM-tested.
 */
object WakeLockRenewPolicy {

    // The streaming slot's acquire budget and its renew point. They are this
    // policy's own knowledge, not cross-module config — kept here instead of
    // StreamDefaults.
    const val TIMEOUT_MS = 60 * 60 * 1000L
    const val RENEW_AFTER_MS = TIMEOUT_MS / 2

    enum class Action {
        /** The lock was never taken or already lost — plain acquire. */
        ACQUIRE,
        /** The lock is held and young — leave it alone. */
        KEEP,
        /**
         * The lock is held but old — release, then re-acquire: the acquire
         * guard would ignore a plain re-acquire.
         */
        REARM,
    }

    /**
     * Halfway, not near expiry: the 30-second session poll renews each tick,
     * so the worst-case remaining budget is ~[RENEW_AFTER_MS] — never close
     * enough to expiry for the CPU to suspend.
     */
    fun resolve(held: Boolean, elapsedSinceAcquireMs: Long): Action = when {
        !held -> Action.ACQUIRE
        elapsedSinceAcquireMs < RENEW_AFTER_MS -> Action.KEEP
        else -> Action.REARM
    }
}

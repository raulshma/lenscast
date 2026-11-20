package com.raulshma.lenscast.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * The reliability runtime's consecutive-miss counter: owns the
 * increment-then-decide convention the two policy call sites used to hold as
 * prose ([WatchdogPolicy.nextTick]'s and [WatchdogAlarmPolicy.decide]'s
 * "the caller counts the current event in" rule). The caller hands in the
 * event's verdict — [recordFailure] on a miss, [reset] on success — and the
 * count that INCLUDES the current event is what comes back out to pass into
 * the policy, so increment-before-ask is structural: a call site can no
 * longer ask with a stale count. AtomicInteger inside, deliberately: the
 * two call sites advanced the raw counter the same way, and the heartbeat's
 * evaluation runs on a dispatcher thread while the loop's ticks run on
 * another.
 */
class MissCounter {

    private val count = AtomicInteger(0)

    /**
     * Records one failed event and returns the consecutive-failure count
     * INCLUDING it — the value to pass into the policy (miss 1 is the first
     * tier, not a zeroth look).
     */
    fun recordFailure(): Int = count.incrementAndGet()

    /** Zeroes the run — a healthy event (the policy's RESET verdict) or a re-armed baseline. */
    fun reset() {
        count.set(0)
    }

    /** The current run length without recording — telemetry and log lines read this; decisions never key on it. */
    val current: Int get() = count.get()
}

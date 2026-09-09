package com.raulshma.lenscast.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The auto-stop timer behind a started siren — one process-wide instance
 * (owned by the composition root, injected into both users) shared by the
 * detection coordinator's auto-deterrence and the external automation
 * receiver, so a stop armed by one owner cannot be missed or duplicated by
 * the other's instance. One cancellable job per run, replaced (never
 * stacked) when a new start re-arms the timer, and cancelled when the siren
 * is stopped by hand.
 */
class SirenAutoStop(private val scope: CoroutineScope) {

    private val lock = Any()
    private var job: Job? = null

    /**
     * Arms the auto-stop for a siren start: cancels any pending stop from an
     * older run — a duration command can extend a running siren without the
     * stale timer cutting the new run short — then, when [durationMs] is
     * positive, schedules [stop] after it. A non-positive duration arms no
     * timer (the run lasts until stopped). Safe to call concurrently.
     */
    fun armAfterStart(durationMs: Long, stop: () -> Unit) {
        synchronized(lock) {
            job?.cancel()
            job = if (durationMs > 0) {
                scope.launch {
                    delay(durationMs)
                    stop()
                }
            } else {
                null
            }
        }
    }

    /** Cancels a pending auto-stop (the manual-stop path). */
    fun cancel() {
        synchronized(lock) { job?.cancel() }
    }
}

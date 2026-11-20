package com.raulshma.lenscast.core

import com.raulshma.lenscast.streaming.ServerForegroundController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one grace-windowed arm/disarm bridge over the server's runtime state
 * (blueprint P0 item 2, resolved tension 3, issue #14's race class): collects
 * the one `StreamingManager.isServerRunning` flow, runs [arm] on the up leg,
 * and delays [disarm] past [stopGraceMs] on the down leg — because a server *restart*
 * bounces the flow false → true within the same restart, and an immediate
 * disarm on the down leg would tear down exactly the thing performing the
 * recovery (in server-only mode the watchdog's own MEDIUM tier is what
 * bounces the flag). A promote inside the window cancels the pending stop; a
 * real stop releases the watch after the grace.
 *
 * **The fire-time re-check, on the stop leg.** The pending stop's WHOLE
 * premise is "the server is really down" — but its cancel can lose a timing
 * race to a starved dispatcher (the delayed stop resuming after the up leg's
 * write landed, before the collector's up leg ran), and a delayed stop that
 * kills a just-restarted server is the #14 failure shape. So the delayed job
 * re-reads the flow at FIRE time and disarms only when the server is still
 * down. The re-check AND [disarm] run inside the same monitor as the up
 * leg's [arm]: a job past its delay cannot be canceled, so without the
 * shared lock a stale stop could still disarm behind a live arm (the
 * residual #14 shape). The delay itself stays outside the monitor — the
 * grace window is never serialized. All three consumers (the composition root's server-liveness watch —
 * the `serverWatchdogArming` leg MainApplication wires, the former
 * standalone arming class inlined into that wiring —
 * [HeartbeatController]'s alarm, and [ServerForegroundController]'s
 * foreground service) get the re-check from this one implementation — the
 * duplicate the Heartbeat leg carried was exactly that, one leg's guard, and
 * the other two legs lacked it (the foreground leg's missing re-check was
 * the last #14-shaped hole) until they migrated onto this bridge.
 *
 * Deliberately dumb otherwise: no decisions, no Android — the consumers hand
 * in the two lambdas and keep every policy (what "arm" and "disarm" mean)
 * on their side of the seam.
 */
class GraceWindowedArming(
    private val isServerRunning: StateFlow<Boolean>,
    /** The down-leg grace; defaults to the same window ServerForegroundController measured restarts inside. */
    val stopGraceMs: Long = ServerForegroundController.SERVER_STOP_GRACE_MS,
    /** The up leg — run on every true emission (idempotent per consumer contract). */
    private val arm: () -> Unit,
    /** The down leg — run when a stop survives the grace AND the fire-time re-check. */
    private val disarm: () -> Unit,
) {

    // Set by [start]; a stop landing before wiring runs immediately.
    private var scope: CoroutineScope? = null
    private var pendingStop: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            isServerRunning.collect { running -> apply(running) }
        }
    }

    /**
     * Re-dispatch a state without waiting for the next emission — the
     * sync/resume retry hook ([ServerForegroundController.sync] calls it
     * while the app is visible, to heal a start Android denied in the
     * background). The same path the collector runs, re-check included on
     * the stop leg.
     */
    internal fun applyNow(running: Boolean) = apply(running)

    private fun apply(running: Boolean) = synchronized(this) {
        pendingStop?.cancel()
        pendingStop = null
        if (running) {
            arm()
        } else {
            val target = scope
            if (target == null) {
                disarm()
            } else {
                pendingStop = target.launch {
                    delay(stopGraceMs)
                    // Fire-time re-check under the up leg's monitor (see the
                    // class KDoc): a job past its delay cannot be canceled,
                    // so the re-check + disarm must serialize against arm —
                    // a server that came back inside the window must never
                    // lose its watch, alarm, or foreground service to a
                    // stale stop.
                    synchronized(this@GraceWindowedArming) {
                        if (!isServerRunning.value) {
                            disarm()
                        }
                    }
                }
            }
        }
    }
}

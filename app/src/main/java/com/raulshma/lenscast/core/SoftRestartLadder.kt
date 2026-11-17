package com.raulshma.lenscast.core

import com.raulshma.lenscast.camera.model.StreamStartOutcome

/**
 * The in-process soft-restart ladder (blueprint P1 item 9, restart-reliability
 * §4.2): fix the majority of "stream wedged" cases WITHOUT process death —
 * the only automated tier that preserves the foreground-started FGS's
 * while-in-use camera standing (blueprint resolved tension 2's invariant:
 * in-process tiers that keep the process alive are strictly stronger than
 * death-and-relaunch tiers, because a foreground-started FGS keeps its camera
 * standing for the process's lifetime and no alarm/boot/battery exemption
 * ever restores it after death).
 *
 * The steps are [MainApplication]'s quit ladder minus `exitProcess`, plus the
 * camera rebind the quit ladder never needed (its process death releases
 * everything anyway): stop a live recording (its finalize must not be cut
 * short mid-write) → stop the whole server (every output, transport, mDNS
 * and session, dropping the FGS/wake-lock choreography the same way a user
 * toggle would) → rebind the camera (the release side falls out of the
 * session end; the rebind re-establishes demand under the STILL-ALIVE FGS) →
 * re-arm the server. Every seam already existed — this class is pure
 * orchestration over four injected lambdas, so the ladder is JVM-testable
 * and has exactly ONE implementation with TWO entry points: the dashboard's
 * `POST /api/system/restart?mode=soft` and the heartbeat's SOFT_RESTART tier.
 *
 * **Failure semantics.** The two stop steps are best-effort exactly like the
 * quit ladder's (`runCatching` — a wedged recorder or server that refuses to
 * stop must not block the recovery attempt; the re-arm below is what heals).
 * The camera rebind and the server re-arm are decisive: a rebind that threw
 * or came back unbound, or a start that failed (throw, refused start, or a
 * rolled-back session begin), answers [Result.StepFailed] naming the step —
 * the caller (the HTTP handler) turns that into the typed refusal shape so a
 * soft restart can never report success it did not earn. Order matters for
 * reporting: the re-arm runs even after a failed rebind (the server being up
 * is the tier's primary contract — camera healing continues via the unlock
 * heal and the heartbeat's SOFT tier), and a failed re-arm outranks a failed
 * rebind in the report because a dark server is the worse truth.
 *
 * **The response-drop race** (the TLS-flip lesson, SettingsApplier's stop →
 * recreate → start transport cycle): the HTTP request whose handler runs this
 * ladder rode a connection accepted by the OLD listener socket, and
 * `stopServer` tears that socket down — so a verdict awaited inline in the
 * handler was written onto a dead connection and never reached the client.
 * The ladder itself stays a synchronous runner (the heartbeat tier wants the
 * verdict); the delivery fix lives in the HTTP entry, which LAUNCHES the
 * ladder off the request thread past the response flush and answers the
 * acknowledgment first (MainApplication.launchSoftRestart — the same
 * answer-before-teardown shape the deep restart uses). The dashboard's
 * dropped-response poll fallback remains as the safety net.
 */
class SoftRestartLadder(
    /** Stop a live recording; best-effort (the quit ladder's shape). */
    private val stopRecording: suspend () -> Unit,
    /** Stop the whole server (outputs, transport, session); best-effort. */
    private val stopServer: suspend () -> Unit,
    /**
     * The camera rebind under the surviving FGS — wired to the session's
     * SOFT recovery mechanics in production (Main-dispatched, bounded, and
     * carrying the blocked-by-lock bookkeeping); false or a throw is a
     * decisive failure.
     */
    private val rebindCamera: suspend () -> Boolean,
    /** The whole-server start ladder; any start failure is decisive. */
    private val startServer: suspend () -> StreamStartOutcome,
) {

    /** The step names the wire's `step` field carries (the typed soft failure). */
    object Step {
        const val REBIND_CAMERA = "rebindCamera"
        const val START_SERVER = "startServer"
    }

    /** The ladder's typed outcome: restarted, or the first decisive step that failed. */
    sealed interface Result {
        /** Every decisive step landed: the server re-armed under the surviving FGS. */
        data object Restarted : Result

        /**
         * A decisive step failed. [step] is machine-readable ([Step] names);
         * [detail] is the readable cause for logs and hints.
         */
        data class StepFailed(val step: String, val detail: String) : Result
    }

    /**
     * Run the ladder in order. Never throws: every step is runCatching-wrapped
     * and the outcome is typed, so the heartbeat tier and the HTTP handler share
     * one failure shape.
     */
    suspend fun run(): Result {
        // Best-effort stops — the quit ladder tolerates a wedged stop for the
        // same reason: the point of this tier is the recovery below it.
        runCatching { stopRecording() }
        runCatching { stopServer() }
        val rebind = runCatching { rebindCamera() }
        val start = runCatching { startServer() }
        // The re-arm outranks the rebind in the report (a dark server is the
        // worse truth), but both are decisive.
        start.getOrNull()?.takeIf { it.isStartFailure }?.let { outcome ->
            return Result.StepFailed(
                Step.START_SERVER,
                StreamStartOutcome.startFailedMessage(outcome.kindOrNull()),
            )
        }
        start.exceptionOrNull()?.let {
            return Result.StepFailed(Step.START_SERVER, it.message ?: it.javaClass.simpleName)
        }
        rebind.getOrNull()?.takeIf { !it }?.let {
            return Result.StepFailed(Step.REBIND_CAMERA, "camera rebind did not bind (denied or timed out)")
        }
        rebind.exceptionOrNull()?.let {
            return Result.StepFailed(Step.REBIND_CAMERA, it.message ?: it.javaClass.simpleName)
        }
        return Result.Restarted
    }
}

/** The server ladder's outcome kind for the failure wording (null-safe: the whole-server start reports kind null). */
private fun StreamStartOutcome.kindOrNull() = when (this) {
    is StreamStartOutcome.StartFailed -> this.kind
    is StreamStartOutcome.BeginFailedRolledBack -> this.kind
    else -> null
}

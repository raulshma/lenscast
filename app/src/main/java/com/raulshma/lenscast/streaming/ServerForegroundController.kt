package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.GraceWindowedArming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Maps the web server's runtime state to the foreground service's server-only
 * mode. The server is started at app launch and can outlive every stream, so
 * this is the piece that keeps the process in the foreground (and its
 * outbound alert traffic outside Android's background-network firewall) even
 * when no camera output is live.
 *
 * The session remains the single owner of foreground-service intents and of
 * the stream-mode transitions; this class only observes the server lifecycle.
 * It is deliberately separate from SettingsApplier: server state is runtime
 * state, not a persisted setting.
 *
 * A server *restart* (port/TLS apply, watchdog recovery) is one choreography
 * that bounces [isServerRunning] false → true within the same restart, so the
 * down leg never stops the service directly: a stop fired on the transient
 * down leg races the promotion that immediately follows — system_server can
 * process the stop's bring-down after the next start has armed its foreground
 * obligation but before the service has called startForeground, which throws
 * ForegroundServiceDidNotStartInTimeException in-process and kills the app
 * (issue #14: one Streaming Port slider drag restarts the transport once per
 * frame and crashed the app ~1 drag in 10). The grace window, the promote
 * cancel, and the fire-time re-check live in the one shared
 * [GraceWindowedArming] bridge (the composition root's server-liveness
 * watch and the HeartbeatController legs share it — the race handling is
 * written once, not once per leg). This class keeps only the mapping: server up → the live
 * session's promotion re-assert or the server-only foreground, a stop that
 * survives the grace and the re-check → stop-if-idle.
 *
 * [start] is the one-shot wiring for the application scope. [sync] is the
 * retry hook for the cases Android denies a background start - call it while
 * the app is visible and the server is running.
 */
class ServerForegroundController(
    private val session: StreamingSession,
    private val isServerRunning: StateFlow<Boolean>,
    private val stopGraceMs: Long = SERVER_STOP_GRACE_MS,
) {

    // The shared grace-windowed bridge; the arming semantics are the two
    // lambdas below, everything else (the grace, the pending-stop cancel,
    // the fire-time re-check) is the bridge's one implementation.
    private val arming = GraceWindowedArming(
        isServerRunning = isServerRunning,
        stopGraceMs = stopGraceMs,
        arm = {
            if (session.isActive) {
                // A live session owns the stronger CAMERA/MICROPHONE
                // promotion — re-assert it rather than the server-only one
                // (which would no-op). This is also the resume heal for a
                // start whose typed promotion was denied while the app was
                // backgrounded or locked.
                session.reassertStreamingForegroundIfActive()
            } else {
                session.ensureServerForeground()
            }
        },
        disarm = { session.stopServerForegroundIfIdle() },
    )

    fun start(scope: CoroutineScope) {
        arming.start(scope)
    }

    /** Re-apply the current server state; safe to call from Activity.onResume. */
    fun sync() = arming.applyNow(isServerRunning.value)

    companion object {
        /**
         * How long the server must stay down before the service's server-only
         * foreground mode is dropped. Must comfortably exceed the down leg of
         * one transport restart (measured 10-200 ms) so a restart never
         * bounces the service, while a real server stop still releases the
         * notification promptly.
         */
        const val SERVER_STOP_GRACE_MS = 500L
    }
}

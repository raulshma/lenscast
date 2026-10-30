package com.raulshma.lenscast.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * down leg never stops the service directly: the stop is scheduled behind a
 * grace window and a promote inside that window cancels it. Tearing the
 * service down on the transient down leg races the promotion that immediately
 * follows — system_server can process the stop's bring-down after the next
 * start has armed its foreground obligation but before the service has called
 * startForeground, which throws ForegroundServiceDidNotStartInTimeException
 * in-process and kills the app (issue #14: one Streaming Port slider drag
 * restarts the transport once per frame and crashed the app ~1 drag in 10).
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

    // Set by [start]; a [sync] that lands before wiring has no scope for the
    // delayed stop and falls back to the immediate stop.
    private var scope: CoroutineScope? = null
    private var pendingStop: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            isServerRunning.collect { running -> apply(running) }
        }
    }

    /** Re-apply the current server state; safe to call from Activity.onResume. */
    fun sync() = apply(isServerRunning.value)

    private fun apply(running: Boolean) = synchronized(this) {
        pendingStop?.cancel()
        pendingStop = null
        if (running) {
            session.ensureServerForeground()
        } else {
            val target = scope
            if (target == null) {
                session.stopServerForegroundIfIdle()
            } else {
                pendingStop = target.launch {
                    delay(stopGraceMs)
                    session.stopServerForegroundIfIdle()
                }
            }
        }
    }

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

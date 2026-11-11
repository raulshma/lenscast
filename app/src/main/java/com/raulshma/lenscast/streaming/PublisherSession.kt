package com.raulshma.lenscast.streaming

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The push publishers' shared lifecycle machine — the wanted/started CAS
 * pair, the supervised reconnect loop, and the capped backoff — extracted
 * verbatim from the three hand-rolled copies in the RTMP, SRT, and WHIP
 * publishers. The status type stays generic on purpose: the three status
 * hierarchies share no supertype, and inventing one would couple transports
 * that only agree on the Idle/Connecting/Connected/Error shape.
 *
 * ## The machine
 *
 * - [start] wins a `started` false→true CAS (so a second start is a no-op),
 *   raises `wanted`, and launches exactly one loop on [scope]; the loop's
 *   completion cancels that scope, so nothing outlives the machine.
 * - Each iteration publishes [connectingStatus], runs [connect] (the
 *   transport's per-attempt ladder), and resets the attempt counter to zero
 *   on a clean return — a session that ended cleanly starts over without
 *   penalty. A thrown [Exception] publishes [errorStatus] of the exception's
 *   message (falling back to [defaultErrorMessage] when it carries none) and
 *   reports through [logAttemptFailed] — unless `wanted` already went false,
 *   in which case the stop wins and no Error is ever surfaced.
 * - Between attempts the loop delays [backoffMs]: 1 s doubling per
 *   consecutive failure, capped at 30 s. The loop exits only when `wanted`
 *   is false, and its last act is publishing [idleStatus] — so a stop
 *   mid-attempt lands on Idle, never on Error.
 * - [stop] wins the `started` true→false CAS (so a second stop is a no-op),
 *   clears `wanted`, and runs [onStop] — the transport's close choreography.
 *
 * ## Canonicalization note
 *
 * The three originals ordered the backoff tail differently: RTMP computed
 * the delay, incremented the attempt, then delayed; SRT and WHIP delayed,
 * then incremented. The behavior is identical — `attempt` is coroutine-local
 * and unread during the delay, and the delayed amount is `backoffMs(attempt)`
 * taken before any increment in all three — so the session canonicalizes on
 * delay-then-increment.
 *
 * ## Why [scope] is publisher-owned
 *
 * The scope is constructed by (and shared with) the publisher on purpose:
 * the stop choreographies launch cleanup work on it (RTMP's delayed socket
 * force-close, SRT's best-effort shutdown notice), and the transports run
 * their attempt-side coroutines (media writers, read loops) on it too. The
 * session only rides the scope the publisher already owns.
 *
 * ## Why [onStop] is opaque
 *
 * The stop hook is one opaque lambda per publisher, deliberately not
 * decomposed into channel/socket/resource primitives, because the three
 * bodies are genuinely different choreographies rather than variations of
 * one: RTMP closes the media channel — whose writer-drain tail is the clean
 * close — then force-closes the socket after a 2 s grace in case the clean
 * close is blocked on a dead network; SRT closes the channel, stop-sends a
 * best-effort shutdown notice, then force-closes after the same grace; WHIP
 * only nulls its video source and lets the attempt loop's own watch beat run
 * the teardown (every WHIP step is deadline-bounded, so no force-close
 * exists to add).
 *
 * [delayFn] is a test seam; production timing is the plain [delay].
 */
internal class PublisherSession<S>(
    private val onStatus: (S) -> Unit,
    private val idleStatus: S,
    private val connectingStatus: S,
    private val errorStatus: (String) -> S,
    private val defaultErrorMessage: String,
    private val connect: suspend () -> Unit,
    private val logAttemptFailed: (Exception) -> Unit,
    private val onStop: () -> Unit,
    private val scope: CoroutineScope,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {

    private val wanted = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile var statusValue: S = idleStatus
        private set

    /** The current lifecycle state. */
    fun status(): S = statusValue

    /**
     * Whether the output is still wanted — the transports' read loops,
     * watch beats, and frame feeds poll this; the machine clears it before
     * [onStop] runs.
     */
    fun isWanted(): Boolean = wanted.get()

    private fun publishStatus(status: S) {
        statusValue = status
        onStatus(status)
    }

    /**
     * Publishes a mid-attempt state from the transport's own ladder (the
     * Connected announcements): writes the field, then invokes [onStatus] —
     * that exact order, as everywhere else in the machine.
     */
    fun publish(status: S) = publishStatus(status)

    /** Starts the supervised reconnect loop; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        wanted.set(true)
        val job = scope.launch {
            var attempt = 0
            while (wanted.get()) {
                publishStatus(connectingStatus)
                try {
                    connect()
                    attempt = 0 // a session that ended cleanly starts over without penalty
                } catch (e: Exception) {
                    if (!wanted.get()) break
                    publishStatus(errorStatus(e.message ?: defaultErrorMessage))
                    logAttemptFailed(e)
                }
                if (!wanted.get()) break
                delayFn(backoffMs(attempt))
                attempt += 1
            }
            publishStatus(idleStatus)
        }
        job.invokeOnCompletion { scope.cancel() }
    }

    /** Stops the loop and runs the transport's close choreography; idempotent. */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        wanted.set(false)
        onStop()
    }

    private fun backoffMs(attempt: Int): Long {
        var ms = INITIAL_BACKOFF_MS
        repeat(attempt.coerceAtLeast(0)) {
            ms = (ms * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return ms
    }

    private companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

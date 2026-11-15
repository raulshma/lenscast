package com.raulshma.lenscast.companion.client.sse

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One server-sent event off the remote's SSE lanes (`/api/events`,
 * `/api/status/stream`): the `event:` line's name (null on the default
 * unnamed event) and the concatenated `data:` payload.
 */
data class SseEvent(val name: String?, val data: String)

/**
 * One open SSE connection: the hot event stream, the failure lane, and the
 * cancellation seam. [events] is a [SharedFlow] — multiple collectors (the
 * session's status conflater, the event-feed writer) tap one connection,
 * and [cancel] tears the underlying call down once, from whichever owner
 * releases the session.
 *
 * The two flows NEVER complete — neither on server close nor on failure —
 * because a SharedFlow that completed would look like "nothing more to
 * say" to a collector that is really holding a dead socket. Every ending
 * lands on [failures] instead ([IOException]; [EventSourceClosedException]
 * when the server ended the response cleanly), so the caller's reconnect
 * policy has one lane to watch for "connection ended, reconnect".
 */
interface SseStream {
    val events: SharedFlow<SseEvent>

    /**
     * Every non-planned ending of the underlying connection: transport
     * errors from okhttp-sse's onFailure, and the server's clean end of the
     * chunked response (the device caps each SSE client connection's
     * lifetime and closes it — a clean close still needs a reconnect).
     * Deliberately NOT completed into [events], and never fired for a
     * caller-initiated [cancel]. Carries a small sticky replay so a
     * subscriber that attaches after the crash still learns the stream died.
     */
    val failures: SharedFlow<Throwable>

    /** Closes the connection; idempotent. */
    fun cancel()
}

/** Thrown (well, emitted) when the server ends the SSE response cleanly — not an error, but the stream is over. */
class EventSourceClosedException(message: String) : IOException(message)

/**
 * The companion mode's SSE front door over okhttp-sse: opens one
 * long-lived event stream to a paired device and re-raises the parsed
 * [SseEvent]s as a hot [SharedFlow]. Auth headers ride [open]'s header map
 * (the same X-Api-Token / Basic ladder the JSON client builds).
 *
 * Signatures frozen in wave 0; implemented in wave 1 (agent C) — [SseStream.failures]
 * is the one additive member the wave brief authorized.
 *
 * Buffering decision (the wave-0 open question, resolved here):
 *
 *  - **SharedFlow, not Flow**: one physical connection feeds two semantic
 *    lanes (the `status`/`transport` events of `/api/events` and the
 *    data-only detection frames of `/api/detection/events/stream`). Both
 *    consumers collect the same hot stream; a cold flow would force either
 *    two sockets or an internal multiplexer.
 *  - **`replay = REPLAY_EVENTS` (8), deliberately small**: the device bursts
 *    its connect-time detection backlog (the server replays the latest 50
 *    events as `data:` frames the instant the connection opens) before the
 *    caller's first `collect` can possibly have subscribed — open() returns
 *    before events fly. A small replay covers exactly that subscribe gap.
 *    It is kept small on purpose: a big replay would serve every
 *    re-subscribing collector a stale backlog of old events as if they had
 *    just arrived, and the detection consumer dedupes by id anyway, so
 *    replaying a handful is harmless but replaying hundreds is a lie.
 *  - **`extraBufferCapacity = EVENT_BUFFER_CAPACITY` (32) with
 *    [BufferOverflow.SUSPEND], NOT DROP_OLDEST**: detection events are
 *    security telemetry — a silently dropped motion alert is a missed
 *    break-in, so conflation/dropping is the one policy this feed must
 *    never take (a conflated status event would be fine; conflating them
 *    together is not). With SUSPEND, a burst longer than the buffer parks
 *    the emitter (see below) instead of discarding, and a *stalled*
 *    collector turns into TCP backpressure: the server's writer thread
 *    blocks on its socket, which is the honest end state for a client that
 *    stopped listening.
 *  - **Emission blocks the okhttp-sse reader thread on overflow**: the
 *    listener runs `runBlocking { emit(...) }` on OkHttp's dedicated SSE
 *    reader thread. That thread exists only to pump this connection, so
 *    blocking it is exactly the backpressure story above — and it keeps
 *    this class at ZERO internally launched coroutines: nothing to leak
 *    when [cancel] fires, no dispatcher to shut down. The trade-off is that
 *    a permanently-stuck collector pins the reader thread until it
 *    detaches; callers own collector lifecycle, and `cancel()` + dropping
 *    the collectors always unwinds it (a detached subscriber unblocks
 *    `emit`, the reader resumes, sees the cancelled call, and exits).
 *
 * Event mapping: okhttp-sse already joins multi-line `data:` payloads with
 * `\n` per the SSE spec and skips `: ping` comment keep-alives. The spec's
 * default event name ("message", which the device's data-only detection
 * frames use) is surfaced as `name = null` to match the wire truth — the
 * server never sends a literal `event: message` ([com.raulshma.lenscast.streaming.SseStatusStream]
 * emits `status`/`transport`, the detection stream emits data-only frames).
 */
class SseEventSource(private val client: OkHttpClient) {

    /**
     * Opens one SSE connection to [url], sending [headers] verbatim on the
     * request (auth, and `Accept: text/event-stream` — added here when the
     * caller did not supply one). The returned stream is live immediately;
     * reconnect/backoff policy is the caller session's.
     */
    fun open(url: String, headers: Map<String, String> = emptyMap()): SseStream {
        val request = Request.Builder().url(url).apply {
            for ((name, value) in headers) header(name, value)
            // The negotiation header the EventSource contract implies; the
            // caller's explicit value wins when it chose to set one.
            if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                header("Accept", "text/event-stream")
            }
        }.build()
        val stream = LiveSseStream()
        val source = EventSources.createFactory(client).newEventSource(request, stream.listener)
        stream.attach(source)
        return stream
    }

    /** The [SseStream] of one open connection; also the okhttp-sse listener itself. */
    private class LiveSseStream : SseStream {

        private val _events = MutableSharedFlow<SseEvent>(
            replay = REPLAY_EVENTS,
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        override val events: SharedFlow<SseEvent> = _events

        private val _failures = MutableSharedFlow<Throwable>(
            replay = 1, // sticky: a late subscriber still sees WHY the stream died
            extraBufferCapacity = FAILURE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        override val failures: SharedFlow<Throwable> = _failures

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                // "message" is the SSE spec's default event name (data-only
                // frames — the detection stream's shape); the stub KDoc pins
                // that shape as name = null.
                val name = if (type == null || type == "message") null else type
                runBlocking { _events.emit(SseEvent(name, data)) }
            }

            override fun onFailure(source: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val cause = t ?: IOException("SSE stream failed (HTTP ${response?.code})")
                if (cancelled.get()) return // planned teardown: callers own lifecycle, no spurious failure
                runBlocking { _failures.emit(cause) }
            }

            override fun onClosed(source: EventSource) {
                // Clean end of the chunked response — the device's bounded
                // per-client lifetime. Still an ending: with no completion
                // and no error, this is the only way callers find out.
                if (cancelled.get()) return
                runBlocking { _failures.emit(EventSourceClosedException("SSE stream closed by server")) }
            }
        }

        private val source = AtomicReference<EventSource?>()
        private val cancelled = AtomicBoolean(false)

        /** Wires the EventSource in as soon as newEventSource hands it back. */
        fun attach(source: EventSource?) {
            this.source.set(source)
            // A cancel() that raced ahead of the attach must still win: the
            // call may already be enqueued, so cancelling now is not wasted.
            if (cancelled.get()) source?.cancel()
        }

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return // idempotent
            source.get()?.cancel()
        }

        companion object {
            /**
             * Covers the open→subscribe gap (the server bursts its detection
             * backlog on connect); small so a re-subscriber never wades
             * through a stale backlog (see class KDoc).
             */
            private const val REPLAY_EVENTS = 8

            /** Absorbs collector jitter before emission has to block. */
            private const val EVENT_BUFFER_CAPACITY = 32

            /** Failure bursts are single events; the buffer is pure headroom. */
            private const val FAILURE_BUFFER_CAPACITY = 8
        }
    }
}

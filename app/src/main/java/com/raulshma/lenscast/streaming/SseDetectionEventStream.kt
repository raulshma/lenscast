package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.capture.DetectionEvent
import com.raulshma.lenscast.streaming.web.DetectionEventsWebHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * The SSE detection-event channel: GET /api/detection/events/stream replays
 * the latest [REPLAY_EVENTS] events as `data:` frames on connect, then streams
 * every newly recorded (or clip-linked) event live, sending a `: ping`
 * comment whenever [pingIntervalMs] passes with no event. Served by the
 * [StreamingServer] outside the JSON router — the same never-ending chunked
 * response shape as the `/api/events` status stream ([SseStatusStream]), on
 * the shared [SseClientPump] transport (client cap, whole-chunk queue, one
 * writer thread per client, bounded lifetime).
 */
class SseDetectionEventStream(
    private val events: DetectionEventsWebHandler,
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
) {

    private val pump = SseClientPump(writerThreadName = "SseDetectionEventWriter")

    /** Null when the client cap is reached — the transport answers 503. */
    fun open(): InputStream? = pump.open {
        val live = Channel<DetectionEvent>(Channel.UNLIMITED)
        runBlocking {
            // The live subscription lands BEFORE the backlog snapshot:
            // with the flow at replay = 0, an event recorded between
            // snapshot and subscribe would fall in the gap forever.
            // It buffers here instead and flushes after the backlog
            // (a duplicate frame can reach the client — it dedupes by
            // event id, the same way it survives a reconnect replay).
            val subscription = launch {
                events.eventFlow().collect { live.trySend(it) }
            }
            try {
                // Connect-time backlog: the latest events, oldest first, in
                // the exact per-event JSON the polling GET returns.
                events.replayBacklog(REPLAY_EVENTS).forEach { event ->
                    enqueue("data: ${events.eventJson(event)}\n\n")
                }
                while (isOpen && !lifetimeSpent) {
                    val event = nextEventWithin(pingIntervalMs, live)
                    if (!isOpen) break
                    if (event != null) {
                        enqueue("data: ${events.eventJson(event)}\n\n")
                    } else {
                        // Keep-alive comment: holds proxies open and tells a
                        // healthy client the channel lives.
                        enqueue(": ping\n\n")
                    }
                }
            } finally {
                subscription.cancel()
            }
        }
    }

    /** The next live event, or null when [timeoutMs] passed with none. */
    private suspend fun nextEventWithin(
        timeoutMs: Long,
        live: Channel<DetectionEvent>,
    ): DetectionEvent? {
        return try {
            withTimeoutOrNull(timeoutMs) { live.receive() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val DEFAULT_PING_INTERVAL_MS = 15_000L

        /** Connect-time backlog size: the latest events replayed as `data:` frames. */
        const val REPLAY_EVENTS = 50
    }
}

package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.web.StatusWebHandler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream

/**
 * The SSE status channel: GET /api/events streams the same JSON the /api/status
 * poll answers, once per interval, until the client disconnects. Replaces the
 * dashboard's highest-frequency poll lane with one push connection; MJPEG
 * remains the video path and the poll ladder stays as fallback.
 *
 * The transport (client cap, whole-chunk queue, writer thread, bounded
 * lifetime, retry/bye framing) is the shared [SseClientPump]; this class
 * contributes only the content loop — one status snapshot per interval.
 */
class SseStatusStream(
    private val status: StatusWebHandler,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    private val pump = SseClientPump(writerThreadName = "SseStatusWriter")

    /** Null when the client cap is reached — the transport answers 503. */
    fun open(): InputStream? = pump.open {
        while (isOpen && !lifetimeSpent) {
            val json = try {
                runBlocking {
                    withTimeout(DISPATCH_TIMEOUT_MS) { status.get() }
                }
            } catch (_: Exception) {
                null
            }
            if (json != null) {
                // One SSE event per status snapshot.
                enqueue("event: status\ndata: $json\n\n")
            }
            val deadline = System.currentTimeMillis() + intervalMs
            while (isOpen && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
        }
    }

    private companion object {
        private const val DEFAULT_INTERVAL_MS = 1_000L
        private const val DISPATCH_TIMEOUT_MS = 5_000L
    }
}

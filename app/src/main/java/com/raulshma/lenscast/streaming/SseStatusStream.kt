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
    private val onClientCountChanged: (Int) -> Unit = {},
) {

    private val pump = SseClientPump(
        writerThreadName = "SseStatusWriter",
        onClientCountChanged = onClientCountChanged,
    )

    fun close() = pump.closeAll()

    /**
     * Pushes a one-shot `transport` event telling every open dashboard the
     * scheme of the listener replacing this one. Called before a TLS toggle
     * restarts the transport; the dashboard navigates to the same host, port
     * and path under [scheme]. True when the frame was queued for at least
     * one open client.
     *
     * Waits (bounded) for the response readers to consume the frame, so the
     * caller can tear the listener down without racing the write. A stalled
     * client costs at most [FLUSH_TIMEOUT_MS], not an unbounded lock hold.
     */
    fun notifyTransportSwitch(scheme: String): Boolean {
        // The frame is built by interpolation, so the wire vocabulary is
        // enforced here rather than trusted from the caller.
        if (scheme != "http" && scheme != "https") return false
        val queued = pump.broadcast("event: transport\ndata: {\"scheme\":\"$scheme\"}\n\n")
        if (queued) {
            pump.flush(FLUSH_TIMEOUT_MS)
            // Consumption happens on the response thread just before its
            // socket write; give that write a beat before the listener closes.
            runCatching { Thread.sleep(FLUSH_GRACE_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        return queued
    }

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

        /** Overall budget for open dashboards to consume the switch notice. */
        private const val FLUSH_TIMEOUT_MS = 300L

        /** Post-consumption grace for NanoHTTPD's socket write to land. */
        private const val FLUSH_GRACE_MS = 50L
    }
}

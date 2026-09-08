package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.web.StatusWebHandler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SSE status channel: GET /api/events streams the same JSON the /api/status
 * poll answers, once per interval, until the client disconnects. Replaces the
 * dashboard's highest-frequency poll lane with one push connection; MJPEG
 * remains the video path and the poll ladder stays as fallback.
 *
 * Bounded by design: at most [MAX_CLIENTS] concurrent streams (NanoHTTPD is
 * thread-per-connection), each capped at [MAX_LIFETIME_MS] so a wedged client
 * cannot hold a worker forever.
 */
class SseStatusStream(
    private val status: StatusWebHandler,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    private val clients = AtomicInteger(0)

    /** Null when the client cap is reached — the transport answers 503. */
    fun open(): InputStream? {
        if (clients.getAndIncrement() >= MAX_CLIENTS) {
            clients.decrementAndGet()
            return null
        }
        return try {
            SseEventStream()
        } catch (e: Exception) {
            clients.decrementAndGet()
            null
        }
    }

    private inner class SseEventStream : InputStream() {
        // Whole UTF-8 chunks, not bytes: enqueue drops one event when the
        // reader stalls, instead of walking a per-byte queue.
        private val queue: BlockingQueue<ByteArray> = ArrayBlockingQueue(256)
        private var current: ByteArray = EMPTY_CHUNK
        private var offset = 0
        private val writer = Thread({ writeLoop() }, "SseStatusWriter").apply {
            isDaemon = true
            start()
        }
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun read(): Int {
            // Block until a byte exists or the stream is closed — returning
            // -1 on a silent gap would read as EOF and close the response,
            // putting the client into a reconnect loop between events.
            while (true) {
                if (closed.get()) return -1
                if (offset >= current.size) {
                    val chunk = try {
                        queue.poll(500, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        null
                    } ?: continue
                    current = chunk
                    offset = 0
                    continue
                }
                return current[offset++].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            val first = read()
            if (first == -1) return -1
            b[off] = first.toByte()
            var count = 1
            while (count < len) {
                if (offset >= current.size) {
                    val next = queue.poll(5, TimeUnit.MILLISECONDS) ?: break
                    current = next
                    offset = 0
                    if (current.isEmpty()) break
                }
                val n = minOf(len - count, current.size - offset)
                System.arraycopy(current, offset, b, off + count, n)
                offset += n
                count += n
            }
            return count
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                writer.interrupt()
                clients.decrementAndGet()
            }
        }

        private fun writeLoop() {
            val startMs = System.currentTimeMillis()
            try {
                // Retry hint: the browser's EventSource reconnects automatically.
                enqueue("retry: 3000\n\n")
                while (!closed.get() && System.currentTimeMillis() - startMs < MAX_LIFETIME_MS) {
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
                    while (!closed.get() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                }
                enqueue("event: bye\ndata: {}\n\n")
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        private fun enqueue(text: String) {
            if (text.isEmpty()) return
            // Offer with a timeout so a stalled socket reader cannot wedge
            // the writer forever; dropping the tail of a status stream is
            // always safe.
            queue.offer(text.toByteArray(Charsets.UTF_8), 1, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS = 1_000L
        private const val MAX_CLIENTS = 4
        private const val MAX_LIFETIME_MS = 30 * 60 * 1000L
        private const val DISPATCH_TIMEOUT_MS = 5_000L
        private val EMPTY_CHUNK = ByteArray(0)
    }
}

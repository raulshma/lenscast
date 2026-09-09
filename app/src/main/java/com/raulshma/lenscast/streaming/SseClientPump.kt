package com.raulshma.lenscast.streaming

import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared SSE client transport behind both never-ending chunked streams —
 * the `/api/events` status channel and the `/api/detection/events/stream`
 * detection-event channel: the client cap (the transport answers 503 when
 * full; NanoHTTPD is thread-per-connection), the whole-chunk queue behind a
 * blocking [InputStream], one writer thread per client, close-on-disconnect,
 * the bounded lifetime, and the retry/bye EventSource framing. The content
 * loop is the caller's [SseSession] body — [SseStatusStream] pushes a
 * snapshot per interval, [SseDetectionEventStream] replays a backlog then
 * pushes events with keep-alive pings — so the threading, backpressure, and
 * cancellation model live in exactly one place.
 */
internal class SseClientPump(
    private val writerThreadName: String,
    private val maxClients: Int = DEFAULT_MAX_CLIENTS,
) {

    private val clients = AtomicInteger(0)

    /** Null when the client cap is reached — the transport answers 503. */
    fun open(body: SseSession.() -> Unit): InputStream? {
        if (clients.getAndIncrement() >= maxClients) {
            clients.decrementAndGet()
            return null
        }
        return try {
            SseSession(body)
        } catch (e: Exception) {
            clients.decrementAndGet()
            null
        }
    }

    inner class SseSession(private val body: SseSession.() -> Unit) : InputStream() {
        // Whole UTF-8 chunks, not bytes: enqueue drops one frame when the
        // reader stalls, instead of walking a per-byte queue.
        private val queue: BlockingQueue<ByteArray> = ArrayBlockingQueue(256)
        private var current: ByteArray = EMPTY_CHUNK
        private var offset = 0
        private val closed = AtomicBoolean(false)
        private val startMs = System.currentTimeMillis()
        private val writer = Thread({ runSession() }, writerThreadName).apply {
            isDaemon = true
            start()
        }

        /** False once the client disconnected — every content loop checks it. */
        val isOpen: Boolean
            get() = !closed.get()

        /** True once the session's bounded lifetime is spent. */
        val lifetimeSpent: Boolean
            get() = System.currentTimeMillis() - startMs >= MAX_LIFETIME_MS

        /**
         * Offers one whole frame to the socket reader with a timeout, so a
         * stalled client cannot wedge the writer forever; dropping the tail
         * of an SSE stream is always safe.
         */
        fun enqueue(text: String) {
            if (text.isEmpty()) return
            queue.offer(text.toByteArray(Charsets.UTF_8), 1, TimeUnit.SECONDS)
        }

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

        private fun runSession() {
            try {
                // Retry hint first: the browser's EventSource reconnects
                // automatically on a dropped stream, at this backoff.
                enqueue("retry: 3000\n\n")
                body()
                enqueue("event: bye\ndata: {}\n\n")
            } catch (_: Exception) {
            } finally {
                close()
            }
        }
    }

    companion object {
        /** Both channels cap concurrency the same way; the transport answers 503 when full. */
        const val DEFAULT_MAX_CLIENTS = 4

        private const val MAX_LIFETIME_MS = 30 * 60 * 1000L
        private val EMPTY_CHUNK = ByteArray(0)
    }
}

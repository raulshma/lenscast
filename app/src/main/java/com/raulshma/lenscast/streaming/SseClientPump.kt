package com.raulshma.lenscast.streaming

import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
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
    private val onClientCountChanged: (Int) -> Unit = {},
) {

    private val clients = AtomicInteger(0)
    private val lifecycleLock = Any()

    /** Live sessions, so the server can push one frame to every open client. */
    private val sessions: MutableSet<SseSession> =
        Collections.newSetFromMap(ConcurrentHashMap<SseSession, Boolean>())

    /** Null when the client cap is reached — the transport answers 503. */
    fun open(body: SseSession.() -> Unit): InputStream? = synchronized(lifecycleLock) {
        if (clients.getAndIncrement() >= maxClients) {
            clients.decrementAndGet()
            return@synchronized null
        }
        try {
            val session = SseSession(body)
            onClientCountChanged(clients.get())
            session
        } catch (e: Exception) {
            clients.decrementAndGet()
            onClientCountChanged(clients.get())
            null
        }
    }

    fun closeAll() = synchronized(lifecycleLock) {
        sessions.toList().forEach { it.close() }
    }

    /**
     * Offers [text] to every live session (the transport lifecycle notices:
     * a scheme switch cannot wait for the next content-loop tick). Each offer
     * is bounded and may drop when that client's queue is full — the caller
     * holds the transport lifecycle lock and must not be wedged by one stalled
     * dashboard. True when the frame was queued for at least one client.
     * [flush] observes consumption; this call does not wait for it.
     */
    fun broadcast(text: String): Boolean {
        var delivered = false
        for (session in sessions) {
            if (!session.isOpen) continue
            if (session.offer(text, BROADCAST_OFFER_TIMEOUT_MS)) delivered = true
        }
        return delivered
    }

    /**
     * Waits (bounded by [timeoutMs] overall) for the response readers to
     * consume the frames already queued, so a caller that is about to close
     * the transport can tell "written to the queue" from "handed to the
     * HTTP response thread". False when at least one live session still had
     * bytes queued at the deadline — a stalled dashboard.
     */
    fun flush(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        for (session in sessions) {
            if (!session.isOpen) continue
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            if (!session.awaitDrained(remaining)) return false
        }
        return true
    }

    inner class SseSession(private val body: SseSession.() -> Unit) : InputStream() {
        // Whole UTF-8 chunks, not bytes: enqueue drops one frame when the
        // reader stalls, instead of walking a per-byte queue.
        private val queue: BlockingQueue<ByteArray> = ArrayBlockingQueue(256)
        private var current: ByteArray = EMPTY_CHUNK
        private var offset = 0
        private val closed = AtomicBoolean(false)
        private val startMs = System.currentTimeMillis()
        private val pendingChunks = AtomicInteger(0)

        private val writer = Thread({ runSession() }, writerThreadName).apply {
            isDaemon = true
        }

        init {
            // Register before the writer starts: a body that returns at once
            // would otherwise close an untracked session. If the thread cannot
            // start, unregister before propagating so the broadcast set never
            // holds a session no writer will ever drain.
            sessions.add(this)
            try {
                writer.start()
            } catch (t: Throwable) {
                sessions.remove(this)
                throw t
            }
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
            offer(text, ENQUEUE_TIMEOUT_MS)
        }

        /**
         * Bounded offer for out-of-band frames: false when the client's queue
         * stayed full for [timeoutMs], so a caller holding a lifecycle lock
         * pays a bounded price and knows whether the frame was handed over.
         */
        fun offer(text: String, timeoutMs: Long): Boolean {
            if (text.isEmpty()) return false
            // Convert before counting: a throw here must not leave a phantom
            // pending chunk that [awaitDrained] would wait on.
            val bytes = text.toByteArray(Charsets.UTF_8)
            // Count before publishing: the reader may consume the chunk the
            // instant it lands in the queue, and a late increment would leave
            // [awaitDrained] waiting on a frame that is already on the wire.
            pendingChunks.incrementAndGet()
            val queued = try {
                queue.offer(bytes, timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!queued) pendingChunks.decrementAndGet()
            return queued
        }

        /**
         * Waits until the response reader has consumed every queued frame, or
         * [timeoutMs] elapses. Consumption still precedes NanoHTTPD's socket
         * write, so callers leave a short grace before closing the transport.
         */
        fun awaitDrained(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (pendingChunks.get() > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                try {
                    Thread.sleep(minOf(remaining, DRAIN_POLL_MS))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
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
                val value = current[offset++].toInt() and 0xFF
                if (offset >= current.size) pendingChunks.decrementAndGet()
                return value
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
                if (offset >= current.size) pendingChunks.decrementAndGet()
            }
            return count
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                sessions.remove(this)
                writer.interrupt()
                synchronized(lifecycleLock) {
                    onClientCountChanged(clients.decrementAndGet())
                }
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

        /** Content-loop frames: a stalled reader drops one frame, not the stream. */
        private const val ENQUEUE_TIMEOUT_MS = 1_000L

        /**
         * Out-of-band broadcast frames (the TLS switch notice) wait only
         * briefly per client: the caller holds the server lifecycle lock, so
         * one stalled dashboard must not pin the port apply behind it.
         */
        private const val BROADCAST_OFFER_TIMEOUT_MS = 100L

        /** Poll cadence of [SseSession.awaitDrained]. */
        private const val DRAIN_POLL_MS = 5L

        private const val MAX_LIFETIME_MS = 30 * 60 * 1000L
        private val EMPTY_CHUNK = ByteArray(0)
    }
}

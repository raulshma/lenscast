package com.raulshma.lenscast.streaming

import java.io.InputStream
import java.util.ArrayDeque

/**
 * The RTSP audio subscriber pipe: the bounded drop-oldest backpressure
 * handoff between the mic reader thread and one subscriber's [InputStream]
 * — the bytes [com.raulshma.lenscast.streaming.rtsp.AacEncoder] blocks on.
 * Pure concurrency logic, no Android types, so the whole backpressure
 * contract is JVM-tested: [enqueue] never blocks (once [capacity] chunks
 * are pending the oldest is dropped), [read] blocks until a chunk or
 * [shutdown] (EOF), and [close] runs the owner's [onClose] bookkeeping and
 * [shutdown] — idempotent, like the client stream it replaces.
 */
class AudioSubscriberPipe(
    /** Pending-chunk bound; the oldest queued chunk is dropped past it. */
    private val capacity: Int = DEFAULT_CAPACITY,
    /** Owner bookkeeping run when the subscriber closes the stream. */
    private val onClose: () -> Unit = {},
) : InputStream() {

    private val lock = Object()
    private val pendingChunks = ArrayDeque<ByteArray>()

    private var currentChunk: ByteArray? = null
    private var currentOffset = 0
    private var closed = false

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val read = read(singleByte, 0, 1)
        return if (read <= 0) -1 else singleByte[0].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset + length > target.size) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0

        synchronized(lock) {
            while (true) {
                val chunk = currentChunk
                if (chunk != null && currentOffset < chunk.size) {
                    val toCopy = minOf(length, chunk.size - currentOffset)
                    System.arraycopy(chunk, currentOffset, target, offset, toCopy)
                    currentOffset += toCopy
                    if (currentOffset >= chunk.size) {
                        currentChunk = null
                        currentOffset = 0
                    }
                    return toCopy
                }

                if (pendingChunks.isNotEmpty()) {
                    currentChunk = pendingChunks.removeFirst()
                    currentOffset = 0
                    continue
                }

                if (closed) {
                    return -1
                }

                lock.wait()
            }
        }
    }

    fun enqueue(chunk: ByteArray) {
        synchronized(lock) {
            if (closed) return
            while (pendingChunks.size >= capacity) {
                pendingChunks.removeFirst()
            }
            pendingChunks.addLast(chunk)
            lock.notifyAll()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            closed = true
            pendingChunks.clear()
            currentChunk = null
            currentOffset = 0
            lock.notifyAll()
        }
    }

    override fun close() {
        onClose()
        shutdown()
    }

    companion object {
        private const val DEFAULT_CAPACITY = 6
    }
}

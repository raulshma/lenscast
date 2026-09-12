package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Transport pass over the shared [SseClientPump] behind both never-ending
 * chunked streams (`/api/events` and `/api/detection/events/stream`): the
 * client cap the transport answers 503 with, the whole-chunk queue behind a
 * blocking [InputStream], the retry framing, the blocking read contract, and
 * close-on-disconnect freeing the cap slot. Every session body is gated on a
 * latch, mirroring the never-ending content loops the pump serves — a body
 * that returns would close the session and free its slot mid-test.
 *
 * **Not coverable here:** the queue-full drop path (each dropped frame pays a
 * 1 s `offer` timeout — refilling the 256-slot queue until a frame drops
 * would make this a minutes-long test) and the 30-minute bounded lifetime
 * (`System.currentTimeMillis` is read directly, no injectable clock).
 */
class SseClientPumpTest {

    /**
     * Reads exactly [count] bytes off the stream on a worker thread with a
     * wall-clock cap, so a framing regression fails the test instead of
     * hanging the suite on the blocking read.
     */
    private fun readFully(stream: InputStream, count: Int, timeoutSeconds: Long = 5): String {
        val task = FutureTask {
            val out = ByteArrayOutputStream()
            while (out.size() < count) {
                val b = stream.read()
                if (b == -1) break
                out.write(b)
            }
            out.toByteArray()
        }
        Thread(task).apply { isDaemon = true }.start()
        val bytes = task.get(timeoutSeconds, TimeUnit.SECONDS)
        assertEquals("timed out after $timeoutSeconds s with ${bytes.size} of $count bytes", count, bytes.size)
        return String(bytes, Charsets.UTF_8)
    }

    /** Opens a session whose content loop stays alive until the latch fires. */
    private fun openLiveSession(pump: SseClientPump, gate: CountDownLatch, vararg frames: String): InputStream {
        val stream = pump.open {
            frames.forEach { enqueue(it) }
            gate.await(10, TimeUnit.SECONDS)
        }
        assertNotNull(stream)
        return stream!!
    }

    // ── client cap ──

    @Test
    fun `open hands out live sessions up to the cap then answers null for the 503`() {
        val pump = SseClientPump(writerThreadName = "t", maxClients = SseClientPump.DEFAULT_MAX_CLIENTS)
        val gates = List(SseClientPump.DEFAULT_MAX_CLIENTS) { CountDownLatch(1) }
        val streams = gates.map { openLiveSession(pump, it) }
        assertNull("a fifth live client must be refused (the transport's 503)", pump.open { })
        streams.forEach { it.close() }
    }

    @Test
    fun `closing one client frees its cap slot`() {
        val pump = SseClientPump(writerThreadName = "t", maxClients = 2)
        val gate = CountDownLatch(1)
        val a = openLiveSession(pump, gate)
        openLiveSession(pump, CountDownLatch(1))
        assertNull(pump.open { })
        a.close()
        val freed = pump.open { }
        assertNotNull("the closed client's slot must be reusable", freed)
        freed!!.close()
        gate.countDown()
    }

    @Test
    fun `session close is idempotent and releases exactly one slot`() {
        val pump = SseClientPump(writerThreadName = "t", maxClients = 1)
        val gate = CountDownLatch(1)
        val session = openLiveSession(pump, gate) as SseClientPump.SseSession
        assertTrue(session.isOpen)
        session.close()
        session.close()
        val freed = pump.open { }
        assertNotNull(freed)
        freed!!.close()
        assertFalse(session.isOpen)
        gate.countDown()
    }

    // ── framing ──

    @Test
    fun `session stream opens with the EventSource retry hint then delivers whole chunks`() {
        val pump = SseClientPump(writerThreadName = "t")
        val gate = CountDownLatch(1)
        // The retry hint is enqueued by the writer before the body runs; the
        // gated body keeps the session open while the reader drains.
        val stream = pump.open {
            enqueue("event: status\ndata: {\"ok\":1}\n\n")
            enqueue("data: two\n\n")
            gate.await(10, TimeUnit.SECONDS)
        }!!
        assertEquals("retry: 3000\n\n", readFully(stream, "retry: 3000\n\n".length))
        val expected = "event: status\ndata: {\"ok\":1}\n\ndata: two\n\n"
        assertEquals(expected, readFully(stream, expected.length))
        gate.countDown()
    }

    @Test
    fun `empty enqueues are dropped and utf-8 survives`() {
        val pump = SseClientPump(writerThreadName = "t")
        val gate = CountDownLatch(1)
        val stream = pump.open {
            enqueue("")
            enqueue("data:温度\n\n")
            gate.await(10, TimeUnit.SECONDS)
        }!!
        assertEquals("retry: 3000\n\n", readFully(stream, "retry: 3000\n\n".length))
        val expected = "data:温度\n\n"
        assertEquals(expected, readFully(stream, expected.toByteArray(Charsets.UTF_8).size))
        gate.countDown()
    }

    @Test
    fun `the writer closes the session after the body returns`() {
        val pump = SseClientPump(writerThreadName = "t")
        val session = pump.open { } as SseClientPump.SseSession
        val closed = CountDownLatch(1)
        Thread {
            while (session.isOpen) Thread.sleep(5)
            closed.countDown()
        }.apply { isDaemon = true }.start()
        assertTrue("writer never closed the session", closed.await(5, TimeUnit.SECONDS))
        assertFalse(session.isOpen)
    }

    // ── blocking read contract ──

    @Test
    fun `read blocks until data arrives instead of answering a premature EOF`() {
        val pump = SseClientPump(writerThreadName = "t")
        val gate = CountDownLatch(1)
        val stream = pump.open {
            gate.await(10, TimeUnit.SECONDS)
        }!!

        // Drain the writer's opening retry hint, then park a second read
        // while the content loop is alive and the queue is empty: the read
        // must block, never answer a premature -1 (that would read as EOF and
        // drop the response into a reconnect loop between events).
        assertEquals("retry: 3000\n\n", readFully(stream, "retry: 3000\n\n".length))
        val nextByte = FutureTask { stream.read() }
        Thread(nextByte).apply { isDaemon = true }.start()
        Thread.sleep(150)
        assertFalse("read() answered EOF before any data", nextByte.isDone)
        gate.countDown()
        stream.close()
    }
}

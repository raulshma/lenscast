package com.raulshma.lenscast.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioSubscriberPipeTest {

    @Test
    fun `enqueue then read returns the chunks in order`() {
        val pipe = AudioSubscriberPipe()
        pipe.enqueue(byteArrayOf(1, 2, 3))
        pipe.enqueue(byteArrayOf(4, 5))

        // A short read stops at the chunk boundary; the next read resumes
        // mid-chunk and walks across into the queued chunk.
        val head = ByteArray(2)
        assertEquals(2, pipe.read(head, 0, 2))
        assertArrayEquals(byteArrayOf(1, 2), head)
        assertEquals(3, pipe.read())
        assertEquals(4, pipe.read())
        assertEquals(5, pipe.read())

        // read() is the unsigned byte value, like any InputStream.
        pipe.enqueue(byteArrayOf(-1))
        assertEquals(255, pipe.read())
    }

    @Test
    fun `enqueue past the bound drops the oldest chunk`() {
        val pipe = AudioSubscriberPipe(capacity = 3)
        for (chunk in 1..5) {
            pipe.enqueue(byteArrayOf(chunk.toByte()))
        }

        assertEquals(3, pipe.read())
        assertEquals(4, pipe.read())
        assertEquals(5, pipe.read())
    }

    @Test
    fun `read blocks until a chunk is enqueued`() {
        val pipe = AudioSubscriberPipe()
        val reading = CountDownLatch(1)
        val result = AtomicInteger(-2)

        val reader = Thread {
            reading.countDown()
            result.set(pipe.read())
        }
        reader.isDaemon = true
        reader.start()
        assertTrue(reading.await(5, TimeUnit.SECONDS))

        // Bounded probe: no chunk yet, so the reader is still parked.
        reader.join(250)
        assertTrue(reader.isAlive)

        pipe.enqueue(byteArrayOf(7))
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertEquals(7, result.get())
    }

    @Test
    fun `shutdown ends a blocked read with EOF`() {
        val pipe = AudioSubscriberPipe()
        val reading = CountDownLatch(1)
        val result = AtomicInteger(-2)

        val reader = Thread {
            reading.countDown()
            result.set(pipe.read())
        }
        reader.isDaemon = true
        reader.start()
        assertTrue(reading.await(5, TimeUnit.SECONDS))

        pipe.shutdown()
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertEquals(-1, result.get())
    }

    @Test
    fun `read reports EOF after shutdown and ignores late enqueues`() {
        val pipe = AudioSubscriberPipe()
        pipe.enqueue(byteArrayOf(1))
        pipe.shutdown()

        // Shutdown drops everything pending and every later read is EOF.
        assertEquals(-1, pipe.read())
        pipe.enqueue(byteArrayOf(2))
        assertEquals(-1, pipe.read(ByteArray(1)))
        assertEquals(-1, pipe.read())
    }

    @Test
    fun `close is idempotent`() {
        val pipe = AudioSubscriberPipe()
        pipe.enqueue(byteArrayOf(1, 2))

        pipe.close()
        pipe.close()

        assertEquals(-1, pipe.read())
    }

    @Test
    fun `read rejects out-of-bounds arguments`() {
        val pipe = AudioSubscriberPipe()
        val target = ByteArray(2)

        try {
            pipe.read(target, 0, 3)
            fail("read past the target end must throw")
        } catch (_: IndexOutOfBoundsException) {
        }
        try {
            pipe.read(target, -1, 1)
            fail("negative offset must throw")
        } catch (_: IndexOutOfBoundsException) {
        }
        assertEquals(0, pipe.read(target, 0, 0))
    }

    @Test
    fun `interleaved producer and consumer pass the bytes through`() {
        val pipe = AudioSubscriberPipe()
        val chunks = 6
        val chunkSize = 4
        val total = chunks * chunkSize

        val received = ByteArray(total)
        val consumed = CountDownLatch(1)
        val consumer = Thread {
            var offset = 0
            while (offset < total) {
                val read = pipe.read(received, offset, total - offset)
                if (read < 0) break
                offset += read
            }
            consumed.countDown()
        }
        consumer.isDaemon = true
        consumer.start()

        // The consumer is parked when the first chunks land and still
        // draining when the last ones do; at most `chunks` chunks are ever
        // in flight, so nothing is dropped no matter the interleaving.
        for (chunk in 0 until chunks) {
            pipe.enqueue(ByteArray(chunkSize) { (chunk * chunkSize + it).toByte() })
        }

        assertTrue(consumed.await(5, TimeUnit.SECONDS))
        assertArrayEquals(ByteArray(total) { it.toByte() }, received)
    }
}

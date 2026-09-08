package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MediaCodecEncoderHarnessTest {

    /**
     * Scripted [CodecLike] fake: lifecycle calls are recorded in [events],
     * and each dequeueOutput pops the next scripted step — defaulting to
     * TRY_AGAIN with a 1ms sleep so an idle drain loop doesn't spin hot.
     */
    private class FakeCodec(private val startError: Exception? = null) : CodecLike {

        val events: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        var configureCount = 0
        var startCount = 0
        var stopCount = 0
        var releaseCount = 0
        var outputBufferNull = false

        private val outputScript = LinkedBlockingQueue<(MediaCodecEncoderHarness.OutputInfo) -> Int>()
        private val dequeueAttempts = AtomicInteger(0)

        fun scriptBuffer(index: Int, size: Int = 4, flags: Int = 0) {
            outputScript.put { info ->
                info.size = size
                info.offset = 0
                info.flags = flags
                info.presentationTimeUs = 1_000L * (index + 1)
                index
            }
        }

        fun scriptFormatChanged() {
            outputScript.put { MediaCodec.INFO_OUTPUT_FORMAT_CHANGED }
        }

        fun scriptError(error: Exception) {
            outputScript.put { throw error }
        }

        /** Blocks the drain loop inside dequeueOutput until [gate] opens. */
        fun scriptBlocked(gate: CountDownLatch, onBlocked: () -> Unit = {}) {
            outputScript.put {
                onBlocked()
                gate.await()
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }

        // Counted atomically: the test polls this while the drain thread runs.
        fun dequeueCount(): Int = dequeueAttempts.get()

        override fun dequeueOutput(info: MediaCodecEncoderHarness.OutputInfo, timeoutUs: Long): Int {
            dequeueAttempts.incrementAndGet()
            events.add("dequeue")
            val step = outputScript.poll()
            if (step == null) {
                Thread.sleep(1)
                return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            return step(info)
        }

        override fun getOutputBuffer(index: Int): ByteBuffer? {
            if (outputBufferNull) return null
            return ByteBuffer.wrap(byteArrayOf(0x0B, 0x00, 0x00, 0x01))
        }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            events.add("release:$index")
        }

        override fun configureEncode(format: MediaFormat) {
            recordConfigure()
        }

        /** The harness-visible configure step, so tests can pin the ladder order. */
        fun recordConfigure() {
            configureCount++
            events.add("configure")
        }

        override fun start() {
            startCount++
            events.add("codec-start")
            startError?.let { throw it }
        }

        override fun stop() {
            stopCount++
            events.add("codec-stop")
        }

        override fun release() {
            releaseCount++
            events.add("codec-release")
        }

        override fun supportedColorFormats(mimeType: String): Set<Int> = emptySet()

        override fun isFeatureSupported(mimeType: String, feature: String): Boolean = false

        override fun dequeueInputBuffer(timeoutUs: Long): Int = -1

        override fun getInputBuffer(index: Int): ByteBuffer? = null

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}

        override fun setParameters(params: Bundle) {}

        // MediaFormat has no JVM-constructible instance, and the harness tests
        // only assert that the format-changed event routes the codec handle —
        // the format itself is never read.
        override fun outputFormat(): MediaFormat = error("outputFormat not expected in harness tests")
    }

    private fun harnessFor(
        codec: FakeCodec,
        createError: Exception? = null,
        onOutput: ((ByteBuffer, MediaCodecEncoderHarness.OutputInfo) -> Unit)? = null,
        onFormatChanged: ((CodecLike) -> Unit)? = null,
    ): MediaCodecEncoderHarness {
        return MediaCodecEncoderHarness(
            tag = "HarnessTest",
            threadName = "HarnessTestOutput",
            startedMessage = { "harness started" },
            startFailureMessage = "harness start failed",
            outputErrorMessage = "harness output error",
            createCodec = {
                if (createError != null) throw createError
                codec
            },
            configureCodec = { codecLike -> (codecLike as FakeCodec).recordConfigure() },
            onFormatChanged = { onFormatChanged?.invoke(it) },
            onOutput = { buffer, info -> onOutput?.invoke(buffer, info) },
        )
    }

    /** Waits until the drain thread has made [count] dequeue attempts. */
    private fun awaitDequeueAttempts(codec: FakeCodec, count: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (codec.dequeueCount() >= count) return true
            Thread.sleep(5)
        }
        return false
    }

    // ── start ladder ──

    @Test
    fun `start runs the create-configure-start ladder once and reports success`() {
        val codec = FakeCodec()
        val harness = harnessFor(codec)

        assertTrue(harness.start())

        assertEquals(1, codec.configureCount)
        assertEquals(1, codec.startCount)
        assertTrue(harness.isRunning)
        assertSame(codec, harness.activeCodec)

        // The ladder order: configuration lands between create and start.
        val configureAt = codec.events.indexOf("configure")
        val startAt = codec.events.indexOf("codec-start")
        assertTrue(configureAt in 0 until startAt)

        harness.stop()
    }

    @Test
    fun `double start is a no-op`() {
        val codec = FakeCodec()
        val harness = harnessFor(codec)

        assertTrue(harness.start())
        assertTrue(harness.start())

        assertEquals(1, codec.configureCount)
        assertEquals(1, codec.startCount)

        harness.stop()
    }

    @Test
    fun `restart after stop re-runs the ladder`() {
        val codec = FakeCodec()
        val harness = harnessFor(codec)

        assertTrue(harness.start())
        assertTrue(harness.stop())
        assertTrue(harness.start())

        assertEquals(2, codec.startCount)
        assertEquals(1, codec.releaseCount)

        harness.stop()
    }

    @Test
    fun `start failure flips the running guard off so stop stays safe`() {
        val codec = FakeCodec(startError = IllegalStateException("configure rejected"))
        val harness = harnessFor(codec)

        assertFalse(harness.start())
        assertFalse(harness.isRunning)
        assertNull(harness.activeCodec)

        // stop after a failed start is a no-op — the codec is never touched.
        assertFalse(harness.stop())
        assertEquals(0, codec.stopCount)
        assertEquals(0, codec.releaseCount)
    }

    @Test
    fun `create failure flips the running guard off too`() {
        val codec = FakeCodec()
        val harness = harnessFor(codec, createError = RuntimeException("no encoder"))

        assertFalse(harness.start())
        assertFalse(harness.isRunning)

        assertFalse(harness.stop())
    }

    // ── drain loop ──

    @Test
    fun `output buffers route to onOutput and are released after the callback`() {
        val codec = FakeCodec()
        val delivered = CountDownLatch(1)
        val received = AtomicReference<Pair<ByteBuffer, MediaCodecEncoderHarness.OutputInfo>?>(null)
        codec.scriptBuffer(index = 3, size = 2, flags = MediaCodec.BUFFER_FLAG_KEY_FRAME)

        val harness = harnessFor(codec, onOutput = { buffer, info ->
            codec.events.add("onOutput")
            received.set(buffer to info)
            delivered.countDown()
        })

        assertTrue(harness.start())
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        harness.stop()

        val (buffer, info) = received.get()!!
        assertEquals(4, buffer.remaining())
        assertEquals(2, info.size)
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, info.flags)

        // Release happens only after the callback ran.
        val onOutputAt = codec.events.indexOf("onOutput")
        val releaseAt = codec.events.indexOf("release:3")
        assertTrue(onOutputAt in 0 until releaseAt)
    }

    @Test
    fun `codec-config buffers also route to onOutput - the encoder decides what they mean`() {
        val codec = FakeCodec()
        val delivered = CountDownLatch(1)
        val flagsSeen = AtomicInteger(0)
        codec.scriptBuffer(index = 0, size = 4, flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG)

        val harness = harnessFor(codec, onOutput = { _, info ->
            flagsSeen.set(info.flags)
            delivered.countDown()
        })

        assertTrue(harness.start())
        assertTrue(delivered.await(5, TimeUnit.SECONDS))

        assertEquals(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, flagsSeen.get())

        harness.stop()
    }

    @Test
    fun `a null output buffer skips callback and release mirroring the encoder continue paths`() {
        val codec = FakeCodec()
        codec.outputBufferNull = true
        codec.scriptBuffer(index = 1)
        var callbackInvocations = 0

        val harness = harnessFor(codec, onOutput = { _, _ -> callbackInvocations++ })

        assertTrue(harness.start())
        // Two iterations: the scripted null-buffer one, then an idle poll.
        assertTrue(awaitDequeueAttempts(codec, 2))
        harness.stop()

        assertEquals(0, callbackInvocations)
        assertEquals(0, codec.events.count { it.startsWith("release:") })
    }

    @Test
    fun `format changed routes to onFormatChanged without a buffer release`() {
        val codec = FakeCodec()
        val formatChanged = CountDownLatch(1)
        val received = AtomicReference<CodecLike?>(null)
        codec.scriptFormatChanged()

        val harness = harnessFor(codec, onFormatChanged = { codecLike ->
            received.set(codecLike)
            formatChanged.countDown()
        })

        assertTrue(harness.start())
        assertTrue(formatChanged.await(5, TimeUnit.SECONDS))
        assertSame(codec, received.get())
        harness.stop()

        assertEquals(0, codec.events.count { it.startsWith("release:") })
    }

    @Test
    fun `an IllegalStateException from dequeue breaks the drain loop`() {
        val codec = FakeCodec()
        codec.scriptError(IllegalStateException("codec in wrong state"))

        val harness = harnessFor(codec)

        assertTrue(harness.start())
        assertTrue(awaitDequeueAttempts(codec, 1))
        harness.stop()

        // Exactly one dequeue attempt: the ladder logged and broke, it did not loop.
        assertEquals(1, codec.dequeueCount())
    }

    @Test
    fun `any other exception from dequeue also breaks the drain loop`() {
        val codec = FakeCodec()
        codec.scriptError(RuntimeException("boom"))

        val harness = harnessFor(codec)

        assertTrue(harness.start())
        assertTrue(awaitDequeueAttempts(codec, 1))
        harness.stop()

        assertEquals(1, codec.dequeueCount())
    }

    // ── teardown ──

    @Test
    fun `stop waits for the drain thread before stopping and releasing the codec`() {
        val codec = FakeCodec()
        val drainBlocked = CountDownLatch(1)
        val gate = CountDownLatch(1)
        codec.scriptBlocked(gate) { drainBlocked.countDown() }

        val harness = harnessFor(codec)
        assertTrue(harness.start())
        assertTrue(drainBlocked.await(5, TimeUnit.SECONDS))

        val stopped = CountDownLatch(1)
        Thread {
            harness.stop()
            stopped.countDown()
        }.start()

        // The drain thread is parked inside dequeueOutput: stop must still be waiting.
        assertFalse(stopped.await(250, TimeUnit.MILLISECONDS))
        assertEquals(0, codec.releaseCount)

        gate.countDown()
        assertTrue(stopped.await(5, TimeUnit.SECONDS))

        // The codec is stopped before it is released, after the drain thread is gone.
        assertEquals(1, codec.releaseCount)
        val codecStopAt = codec.events.indexOf("codec-stop")
        val codecReleaseAt = codec.events.indexOf("codec-release")
        assertTrue(codecStopAt in 0 until codecReleaseAt)
    }

    @Test
    fun `requestStop ends the loops without teardown - the aac end-of-stream path`() {
        val codec = FakeCodec()
        val harness = harnessFor(codec)

        assertTrue(harness.start())
        harness.requestStop()

        // The guard is already off, so stop() does nothing — pinning the
        // pre-harness end-of-stream behavior exactly.
        assertFalse(harness.stop())
        assertEquals(0, codec.releaseCount)
    }

    // ── shared MediaCodec timings ──

    @Test
    fun `the dequeue poll and teardown join keep the encoders original timings`() {
        assertEquals(10_000L, MediaCodecEncoderHarness.DEQUEUE_TIMEOUT_US)
        assertEquals(3_000L, MediaCodecEncoderHarness.STOP_JOIN_TIMEOUT_MS)
    }
}

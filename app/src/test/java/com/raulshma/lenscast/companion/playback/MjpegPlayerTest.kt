package com.raulshma.lenscast.companion.playback

import android.graphics.Bitmap
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The MJPEG rung's decode stage, two layers deep:
 *
 *  - [BitmapFrameDecoder] against REAL BitmapFactory work (Robolectric
 *    native graphics): the fixtures are compressed bitmaps, so the inBitmap
 *    pool-of-1 reuse, the dimension-mismatch fallback, and the garbage
 *    refusal are all exercised on the genuine decode path;
 *  - the player pipeline's decisions against the same multipart fixture
 *    style as MjpegFrameSourceTest (the server pump's exact part shape), a
 *    fake clock, and — where record-order matters — a gated decode that
 *    doubles as the ordering barrier on real threads. NO
 *    kotlinx-coroutines-test virtual time (it false-passes beside real
 *    threads; see MjpegFrameSourceTest's header note).
 *
 * The budget layer is pinned at the engine's own seams: escalation verdicts
 * surface through the injected pauseMs, exhaustion lands as the terminal
 * [MjpegState.Failed], and a delivered frame resets the count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MjpegPlayerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private companion object {
        const val BOUNDARY = "LensCastBoundary"
        const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"
    }

    // ── fixtures ──

    /** A genuinely decodable JPEG (a compressed bitmap), not a marker-wrapped fake. */
    private fun realJpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** The server pump's exact multipart shape (MjpegStreamPump.buildPartHeader). */
    private fun multipartBody(vararg jpegs: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        jpegs.forEachIndexed { index, jpeg ->
            if (index > 0) bytes.write("\r\n".toByteArray())
            bytes.write("--$BOUNDARY\r\n".toByteArray())
            bytes.write("Content-Type: image/jpeg\r\n".toByteArray())
            bytes.write("Content-Length: ${jpeg.size}\r\n".toByteArray())
            bytes.write("\r\n".toByteArray())
            bytes.write(jpeg)
            bytes.write("\r\n".toByteArray())
        }
        return bytes.toByteArray()
    }

    /** The D-style fake: SOI/EOI framed, undecodable contents. */
    private fun fakeJpeg(size: Int = 16): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte()
        bytes[size - 2] = 0xFF.toByte(); bytes[size - 1] = 0xD9.toByte()
        return bytes
    }

    private fun device() = CompanionDevice(
        id = "mjpeg-device",
        name = "Mjpeg Device",
        host = "127.0.0.1",
        port = server.port,
        auth = CompanionAuthKind.TOKEN,
        role = "admin",
        addedAtMs = 0L,
    )

    // ── the decoder, direct ──

    @Test
    fun `decode produces a frame with the encoded dimensions`() = runBlocking {
        val decoder = BitmapFrameDecoder()
        val frame = decoder.decode(realJpeg(8, 6, 0xFF0000), sequence = 7, atMs = 42, reuse = null)
        assertNotNull(frame)
        assertEquals(8, frame!!.bitmap.width)
        assertEquals(6, frame.bitmap.height)
        assertEquals(7L, frame.sequence)
        assertEquals(42L, frame.atMs)
    }

    @Test
    fun `decode reuses the pooled bitmap in place on matching dimensions`() = runBlocking {
        val decoder = BitmapFrameDecoder()
        val pooled = decoder.decode(realJpeg(8, 6, 0x00FF00), sequence = 0, atMs = 0, reuse = null)!!.bitmap
        val next = decoder.decode(realJpeg(8, 6, 0x0000FF), sequence = 1, atMs = 1, reuse = pooled)!!
        assertSame("the pool-of-1 decodes INTO the previous bitmap", pooled, next.bitmap)
    }

    @Test
    fun `decode falls back to a fresh bitmap when the pool dimensions mismatch`() = runBlocking {
        val decoder = BitmapFrameDecoder()
        val pooled = decoder.decode(realJpeg(8, 6, 0x101010), sequence = 0, atMs = 0, reuse = null)!!.bitmap
        val next = decoder.decode(realJpeg(4, 3, 0x202020), sequence = 1, atMs = 1, reuse = pooled)!!
        assertNotSame("a resolution change invalidates the pool entry", pooled, next.bitmap)
        assertEquals(4, next.bitmap.width)
        assertEquals(3, next.bitmap.height)
    }

    @Test
    fun `a garbage payload decodes to null and is skipped`() = runBlocking {
        val decoder = BitmapFrameDecoder()
        assertNull(decoder.decode(fakeJpeg(), sequence = 0, atMs = 0, reuse = null))
        assertNull(decoder.decode("not jpeg at all".toByteArray(), sequence = 0, atMs = 0, reuse = null))
    }

    // ── the player pipeline ──

    private class StreamExhausted : IOException("no more test streams")

    /**
     * One raw-closure answer, mimicking the production transport's contract:
     * return a stream ONLY for a real multipart body — a non-multipart 200
     * (or any failure) must THROW, or the parser would spin on it.
     */
    private class Entry(
        val stream: ByteArray? = null,
        val failure: IOException? = null,
        /**
         * When set, [open] parks until the test completes this gate before
         * throwing [failure] — the ordering barrier that pins
         * recordSuccess-before-next-error against the conflated source's
         * independent pump coroutine.
         */
        val armBeforeFailing: CompletableDeferred<Unit>? = null,
    ) {
        suspend fun open(): MjpegStream {
            armBeforeFailing?.await()
            failure?.let { throw it }
            return MjpegStream(ByteArrayInputStream(stream!!), CONTENT_TYPE)
        }
    }

    private fun nonMultipartFailure(): IOException =
        IOException("MJPEG answer is not multipart (type: text/html)")

    /**
     * A decode seam that parks each decode on a test-held gate — the
     * ordering barrier that makes recordSuccess-before-next-error
     * observable without virtual time.
     */
    private class GatedDecoder : MjpegFrameDecoder {
        val requested = Channel<Long>(Channel.UNLIMITED)
        val releases = Channel<Bitmap?>(Channel.UNLIMITED)

        override suspend fun decode(jpeg: ByteArray, sequence: Long, atMs: Long, reuse: Bitmap?): DecodedFrame? {
            requested.trySend(sequence)
            val bitmap = releases.receive() // the test's explicit go-ahead
            return bitmap?.let { DecodedFrame(it, sequence, atMs) }
        }
    }

    /**
     * The engine harness. `unconfined = true` traces the budget loop
     * synchronously on the test thread (fail-only sequences, where
     * record-order across threads never comes into play); `unconfined =
     * false` runs on the real IO pool so the source's yield lets the
     * collector apply recordSuccess between the pump's reconnects.
     */
    private class Harness(
        private val entries: List<Entry>,
        budget: RetryBudget = RetryBudget(),
        decoder: MjpegFrameDecoder = BitmapFrameDecoder(),
        unconfined: Boolean = true,
    ) {
        var clock = 0L
        val delays = mutableListOf<Long>()

        /** Signals one recorded delay each — the backoff barrier for async runs. */
        val delaySignal = Channel<Long>(Channel.UNLIMITED)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val openCalls = AtomicInteger()
        private val cursor = AtomicInteger()

        val player = MjpegPlayer(
            openStream = {
                openCalls.incrementAndGet()
                entries.getOrNull(cursor.getAndIncrement())?.open() ?: throw StreamExhausted()
            },
            decoder = decoder,
            scope = CoroutineScope(
                SupervisorJob() + (if (unconfined) Dispatchers.Unconfined else Dispatchers.IO),
            ),
            decodeDispatcher = if (unconfined) Dispatchers.Unconfined else Dispatchers.IO,
            clockMs = { clock },
            pauseMs = { ms ->
                delays.add(ms)
                delaySignal.trySend(ms)
                clock += ms
                ticks.receive()
            },
            budget = budget,
        )

        fun tick(n: Int = 1) {
            repeat(n) { check(ticks.trySend(Unit).isSuccess) }
        }

        suspend fun awaitTerminal(): MjpegState =
            withTimeout(10_000) {
                player.state.first { it is MjpegState.Failed || it == MjpegState.Idle }
            }
    }

    @Test
    fun `multipart frames publish live through the real decoder`() = runBlocking {
        val gated = GatedDecoder()
        val h = Harness(
            entries = listOf(Entry(multipartBody(realJpeg(8, 6, 0x111111))), Entry(multipartBody(realJpeg(8, 6, 0x222222)))),
            decoder = gated,
            unconfined = false, // real threads: the decode gates order every beat
        )
        h.player.start()

        // Frame a reached the decode stage: the rung is Live at that beat.
        withTimeout(10_000) { assertEquals(0L, gated.requested.receive()) }
        assertEquals(MjpegState.Live, h.player.state.value)
        gated.releases.trySend(Bitmap.createBitmap(8, 6, Bitmap.Config.RGB_565))

        // Frame b follows once the first decode released the collector.
        withTimeout(10_000) { assertEquals(1L, gated.requested.receive()) }
        gated.releases.trySend(Bitmap.createBitmap(8, 6, Bitmap.Config.RGB_565))
        val newest = withTimeout(10_000) { h.player.frame.first { it != null && it.sequence == 1L } }
        assertEquals(8, newest!!.bitmap.width)

        // The second stream ended at EOF — one budget error is pending — and
        // a stop settles the rung while keeping the last frame for the UI.
        h.player.stop()
        assertEquals(MjpegState.Idle, h.player.state.value)
        assertNotNull(h.player.frame.value)
    }

    @Test
    fun `a delivered frame resets the budget escalation`() = runBlocking {
        val gated = GatedDecoder()
        // The conflated source's pump is an independent coroutine: after the
        // frame is emitted it races straight into the next open. Arming that
        // next failure behind a gate keeps the test deterministic — the gate
        // is released only once the collector has provably recorded the
        // success (the gated decode sits downstream of recordSuccess+Live).
        val armed = CompletableDeferred<Unit>()
        val h = Harness(
            entries = listOf(
                Entry(failure = nonMultipartFailure()),
                Entry(multipartBody(realJpeg(6, 4, 0x333333))),
                Entry(failure = nonMultipartFailure(), armBeforeFailing = armed),
                Entry(failure = nonMultipartFailure()),
            ),
            decoder = gated,
            unconfined = false, // real threads: the decode gates order every beat
        )
        h.player.start()
        // attempt 1 fails on a fresh budget…
        assertEquals(2_000L, h.delaySignal.receive())
        // …the tick releases the backoff, attempt 2 delivers a frame…
        h.tick(1)
        assertEquals(0L, withTimeout(10_000) { gated.requested.receive() })
        armed.complete(Unit)
        gated.releases.trySend(null)
        // …and the post-success failures restart the escalation at the first step:
        assertEquals("success resets the count", 2_000L, h.delaySignal.receive())
        h.tick(1)
        assertEquals("then it escalates again", 4_000L, h.delaySignal.receive())
        h.player.stop()
        assertEquals(listOf(2_000L, 2_000L, 4_000L), h.delays)
    }

    @Test
    fun `budget backoff escalates and exhaustion is terminal`() = runBlocking {
        val h = Harness(entries = emptyList())
        h.player.start()
        // Every open fails; the escalation runs 2000/4000/6000/8000 before
        // the fifth error spends the budget — terminal, no further opens.
        h.tick(4)
        val settled = h.awaitTerminal()
        assertTrue("expected Failed, was $settled", settled is MjpegState.Failed)
        assertTrue((settled as MjpegState.Failed).reason.contains("budget exhausted"))
        assertEquals(listOf(2_000L, 4_000L, 6_000L, 8_000L), h.delays)
        assertEquals("the delays advanced the fake clock", 20_000L, h.clock)
        assertEquals("terminal means the last error opened nothing more", 5, h.openCalls.get())
    }

    // ── the production transport against a real HTTP stack ──

    private inner class TransportHarness(budget: RetryBudget = RetryBudget()) {
        var clock = 0L
        val delays = mutableListOf<Long>()
        val ticks = Channel<Unit>(Channel.UNLIMITED)

        val player = MjpegPlayer(
            openStream = MjpegPlayer.transport(
                LenscastApiClient(device()) {
                    StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-mjpeg")
                },
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            decodeDispatcher = Dispatchers.Unconfined,
            clockMs = { clock },
            pauseMs = { ms ->
                delays.add(ms)
                clock += ms
                ticks.receive()
            },
            budget = budget,
        )

        fun tick(n: Int = 1) {
            repeat(n) { check(ticks.trySend(Unit).isSuccess) }
        }

        suspend fun awaitTerminal(): MjpegState =
            withTimeout(10_000) {
                player.state.first { it is MjpegState.Failed || it == MjpegState.Idle }
            }
    }

    @Test
    fun `non-200 answers consume the budget to a terminal failure`() = runBlocking {
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))
        }
        val h = TransportHarness()
        h.player.start()
        h.tick(4) // the four in-budget backoffs; the fifth error is terminal
        val settled = h.awaitTerminal()
        assertTrue("expected Failed, was $settled", settled is MjpegState.Failed)
        assertEquals(5, server.requestCount)
        val get = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(get)
        assertEquals("/stream", get!!.path)
        assertEquals("the shared auth ladder rides the media GET", "tok-mjpeg", get.getHeader("X-Api-Token"))
        assertEquals(listOf(2_000L, 4_000L, 6_000L, 8_000L), h.delays)
        h.player.stop()
    }

    @Test
    fun `a 200 that is not multipart is a failed attempt not a parseable stream`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html>proxy error page</html>"),
            )
        }
        val h = TransportHarness(budget = RetryBudget(maxErrors = 2, backoffStepMs = 100, backoffCapErrors = 1))
        h.player.start()
        h.tick(1) // the one in-budget backoff; the second failure is terminal
        val settled = h.awaitTerminal()
        assertTrue(settled is MjpegState.Failed)
        assertEquals(
            "two attempts only — the transport refused to hand the parser an HTML page",
            2,
            server.requestCount,
        )
        h.player.stop()
    }

    @Test
    fun `a real multipart 200 opens and goes live`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", CONTENT_TYPE)
                .setBody(okio.Buffer().write(multipartBody(realJpeg(8, 6, 0x555555)))),
        )
        val h = TransportHarness()
        h.player.start()
        val settled = withTimeout(10_000) {
            h.player.state.first { it == MjpegState.Live || it is MjpegState.Failed }
        }
        assertEquals("a real feed publishes", MjpegState.Live, settled)
        // Live flips before the decode lands; the frame's own flow is the beat.
        val frame = withTimeout(10_000) { h.player.frame.first { it != null } }
        assertEquals(8, frame!!.bitmap.width)
        h.player.stop()
    }
}

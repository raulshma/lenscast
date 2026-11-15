package com.raulshma.lenscast.companion.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Synthetic multipart fixtures against the server's exact wire format
 * (streaming/MjpegStreamPump.buildPartHeader): first part opens with
 * `--LensCastBoundary` and no leading CRLF, later parts are preceded by the
 * previous part's `\r\n` footer, headers are `Content-Type: image/jpeg` +
 * `Content-Length: N` + blank line, payload, `\r\n` footer. The boundary
 * value matches StreamingServer.BOUNDARY_MARKER.
 *
 * Driven with plain collectors under runBlocking, NOT Turbine's `test`:
 * turbine collects flows on an UnconfinedTestDispatcher (where `yield()` is
 * a no-op and its internal runTest drags kotlinx-coroutines-test in), so the
 * parse pump outruns the collector and the CONFLATED channel legitimately
 * drops mid-stream frames — production-correct drop-oldest behavior, but
 * unassertable frame by frame. Under runBlocking's event loop the hand-offs
 * stay sequential, so the keeping-up path is deterministic; genuine slowness
 * is simulated with suspension barriers, not time.
 */
class MjpegFrameSourceTest {

    private class TestStreamExhausted : IOException("no more test streams")

    private val source = MjpegFrameSource()

    // ── fixture helpers ──

    private fun jpeg(seed: Int, size: Int = 16): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        for (i in 2 until size - 2) bytes[i] = ((seed * 31 + i) and 0x7F).toByte()
        bytes[size - 2] = 0xFF.toByte()
        bytes[size - 1] = 0xD9.toByte()
        return bytes
    }

    /** One part exactly as the server pump writes it. */
    private fun part(payload: ByteArray, contentLength: Boolean, first: Boolean): ByteArray {
        val prefix = if (first) "" else "\r\n"
        val header = buildString {
            append(prefix)
            append("--$BOUNDARY\r\n")
            append("Content-Type: image/jpeg\r\n")
            if (contentLength) append("Content-Length: ${payload.size}\r\n")
            append("\r\n")
        }
        return header.toByteArray() + payload + "\r\n".toByteArray()
    }

    private fun multipartBody(vararg parts: ByteArray): ByteArray = parts.reduce { acc, bytes -> acc + bytes }

    private fun stream(body: ByteArray, contentType: String = CONTENT_TYPE) =
        MjpegStream(ByteArrayInputStream(body), contentType)

    private fun openSequence(vararg streams: MjpegStream): suspend () -> MjpegStream {
        val queue = streams.toMutableList()
        return {
            if (queue.isEmpty()) throw TestStreamExhausted() else queue.removeAt(0)
        }
    }

    /**
     * Collects the flow to completion. Returns the frames in delivery order
     * plus the terminal failure (null when the flow completed cleanly).
     */
    private fun collectUntilTerminal(open: suspend () -> MjpegStream): Pair<List<MjpegFrame>, Throwable?> {
        val frames = mutableListOf<MjpegFrame>()
        var failure: Throwable? = null
        try {
            runBlocking {
                source.frames(open).collect { frames.add(it) }
            }
        } catch (t: Throwable) {
            failure = t
        }
        return frames to failure
    }

    private fun assertExhausted(failure: Throwable?) {
        assertTrue("flow must end with the exhausted sentinel, was: $failure", failure is TestStreamExhausted)
    }

    // ── happy path ──

    @Test
    fun `content-length parts emit frames in order across reconnects`() {
        val a = jpeg(1)
        val b = jpeg(2)
        val c = jpeg(3)
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream(multipartBody(part(a, contentLength = true, first = true), part(b, contentLength = true, first = false))),
                stream(multipartBody(part(c, contentLength = true, first = true))),
            )
        )
        assertExhausted(failure)
        assertEquals(3, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
        assertArrayEquals(b, frames[1].jpeg)
        assertArrayEquals(c, frames[2].jpeg)
        assertEquals(0L, frames[0].sequence)
        assertEquals(1L, frames[1].sequence)
        assertEquals(2L, frames[2].sequence)
    }

    @Test
    fun `parts without content-length are scanned to the next boundary`() {
        val a = jpeg(4)
        val b = jpeg(5)
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream(multipartBody(part(a, contentLength = false, first = true), part(b, contentLength = false, first = false)))
            )
        )
        assertExhausted(failure)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
        assertArrayEquals(b, frames[1].jpeg)
    }

    @Test
    fun `mixed content-length and scanned parts interleave`() {
        val a = jpeg(6)
        val b = jpeg(7)
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream(
                    multipartBody(
                        part(a, contentLength = true, first = true),
                        part(b, contentLength = false, first = false),
                    )
                )
            )
        )
        assertExhausted(failure)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
        assertArrayEquals(b, frames[1].jpeg)
    }

    @Test
    fun `quoted boundary parameter is accepted`() {
        val a = jpeg(8)
        val (frames, failure) = collectUntilTerminal(
            openSequence(stream(part(a, contentLength = true, first = true), contentType = "multipart/x-mixed-replace; boundary=\"$BOUNDARY\""))
        )
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
    }

    // ── robustness: resync, malformed parts, line endings ──

    @Test
    fun `garbage before and between parts is skipped by resyncing`() {
        val a = jpeg(9)
        val b = jpeg(10)
        val junk = "<html>proxy garbage</html>\r\n--LensCastBoundar".toByteArray() // truncated marker too
        val body = junk + "\r\n".toByteArray() + part(a, contentLength = true, first = true) +
            "trailing noise".toByteArray() + part(b, contentLength = true, first = false)
        val (frames, failure) = collectUntilTerminal(openSequence(stream(body)))
        assertExhausted(failure)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
        assertArrayEquals(b, frames[1].jpeg)
    }

    @Test
    fun `payload without jpeg markers is dropped and the sequence stays contiguous`() {
        val a = jpeg(11)
        val c = jpeg(26)
        val notJpeg = "definitely not a jpeg payload".toByteArray()
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream(
                    multipartBody(
                        part(notJpeg, contentLength = true, first = true),
                        part(a, contentLength = true, first = false),
                    )
                ),
                stream(part(c, contentLength = true, first = true)),
            )
        )
        assertExhausted(failure)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
        assertEquals(0L, frames[0].sequence)
        assertArrayEquals(c, frames[1].jpeg)
        assertEquals("the skipped part must not consume a sequence number", 1L, frames[1].sequence)
    }

    @Test
    fun `lf-only line endings are tolerated`() {
        val a = jpeg(12)
        val header = "--$BOUNDARY\nContent-Type: image/jpeg\nContent-Length: ${a.size}\n\n"
        val body = header.toByteArray() + a + "\r\n--$BOUNDARY\r\n".toByteArray()
        val (frames, failure) = collectUntilTerminal(openSequence(stream(body)))
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
    }

    @Test
    fun `boundary marker inside a content-length payload does not truncate the frame`() {
        val a = jpeg(13, size = 64)
        a[20] = '-'.code.toByte()
        a[21] = '-'.code.toByte()
        "--$BOUNDARY".toByteArray().copyInto(a, 22)
        val (frames, failure) = collectUntilTerminal(
            openSequence(stream(part(a, contentLength = true, first = true)))
        )
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
    }

    // ── buffer growth: frames larger than the 16KB scan window ──

    @Test
    fun `content-length frame larger than the read buffer survives refills`() {
        val big = jpeg(14, size = 20_000)
        val small = jpeg(15)
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream(
                    multipartBody(
                        part(big, contentLength = true, first = true),
                        part(small, contentLength = true, first = false),
                    )
                )
            )
        )
        assertExhausted(failure)
        assertEquals(2, frames.size)
        assertArrayEquals(big, frames[0].jpeg)
        assertArrayEquals(small, frames[1].jpeg)
    }

    @Test
    fun `scanned frame larger than the read buffer survives refills`() {
        val big = jpeg(16, size = 20_000)
        val (frames, failure) = collectUntilTerminal(
            openSequence(stream(part(big, contentLength = false, first = true)))
        )
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(big, frames[0].jpeg)
    }

    // ── stream lifecycle: truncation, empty bodies, terminal failures ──

    @Test
    fun `premature eof mid part drops the partial frame and reconnects`() {
        val a = jpeg(17)
        val b = jpeg(18)
        val header = "\r\n--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${b.size}\r\n\r\n"
        val truncated = part(a, contentLength = true, first = true) + header.toByteArray() + b.copyOfRange(0, 4)
        val (frames, failure) = collectUntilTerminal(
            openSequence(stream(truncated), stream(part(b, contentLength = true, first = true)))
        )
        assertExhausted(failure)
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
        val next = frames[1]
        assertArrayEquals(b, next.jpeg)
        assertEquals("the truncated part must not consume a sequence number", 1L, next.sequence)
    }

    @Test
    fun `eof before any part reconnects`() {
        val a = jpeg(19)
        val (frames, failure) = collectUntilTerminal(
            openSequence(stream(ByteArray(0)), stream(part(a, contentLength = true, first = true)))
        )
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
    }

    @Test
    fun `openStream failure is the terminal exception`() {
        val failure = IOException("device unreachable")
        var caught: Throwable? = null
        try {
            runBlocking {
                source.frames { throw failure }.collect { }
            }
        } catch (t: Throwable) {
            caught = t
        }
        assertEquals(failure.message, caught?.message)
    }

    @Test
    fun `non-multipart content type is a failed attempt not a terminal`() {
        val a = jpeg(20)
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream("<html>503 busy</html>".toByteArray(), contentType = "text/html"),
                stream(part(a, contentLength = true, first = true)),
            )
        )
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
    }

    @Test
    fun `multipart type without a boundary parameter is a failed attempt`() {
        val a = jpeg(21)
        val (frames, failure) = collectUntilTerminal(
            openSequence(
                stream(part(a, contentLength = true, first = true), contentType = "multipart/x-mixed-replace"),
                stream(part(a, contentLength = true, first = true)),
            )
        )
        assertExhausted(failure)
        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0].jpeg)
    }

    // ── conflation: the viewer always gets the newest frame ──

    @Test
    fun `slow collector receives the first and newest frame with stale ones dropped`() {
        val a = jpeg(22)
        val b = jpeg(23)
        val c = jpeg(24)
        val body = multipartBody(
            part(a, contentLength = true, first = true),
            part(b, contentLength = true, first = false),
            part(c, contentLength = true, first = false),
        )
        // Real-time slowness is simulated with suspension barriers, not
        // delays: the collector takes frame 0 and then freezes on [stall]
        // while the pump parses the rest of the body and parks on [gate]
        // (signalled via [parsed] so the test knows the conflation window
        // has closed around the newest frame).
        val stall = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val parsed = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val received = mutableListOf<Long>()
        var attempt = 0
        runBlocking {
            val job = launch {
                source.frames {
                    val currentAttempt = attempt
                    attempt += 1
                    if (currentAttempt == 0) {
                        stream(body)
                    } else {
                        parsed.complete(Unit)
                        gate.await()
                        awaitCancellation()
                    }
                }.collect { frame ->
                    received.add(frame.sequence)
                    if (received.size == 1) stall.await() else drained.complete(Unit)
                }
            }
            parsed.await() // pump parsed a, b, c; b was conflated into c; pump parked
            stall.complete(Unit)
            drained.await() // collector took the conflated newest frame
            job.cancelAndJoin()
        }
        assertEquals("frames 1 and 2 must conflate into the newest", listOf(0L, 2L), received)
    }

    // ── header parsing and JPEG sanity, pinned directly ──

    @Test
    fun `boundary parser handles quoting and case and rejects non multipart`() {
        assertArrayEquals(
            "--LensCastBoundary".toByteArray(),
            MjpegFrameSource.parseMultipartBoundary("multipart/x-mixed-replace; boundary=LensCastBoundary"),
        )
        assertArrayEquals(
            "--x".toByteArray(),
            MjpegFrameSource.parseMultipartBoundary("multipart/mixed; BOUNDARY=\"x\""),
        )
        assertArrayEquals(
            "--y".toByteArray(),
            MjpegFrameSource.parseMultipartBoundary("multipart/x-mixed-replace;charset=utf-8;  boundary = y"),
        )
        assertNull(MjpegFrameSource.parseMultipartBoundary("multipart/x-mixed-replace"))
        assertNull(MjpegFrameSource.parseMultipartBoundary("text/html; boundary=fake"))
        assertNull(MjpegFrameSource.parseMultipartBoundary(null))
    }

    @Test
    fun `jpeg sanity gate accepts only soi and eoi framed payloads`() {
        assertTrue(MjpegFrameSource.isPlausibleJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        assertFalse(MjpegFrameSource.isPlausibleJpeg(jpeg(25).copyOfRange(0, 12))) // SOI only, cut before EOI
        assertFalse(MjpegFrameSource.isPlausibleJpeg(ByteArray(0)))
        assertFalse(MjpegFrameSource.isPlausibleJpeg("not jpeg at all".toByteArray()))
    }

    companion object {
        private const val BOUNDARY = "LensCastBoundary"
        private const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"
    }
}

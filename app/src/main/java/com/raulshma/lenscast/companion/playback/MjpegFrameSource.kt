package com.raulshma.lenscast.companion.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * One opened MJPEG HTTP response, handed to [MjpegFrameSource.frames] by the
 * caller's transport closure. The wave-2 transport wraps the HTTP response
 * body here (OkHttp on Android); the source only knows the byte pipe and the
 * Content-Type header the server answered with.
 *
 * Contract: return an instance ONLY for a real 200 multipart body — anything
 * else (auth failure, 503 client cap, an HTML error page served with a 200)
 * must throw from the closure instead, because the source treats a returned
 * stream as "the camera is live" and will reconnect on a body that never
 * yields frames.
 */
class MjpegStream(val input: InputStream, val contentType: String) : Closeable by input

/** One complete JPEG frame; [sequence] orders frames across reconnects (0-based, +1 per emitted frame). */
data class MjpegFrame(val jpeg: ByteArray, val sequence: Long)

/**
 * The MJPEG rung's frame source: a transport-agnostic parser for the
 * server's `multipart/x-mixed-replace` stream. Wire format is
 * [MjpegStreamPump.buildPartHeader] exactly — first part opens with
 * `--{boundary}` (no leading CRLF), subsequent parts are preceded by the
 * `\r\n` footer of the previous frame, each part carries
 * `Content-Type: image/jpeg` and a decimal `Content-Length`, the JPEG bytes
 * follow the blank line, and a bare `\r\n` footer closes the part. The
 * Content-Type answer is `multipart/x-mixed-replace; boundary=LensCastBoundary`
 * (StreamingServer.BOUNDARY_MARKER, unquoted).
 *
 * Robustness contract (mirrors the web `<img>` rung's crash-tolerance):
 *  - garbage between parts, boundary strings inside JPEG payloads, missing
 *    Content-Length, CRLF-vs-LF line endings, and preamble before the first
 *    boundary are all tolerated;
 *  - a malformed or truncated part is dropped, never emitted and never
 *    thrown — parsing resyncs at the next boundary;
 *  - the flow fails ONLY when [openStream] itself throws (the caller's
 *    transport is the reconnect + retry-budget seam: it owns backoff and
 *    decides when the ladder should demote). A stream that ends mid-part or
 *    whose Content-Type carries no usable boundary is treated as a failed
 *    attempt: it is closed and [openStream] is called again;
 *  - cancellation lands between frames: CONFLATED sends never suspend, so
 *    the only suspension points are openStream and the inter-frame checks.
 *    A blocking read parked mid-frame delays the unwind by at most one
 *    frame interval (the wave-2 transport can add a socket read timeout if
 *    it wants tighter cancellation); the current stream is closed on the
 *    way out.
 *
 * Emission uses a CONFLATED channel: a companion viewer always wants the
 * newest frame — when the collector (bitmap decode + render) is slower than
 * the camera's ~24 fps feed, stale frames are pure decode work, so the
 * buffer keeps one element and each new frame replaces it (drop-oldest, the
 * same live-edge philosophy as LiveAudioPlayer.ts's "Drop chunks instead —
 * live audio joins at the live edge"). Consequent [MjpegFrame.sequence]
 * gaps at the collector are expected and harmless.
 */
class MjpegFrameSource(
    /** Internal scan-buffer start size; the 20KB+ frames of real feeds grow it on demand. */
    private val readBufferSizeBytes: Int = DEFAULT_READ_BUFFER_BYTES,
) {

    /**
     * Repeatedly opens the stream and emits complete JPEG frames until
     * cancelled or until [openStream] throws (which fails the flow with that
     * exception, verbatim — after any frames already parsed have been
     * delivered). Between two openStream calls there is no delay: a caller
     * whose streams die instantly must apply its own backoff inside the
     * closure (RetryBudget + delay) or throw — the pacing decision belongs to
     * the transport, which knows the failure history.
     */
    fun frames(openStream: suspend () -> MjpegStream): Flow<MjpegFrame> = ConflatedEmissionFlow { newest ->
        var sequence = 0L
        while (currentCoroutineContext().isActive) {
            val stream = openStream()
            stream.use { opened ->
                val boundary = parseMultipartBoundary(opened.contentType)
                if (boundary == null) {
                    // Not a multipart answer (proxy error page, misconfigured
                    // server): a failed attempt, not a terminal condition — a
                    // reconnect gives a restarted/corrected server a chance.
                    return@use
                }
                val reader = PartReader(opened.input, readBufferSizeBytes)
                while (currentCoroutineContext().isActive) {
                    val payload: ByteArray? = try {
                        reader.nextPartPayload(boundary)
                    } catch (e: IOException) {
                        // Mid-stream I/O failure: not terminal either — the
                        // next loop iteration re-opens through the caller's
                        // (retry-budget-aware) transport.
                        null
                    }
                    if (payload == null) break
                    if (isPlausibleJpeg(payload)) {
                        newest.send(MjpegFrame(payload, sequence))
                        sequence += 1
                        // Courtesy yield: lets a keeping-up collector take the
                        // frame before the next parse pass, so it sees every
                        // frame — the CONFLATED drop-oldest only kicks in when
                        // it genuinely falls behind.
                        yield()
                    }
                }
            }
        }
    }

    /**
     * The per-stream scanner: one growable window over the response body, so
     * boundary scans and header reads never do a syscall per byte and a
     * marker split across a refill is still found (the possible partial tail
     * is rescanned after every fill).
     */
    private class PartReader(private val input: InputStream, bufferSize: Int) {

        private var buffer = ByteArray(bufferSize)
        private var pos = 0 // first unconsumed byte
        private var limit = 0 // one past the last filled byte
        private var eof = false

        /** Fills the window; false at EOF. Compacts consumed bytes when the tail is full. */
        private fun fill(): Boolean {
            if (eof) return false
            if (limit == buffer.size) {
                if (pos > 0) {
                    buffer.copyInto(buffer, 0, pos, limit)
                    limit -= pos
                    pos = 0
                } else {
                    buffer = buffer.copyOf(buffer.size * 2)
                }
            }
            val read = input.read(buffer, limit, buffer.size - limit)
            if (read <= 0) {
                if (read < 0) eof = true
                return false
            }
            limit += read
            return true
        }

        /**
         * Index of [marker] in the window, or null at EOF. The index is only
         * valid until the next [fill]; callers must consume through it
         * before anything else refills.
         */
        private fun locate(marker: ByteArray): Int? {
            var from = pos
            while (true) {
                val hit = buffer.indexOfSequence(marker, from, limit)
                if (hit >= 0) return hit
                from = maxOf(pos, limit - marker.size + 1)
                val posBeforeFill = pos
                if (!fill()) return null
                // fill() may have compacted the window (pos → 0): rescale the scan start.
                from = maxOf(pos, from - posBeforeFill)
            }
        }

        /** Consumes the window up to (excluding) absolute index [end]. */
        private fun consumeThrough(end: Int) {
            pos = end
        }

        /** Consumes through the next line terminator (CRLF or LF). False at EOF before one. */
        private fun skipLine(): Boolean {
            while (true) {
                for (i in pos until limit) {
                    if (buffer[i] == LF) {
                        pos = i + 1
                        return true
                    }
                }
                if (!fill()) return false
            }
        }

        /** One header line without its terminator, or null at EOF (including a partial trailing line). */
        private fun readHeaderLine(): String? {
            while (true) {
                for (i in pos until limit) {
                    if (buffer[i] == LF) {
                        var end = i
                        if (end > pos && buffer[end - 1] == CR) end--
                        val line = String(buffer, pos, end - pos, StandardCharsets.ISO_8859_1)
                        pos = i + 1
                        return line
                    }
                }
                if (!fill()) return null
            }
        }

        /** Exactly [count] bytes, or null when EOF truncates the read. */
        private fun readExactly(count: Int): ByteArray? {
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val available = limit - pos
                if (available > 0) {
                    val take = minOf(available, count - filled)
                    buffer.copyInto(out, filled, pos, pos + take)
                    pos += take
                    filled += take
                }
                if (filled < count && !fill()) return null
            }
            return out
        }

        /**
         * Window bytes [pos, end) minus every trailing line-break byte — the
         * scan-path payload. Two breaks precede the next boundary when both
         * parts carry footers (this part's `\r\n` + the next part's leading
         * `\r\n`); foreign servers may send one, or bare LFs. Stripping all
         * of them is safe: a plausible JPEG must end exactly at EOI, so any
         * trailing 0x0D/0x0A belongs to the multipart delimiter, not the
         * frame.
         */
        private fun payloadUpTo(end: Int): ByteArray {
            var last = end
            while (last > pos && (buffer[last - 1] == LF || buffer[last - 1] == CR)) last--
            return buffer.copyOfRange(pos, last)
        }

        /**
         * The next part's JPEG payload, or null when the stream ended — at a
         * clean boundary EOF, or truncated mid-part (the partial bytes are
         * dropped, never emitted: a half JPEG is worse than no JPEG for a
         * viewer that only wants complete frames).
         */
        fun nextPartPayload(boundary: ByteArray): ByteArray? {
            val markerStart = locate(boundary) ?: return null
            consumeThrough(markerStart)
            if (!skipLine()) return null // rest of the boundary line (transport padding / closing "--")

            var contentLength = -1L
            while (true) {
                val line = readHeaderLine() ?: return null // EOF mid-headers → truncated part
                if (line.isEmpty()) break // blank line: headers done
                val declared = contentLengthFrom(line)
                if (declared != null) contentLength = declared
            }

            return if (contentLength in 0L..MAX_PART_BYTES) {
                readExactly(contentLength.toInt()) ?: return null
            } else {
                // No usable Content-Length (absent, garbage, or absurd):
                // scan to the next boundary; the part is everything before
                // the delimiter's preceding line break. A boundary-sized
                // byte run inside the JPEG entropy data would truncate this
                // payload — the accepted risk of every CL-less MJPEG parser;
                // LensCast's own server always sends Content-Length.
                val nextMarker = locate(boundary)
                if (nextMarker == null) {
                    // Clean EOF with no further boundary: the remaining bytes
                    // are the stream's final part. The SOI/EOI gate below
                    // decides its fate — a complete frame survives the server
                    // closing the stream, a truncated tail fails the EOI
                    // check and is dropped, never emitted.
                    return drainRemaining()
                }
                val payload = payloadUpTo(nextMarker)
                consumeThrough(nextMarker)
                payload
            }
        }

        /**
         * Everything left in the window plus whatever remains readable, with
         * a trailing part-terminator stripped. The window is emptied so the
         * caller's next locate() hits EOF cleanly and the reconnect loop
         * proceeds.
         */
        private fun drainRemaining(): ByteArray {
            val head = buffer.copyOfRange(pos, limit)
            pos = limit
            val tail = input.readBytes() // InputStream default: reads until EOF
            val combined = head + tail
            var end = combined.size
            while (end > 0 && (combined[end - 1] == LF || combined[end - 1] == CR)) end--
            return combined.copyOf(end)
        }
    }

    companion object {
        private const val DEFAULT_READ_BUFFER_BYTES = 16 * 1024

        /**
         * Sanity ceiling for a declared Content-Length: real frames are
         * tens of KB (StreamDefaults JPEG_QUALITY at camera resolution); a
         * larger declaration is a garbage/hostile header, and honoring it
         * would let one response OOM the viewer — fall back to the
         * next-boundary scan instead.
         */
        private const val MAX_PART_BYTES = 16L * 1024 * 1024

        private val CR: Byte = '\r'.code.toByte()
        private val LF: Byte = '\n'.code.toByte()

        /**
         * Extracts the multipart boundary from a Content-Type header:
         * RFC 2045 allows the value quoted or bare and the parameter name is
         * case-insensitive. LensCast's server sends it unquoted
         * (`multipart/x-mixed-replace; boundary=LensCastBoundary`,
         * MjpegStreamPump.openStream). Returns null when the type is not
         * multipart/ or carries no boundary — the caller treats that as a
         * failed attempt and reconnects.
         */
        fun parseMultipartBoundary(contentType: String?): ByteArray? {
            if (contentType == null) return null
            val header = contentType.trim()
            if (!header.startsWith("multipart/", ignoreCase = true)) return null
            for (param in header.split(';').drop(1)) {
                val trimmed = param.trim()
                val equals = trimmed.indexOf('=')
                if (equals <= 0) continue
                val name = trimmed.substring(0, equals).trim()
                if (!name.equals("boundary", ignoreCase = true)) continue
                val value = trimmed.substring(equals + 1).trim().removeSurrounding("\"")
                if (value.isNotEmpty()) return ("--$value").toByteArray(Charsets.US_ASCII)
            }
            return null
        }

        /**
         * Content-Length of a part-header line, or null when the line is not
         * one (header names are case-insensitive; the value must be a
         * non-negative decimal).
         */
        private fun contentLengthFrom(line: String): Long? {
            val colon = line.indexOf(':')
            if (colon < 0) return null
            if (!line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) return null
            return line.substring(colon + 1).trim().toLongOrNull()?.takeIf { it >= 0 }
        }

        /**
         * The SOI (FFD8) … EOI (FFD9) sanity gate before anything reaches the
         * collector: the pump only ever carries whole encoder frames, so a
         * payload without both markers is a parse artifact (header bleed,
         * resync garbage) — dropping it here keeps one bad part from becoming
         * a corrupted Bitmap decode downstream.
         */
        fun isPlausibleJpeg(payload: ByteArray): Boolean =
            payload.size >= 4 &&
                payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte() &&
                payload[payload.size - 2] == 0xFF.toByte() && payload[payload.size - 1] == 0xD9.toByte()

        private fun ByteArray.indexOfSequence(sequence: ByteArray, from: Int, to: Int): Int {
            var i = from
            val lastStart = to - sequence.size
            while (i <= lastStart) {
                var j = 0
                while (j < sequence.size && this[i + j] == sequence[j]) j++
                if (j == sequence.size) return i
                i++
            }
            return -1
        }
    }
}

/**
 * A flow whose producer runs in a supervised side coroutine feeding a
 * CONFLATED channel, and whose CONSUMING side is the collector's own
 * coroutine — the only shape in which drop-oldest actually sits against the
 * collector:
 *
 *  - `flow { }.buffer(CONFLATED)` fails fast: when the upstream throws, the
 *    buffer's consumption loop is cancelled and buffered frames are lost —
 *    the viewer would lose the freshest frame exactly when the stream dies;
 *  - an intermediary forwarding coroutine (pump → conflation → forwarder)
 *    drains a pending frame out of the conflation window the moment the
 *    real collector stalls, queueing stale frames again.
 *
 * Here a stalled collector parks inside [FlowCollector.emit] while the pump
 * keeps conflating, so the next delivery after a stall IS the newest frame;
 * and when the pump fails, `close(cause)` drains the buffered frame to the
 * collector before the cause (channel close semantics).
 */
private class ConflatedEmissionFlow<T>(
    private val produce: suspend (SendChannel<T>) -> Unit,
) : Flow<T> {

    override suspend fun collect(collector: FlowCollector<T>) {
        coroutineScope {
            val newest = Channel<T>(Channel.CONFLATED)
            // SupervisorJob: the pump's failure must reach the collector only
            // through close(cause) — as a structured child it would fail the
            // scope and cancel the consumer before it could drain the buffer.
            val pump = launch(SupervisorJob(currentCoroutineContext().job)) {
                try {
                    produce(newest)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    newest.close(t)
                    return@launch
                }
                newest.close()
            }
            try {
                for (frame in newest) collector.emit(frame)
            } finally {
                // Collector gone (cancelled or the pump failed): release the
                // pump — if it is blocked reading the socket, the channel
                // cancellation turns its next send into a CancellationException
                // and it unwinds through its own stream.close().
                newest.cancel()
                pump.cancel()
            }
        }
    }
}

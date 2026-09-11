package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Random

/**
 * The RTSP read-side parsers under hostile client bytes: a deterministic
 * corpus fuzzer over [RtspRequestParser], [RtspWireReader],
 * [RtspSessionProtocol] header parses and [RtspUriPolicy].
 *
 * ── The contract ──
 * Every parser answers null / a value / its declared verdict — never
 * NumberFormatException (integer fields parse with toIntOrNull), never an
 * unbounded allocation (lines and header counts are capped), never an
 * infinite loop (every test runs under a timeout).
 *
 * ── The corpus ──
 * malformed request lines, repeated/aliased headers, Content-Length at the
 * Int overflow boundary, CSeq at every parse boundary, Transport headers with
 * huge channel numbers, SETUP URIs with huge track IDs, line truncations at
 * every prefix, an over-long line with no LF, and seeded garbage lines.
 */
class RtspParserFuzzTest {

    // ── request-line + header parsing ──

    @Test(timeout = 10_000)
    fun `corpus - malformed request lines parse to null or a request, never a crash`() {
        val hostileLines = listOf(
            listOf(""),
            listOf("GET"),
            listOf("A B"),
            listOf("   "),
            listOf("OPTIONS  RTSP/1.0"), // double space → empty uri part is fine
            listOf("OPTIONS rtsp://h/stream"),
            listOf("þÿ garbage \u0000 \u0001"),
            listOf("OPTIONS rtsp://h/stream RTSP/1.0", ":"),
            listOf("OPTIONS rtsp://h/stream RTSP/1.0", ":::"),
            listOf("OPTIONS rtsp://h/stream RTSP/1.0", "Header-No-Colon"),
            listOf("OPTIONS rtsp://h/stream RTSP/1.0", "X-Huge: " + "a".repeat(100_000)),
            listOf("OPTIONS rtsp://h/stream RTSP/1.0", "cseq", "CSEQ:", "CSeq: abc", "CSeq: -5", "CSeq: 99999999999999"),
        )
        for (lines in hostileLines) {
            val request = RtspRequestParser.parse(lines)
            if (request != null) {
                assertTrue(request.headers.keys.all { it == it.lowercase() })
            }
            RtspRequestParser.parseHeaders(lines)
            RtspRequestParser.extractContentLength(lines)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - Content-Length at the integer boundaries never yields a bogus skip`() {
        for (value in listOf(
            "0", "1", "-1", "-999999999", "2147483647", "2147483648",
            "99999999999999", "abc", " 42 ", "+42", "0x10", "1_000",
        )) {
            val lines = listOf("OPTIONS rtsp://h RTSP/1.0", "Content-Length: $value")
            val parsed = RtspRequestParser.extractContentLength(lines)
            // Unparsable forms answer 0; parseable ones round-trip.
            if (value.trim().toIntOrNull() != null) {
                assertEquals(value.trim().toInt(), parsed)
            } else {
                assertEquals(0, parsed)
            }
        }
        // Repeated headers: the first Content-Length wins.
        val repeated = listOf(
            "SETUP rtsp://h/stream RTSP/1.0",
            "Content-Length: 5",
            "Content-Length: 99999999999999999999",
        )
        assertEquals(5, RtspRequestParser.extractContentLength(repeated))
    }

    // ── regression: Transport channel numbers past Int range ──
    // Pre-fix, `interleaved=99999999999999-0` matched `(\d+)` and threw
    // NumberFormatException out of the SETUP path, killing the session.

    @Test(timeout = 10_000)
    fun `regression - interleaved channel numbers past Int range fall back to defaults`() {
        val verdict = RtspSessionProtocol.parseTransportHeader("RTP/AVP/TCP;unicast;interleaved=99999999999999-0")
        assertTrue(verdict is RtspSessionProtocol.TransportVerdict.Interleaved)
        assertNull((verdict as RtspSessionProtocol.TransportVerdict.Interleaved).channels)
    }

    @Test(timeout = 10_000)
    fun `regression - SETUP track IDs past Int range resolve to no track`() {
        assertNull(RtspUriPolicy.resolveTrackId("rtsp://host/stream/trackID=99999999999999999999"))
        assertNull(RtspUriPolicy.resolveTrackId("/stream/trackID=21474836470"))
        // Valid IDs are untouched.
        assertEquals(0, RtspUriPolicy.resolveTrackId("rtsp://host/stream/trackID=0"))
        assertEquals(1, RtspUriPolicy.resolveTrackId("/stream/trackID=1"))
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - transport headers over garbage and boundary channels give verdicts only`() {
        val headers = listOf(
            null, "", "garbage", "RTP/AVP/TCP", "interleaved",
            "RTP/AVP/TCP;interleaved", "RTP/AVP/TCP;interleaved=",
            "RTP/AVP/TCP;interleaved=-", "RTP/AVP/TCP;interleaved=0-",
            "RTP/AVP/TCP;interleaved=0-1", "rtp/avp/tcp;INTERLEAVED=255-254",
            "RTP/AVP/TCP;interleaved=0-256", "RTP/AVP/TCP;interleaved=256-0",
            "RTP/AVP/TCP;interleaved=99999999999999-0", "RTP/AVP/TCP;interleaved=0-99999999999999",
            "RTP/AVP/TCP;interleaved=1-2-3", "RTP/AVP/TCP;interleaved=0-1;interleaved=3-4",
            "RTP/AVP/TCP;interleaved=" + "9".repeat(400) + "-1",
        )
        for (header in headers) {
            when (val verdict = RtspSessionProtocol.parseTransportHeader(header)) {
                is RtspSessionProtocol.TransportVerdict.Unsupported -> Unit
                is RtspSessionProtocol.TransportVerdict.Interleaved -> {
                    val channels = verdict.channels
                    if (channels != null) {
                        assertTrue(channels.rtp in 0..255)
                        assertTrue(channels.rtcp in 0..255)
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - CSeq at every parse boundary gives a verdict, never a crash`() {
        for (value in listOf(
            null, "", " ", "abc", "-1", "-2147483648", "0", "1",
            "2147483647", "2147483648", "99999999999999999999", "0x10",
            " 7 ", "7;timeout=30",
        )) {
            val first = RtspSessionProtocol.cseqVerdict(-1, value)
            val strict = RtspSessionProtocol.cseqVerdict(5, value)
            listOf(first, strict).forEach { verdict ->
                when (verdict) {
                    is RtspSessionProtocol.CSeqVerdict.Ok -> assertTrue(verdict.cseq >= 0)
                    is RtspSessionProtocol.CSeqVerdict.Reject -> assertTrue(verdict.cseq >= 0)
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - session and uri parsing over hostile values stay null-or-value`() {
        // Concrete expectations, including the parameter-stripping forms.
        assertNull(RtspSessionProtocol.parseSessionHeader(null))
        assertEquals("", RtspSessionProtocol.parseSessionHeader(";timeout=60"))
        assertEquals("", RtspSessionProtocol.parseSessionHeader(";;;;;"))
        assertEquals("abc", RtspSessionProtocol.parseSessionHeader("abc;def;ghi"))
        assertEquals("padded", RtspSessionProtocol.parseSessionHeader("  padded  ; x "))
        // 10k collapsed slashes normalize in bounded time (this was already
        // linear; the pin keeps it that way).
        val slashes = "/".repeat(10_000)
        assertEquals("/", RtspUriPolicy.normalizedPath(slashes))
        assertEquals("/", RtspUriPolicy.extractRtspPath("rtsp://"))
        // The collapsed path is the aggregate path — video track.
        assertEquals(0, RtspUriPolicy.resolveTrackId(slashes))
        assertFalse(RtspUriPolicy.isRequestUriAllowed("SETUP", slashes))
    }

    // ── the wire reader ──

    private fun readLineOf(bytes: ByteArray): String? {
        val input = ByteArrayInputStream(bytes, 1, bytes.size - 1)
        return RtspWireReader(input).readLine(bytes[0].toInt())
    }

    @Test(timeout = 10_000)
    fun `corpus - line reads over every prefix and terminator shape answer null-or-line`() {
        val line = "OPTIONS rtsp://host/stream RTSP/1.0\r\nCSeq: 1\n\r\n"
        for (cut in 1..line.length) {
            val prefix = line.substring(0, cut).toByteArray(Charsets.UTF_8)
            readLineOf(prefix) // null mid-line, a string at a terminator
        }
        // CRLF smuggling shapes: bare LF, CRCRLF, LFCR all end the line at the
        // first LF; a lone CR is NOT a terminator, so "X\r" is EOF mid-line.
        for (terminator in listOf("\r\n", "\n", "\r\r\n", "\n\r")) {
            val bytes = ("X" + terminator).toByteArray(Charsets.UTF_8)
            assertEquals("X", readLineOf(bytes))
        }
        assertNull(readLineOf("X\r".toByteArray(Charsets.UTF_8)))
    }

    // ── regression: an unterminated line grew the heap buffer unbounded ──
    // Pre-fix, a client streaming gigabytes with no LF fed an ever-growing
    // ByteArrayOutputStream — a remote OOM. Over-cap lines now answer null
    // (the same drop-the-connection verdict as EOF).

    @Test(timeout = 10_000)
    fun `regression - an over-long unterminated line is dropped, not buffered forever`() {
        // 8193 bytes: writing the 8193rd trips the cap before any LF is needed.
        val huge = ByteArray(8193) { 'A'.code.toByte() }
        assertNull(readLineOf(huge))
        // At the cap with a terminating LF the line still reads back whole.
        val atCap = ByteArray(8192) { 'A'.code.toByte() } + byteArrayOf('\n'.code.toByte())
        assertEquals(8192, readLineOf(atCap)!!.length)
    }

    @Test(timeout = 10_000)
    fun `corpus - discardBytes never lies about short streams`() {
        val reader = RtspWireReader(ByteArrayInputStream(ByteArray(10)))
        assertTrue(reader.discardBytes(0))
        assertTrue(reader.discardBytes(10))
        assertFalse(reader.discardBytes(11))
        assertFalse(RtspWireReader(ByteArrayInputStream(ByteArray(0))).discardBytes(65535))
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage lines never crash any parser`() {
        val random = Random(0x8554)
        for (trial in 0 until 300) {
            val line = buildString {
                val alphabet = "OPTIONS/SETUP rtsp://:=;,-\r\n%&#$"
                repeat(random.nextInt(128)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val request = RtspRequestParser.parse(listOf(line, line))
            RtspRequestParser.extractContentLength(listOf(line))
            if (request != null) {
                RtspUriPolicy.isRequestUriAllowed(request.method, request.uri)
                RtspUriPolicy.resolveTrackId(request.uri)
                RtspSessionProtocol.cseqVerdict(-1, request.headers["cseq"])
            }
            RtspSessionProtocol.parseTransportHeader(line)
        }
    }
}

package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RtspRequestTest {

    // ── RtspRequestParser.parse ──

    @Test
    fun `parse extracts method uri headers and content length`() {
        val request = RtspRequestParser.parse(
            listOf(
                "DESCRIBE rtsp://host:8554/stream RTSP/1.0",
                "CSeq: 2",
                "Accept: application/sdp",
            )
        )!!

        assertEquals("DESCRIBE", request.method)
        assertEquals("rtsp://host:8554/stream", request.uri)
        assertEquals(mapOf("cseq" to "2", "accept" to "application/sdp"), request.headers)
        assertEquals(0, request.contentLength)
    }

    @Test
    fun `parse returns null for empty malformed or short request lines`() {
        assertNull(RtspRequestParser.parse(emptyList()))
        assertNull(RtspRequestParser.parse(listOf("GARBAGE")))
        assertNull(RtspRequestParser.parse(listOf("OPTIONS /stream"))) // two parts only
        assertNull(RtspRequestParser.parse(listOf("A B")))
    }

    @Test
    fun `parse keeps everything past the third request-line part out of the way`() {
        // Extra segments after the version are ignored: method/uri/version come first.
        val request = RtspRequestParser.parse(listOf("PLAY /stream RTSP/1.0 extra junk"))!!
        assertEquals("PLAY", request.method)
        assertEquals("/stream", request.uri)
    }

    // ── headers ──

    @Test
    fun `header keys are lowercased and later duplicates overwrite`() {
        val headers = RtspRequestParser.parseHeaders(
            listOf(
                "OPTIONS / RTSP/1.0",
                "X-A: 1",
                "USER-AGENT: Test/1.0",
                "x-a: 2",
            )
        )
        assertEquals("2", headers["x-a"])
        assertEquals("Test/1.0", headers["user-agent"])
        assertEquals(2, headers.size)
    }

    @Test
    fun `header values keep inner colons and are trimmed`() {
        val headers = RtspRequestParser.parseHeaders(
            listOf(
                "OPTIONS / RTSP/1.0",
                "  Authorization :   Digest nonce=\"a:b\"  ",
                "X-Empty:",
            )
        )
        assertEquals("Digest nonce=\"a:b\"", headers["authorization"])
        assertEquals("", headers["x-empty"])
    }

    @Test
    fun `header-less lines and leading-colon lines are skipped`() {
        val headers = RtspRequestParser.parseHeaders(
            listOf(
                "OPTIONS / RTSP/1.0",
                "NoColonHere",
                ": leading-colon-value",
                "CSeq: 1",
            )
        )
        assertEquals(mapOf("cseq" to "1"), headers)
    }

    @Test
    fun `request line never leaks into the header map despite colons`() {
        val headers = RtspRequestParser.parseHeaders(
            listOf("GET rtsp://h:8554/stream RTSP/1.0", "CSeq: 1")
        )
        assertEquals(mapOf("cseq" to "1"), headers)
        assertFalse(headers.containsKey("get rtsp"))
    }

    // ── content length ──

    @Test
    fun `content length is case-insensitive and trimmed`() {
        assertEquals(42, RtspRequestParser.extractContentLength(listOf("x", "Content-Length: 42")))
        assertEquals(7, RtspRequestParser.extractContentLength(listOf("x", "content-length:  7 ")))
        assertEquals(5, RtspRequestParser.extractContentLength(listOf("x", "Content-Length : 5")))
    }

    @Test
    fun `content length uses first-header-wins unlike the header map`() {
        val lines = listOf("SET_PARAMETER / RTSP/1.0", "Content-Length: 5", "Content-Length: 9")
        assertEquals(5, RtspRequestParser.extractContentLength(lines))
        // Contrast: the header map itself keeps the last value.
        assertEquals("9", RtspRequestParser.parseHeaders(lines)["content-length"])
    }

    @Test
    fun `missing or unparsable content length is zero`() {
        assertEquals(0, RtspRequestParser.extractContentLength(listOf("x", "CSeq: 1")))
        assertEquals(0, RtspRequestParser.extractContentLength(listOf("x", "Content-Length: abc")))
        assertEquals(0, RtspRequestParser.extractContentLength(listOf("x", "Content-Length: ")))
        assertEquals(0, RtspRequestParser.extractContentLength(emptyList()))
        // Same prefix but a different header name does not match.
        assertEquals(0, RtspRequestParser.extractContentLength(listOf("x", "Content-LengthX: 5")))
    }

    // ── RtspWireReader ──

    @Test
    fun `readLine reassembles a line consuming crlf and honoring the pre-read byte`() {
        val stream = "DESCRIBE rtsp://x RTSP/1.0\r\nCSeq: 1\r\n\r\n".byteInputStream()
        val reader = RtspWireReader(stream)

        val first = stream.read()
        assertEquals("DESCRIBE rtsp://x RTSP/1.0", reader.readLine(first))
        val second = stream.read()
        assertEquals("CSeq: 1", reader.readLine(second))
        // The blank line ending the header block is a valid empty result.
        assertEquals("", reader.readLine(stream.read()))
    }

    @Test
    fun `readLine accepts bare lf terminators and decodes utf-8`() {
        val stream = "héllo\nX".byteInputStream()
        val reader = RtspWireReader(stream)
        assertEquals("héllo", reader.readLine(stream.read()))
    }

    @Test
    fun `readLine returns null at eof mid-line and on an exhausted stream`() {
        val reader = RtspWireReader("abc".byteInputStream())
        assertNull(reader.readLine('a'.code))
        assertNull(reader.readLine(-1))
        // CR immediately followed by EOF is still an unterminated line.
        val reader2 = RtspWireReader("abc\r".byteInputStream())
        assertNull(reader2.readLine('a'.code))
    }

    @Test
    fun `readLine drops bare carriage returns inside the line`() {
        val stream = "a\rb\rc\n".byteInputStream()
        val reader = RtspWireReader(stream)
        assertEquals("abc", reader.readLine(stream.read()))
    }

    @Test
    fun `discardBytes consumes exactly the requested bytes`() {
        val stream = "1234567890\n".byteInputStream()
        val reader = RtspWireReader(stream)
        val first = stream.read() // caller already consumed '1'
        assertTrue(reader.discardBytes(0))
        assertTrue(reader.discardBytes(4)) // drops '2'..'5'
        // The pre-read byte plus the untouched remainder forms the next line.
        assertEquals("167890", reader.readLine(first))
        // Stream is now exhausted.
        assertFalse(reader.discardBytes(1))
    }

    @Test
    fun `discardBytes fails when the stream ends before the count is reached`() {
        val reader = RtspWireReader(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertFalse(reader.discardBytes(4))
        // A request within the remaining bytes still succeeds.
        val reader2 = RtspWireReader(ByteArrayInputStream(ByteArray(4096)))
        assertTrue(reader2.discardBytes(4096))
    }
}

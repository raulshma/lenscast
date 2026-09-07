package com.raulshma.lenscast.streaming.rtsp

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One parsed RTSP request: the request line plus headers. Pure data — the
 * wire loop in [RtspServer.ClientSession] collects lines and hands them to
 * [RtspRequestParser.parse].
 */
class RtspRequest(
    val method: String,
    val uri: String,
    /** Header names lowercased; a later duplicate overwrites the earlier one. */
    val headers: Map<String, String>,
    /**
     * Content-Length as the wire loop must honor it: the first Content-Length
     * header on the message wins — deliberately NOT [headers]' last-wins
     * semantics.
     */
    val contentLength: Int,
)

/**
 * Pure parse of one RTSP request from its collected header lines. Android-
 * free so request-line and header handling is JVM-tested.
 */
object RtspRequestParser {

    /** Null when the request line is missing or has fewer than three parts — no response is owed. */
    fun parse(lines: List<String>): RtspRequest? {
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null

        return RtspRequest(
            method = parts[0],
            uri = parts[1],
            headers = parseHeaders(lines),
            contentLength = extractContentLength(lines),
        )
    }

    /** Parses header lines (everything after the request line) into a lowercase-keyed map. */
    fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colonIdx = lines[i].indexOf(':')
            if (colonIdx > 0) {
                val key = lines[i].substring(0, colonIdx).trim().lowercase()
                val value = lines[i].substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    /**
     * The Content-Length the wire loop must skip: first matching header wins,
     * unparsable/absent means 0.
     */
    fun extractContentLength(lines: List<String>): Int {
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim()
            if (!key.equals("Content-Length", ignoreCase = true)) continue
            return line.substring(colonIdx + 1).trim().toIntOrNull() ?: 0
        }
        return 0
    }
}

/**
 * The RTSP wire-reading primitives over a plain [InputStream] (the interleaved
 * `$`-frame magic and line collection stay in the server's socket loop; only
 * the byte mechanics live here). Android-free, tested with ByteArrayInputStream.
 */
class RtspWireReader(private val input: InputStream) {

    /**
     * Reads one CRLF/CRLF-less terminated line, having already consumed
     * [firstByte] from the stream. Null on EOF mid-line; an empty string is a
     * valid result (the blank line ending a request's header block).
     */
    fun readLine(firstByte: Int): String? {
        val lineBuffer = ByteArrayOutputStream(128)
        var current = firstByte

        while (true) {
            if (current < 0) return null
            if (current == '\n'.code) {
                break
            }
            if (current != '\r'.code) {
                lineBuffer.write(current)
            }
            current = input.read()
        }

        return lineBuffer.toString(Charsets.UTF_8.name())
    }

    /** Reads and drops exactly [byteCount] bytes; false when the stream ends first. */
    fun discardBytes(byteCount: Int): Boolean {
        var remaining = byteCount
        val discardBuffer = ByteArray(2048)

        while (remaining > 0) {
            val toRead = minOf(remaining, discardBuffer.size)
            val read = input.read(discardBuffer, 0, toRead)
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }
}

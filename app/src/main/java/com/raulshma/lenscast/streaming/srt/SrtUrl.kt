package com.raulshma.lenscast.streaming.srt

import com.raulshma.lenscast.core.StreamDefaults

/**
 * The SRT push endpoint parsed out of its URL — the pure mapper between what
 * the user configures and what the publisher needs (the RTMP push URL's
 * twin):
 *
 * `srt://[user:pass@]host[:port][?streamid=…&…]` — a portless authority
 * defaults to [DEFAULT_PORT] (9710, the IANA-registered SRT value).
 *
 * - [host] and [port] are the UDP endpoint;
 * - [username]/[password] come from the URL userinfo (rare, but some
 *   endpoints carry them in the stream id instead — both stay write-only);
 * - [streamId] is the raw `streamid` query value, never percent-decoded (the
 *   SRT stream id is an opaque token both ends see verbatim);
 * - every other query parameter is tolerated and ignored, so a target pasted
 *   from an NVR (`?mode=caller&latency=120`) still parses.
 *
 * Strict where silence would lie: a null return means "not a usable SRT push
 * URL" and the caller surfaces the readable error. Pure Kotlin, no Android —
 * JVM-tested including fuzz.
 */
data class SrtUrl(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val streamId: String?,
) {
    /** host[:port], the port shown only when it differs from the default. */
    val hostAndPort: String
        get() = if (port == DEFAULT_PORT) host else "$host:$port"

    /**
     * The log-safe rendering: userinfo and stream id are credentials, so
     * neither ever reaches a log line (this exists for the readable start
     * message; the output itself never echoes the raw URL).
     */
    fun redacted(): String {
        val stream = if (streamId != null) "streamid=<redacted>" else ""
        return "srt://$hostAndPort" + (if (stream.isEmpty()) "" else "?$stream")
    }

    companion object {
        const val DEFAULT_PORT = StreamDefaults.SRT_PORT_DEFAULT

        /** The tolerant decode: null for anything not a usable push URL. */
        fun parse(raw: String): SrtUrl? {
            val url = raw.trim()
            if (!url.lowercase().startsWith("srt://")) return null
            val afterScheme = url.substring("srt://".length)

            val slash = afterScheme.indexOf('/')
            val queryStart = afterScheme.indexOf('?')
            val authorityEnd = when {
                slash >= 0 && (queryStart < 0 || slash < queryStart) -> slash
                queryStart >= 0 -> queryStart
                else -> afterScheme.length
            }
            val authority = afterScheme.substring(0, authorityEnd)
            if (authority.isBlank()) return null
            val rest = afterScheme.substring(authorityEnd)

            // userinfo@host:port — credentials optional, split on the first
            // ':' (a password may contain colons).
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            if (hostPort.isBlank() || hostPort.startsWith(":")) return null
            var username: String? = null
            var password: String? = null
            if (!userInfo.isNullOrBlank()) {
                val colon = userInfo.indexOf(':')
                if (colon >= 0) {
                    username = userInfo.substring(0, colon).takeIf { it.isNotEmpty() }
                    password = userInfo.substring(colon + 1).takeIf { it.isNotEmpty() }
                } else {
                    username = userInfo
                }
            }

            // host[:port] — split on the last ':' only when the tail parses
            // as a port, so IPv6 literals survive.
            var host = hostPort
            var port = DEFAULT_PORT
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0 && hostPort.indexOf(']') < colon) {
                val parsed = hostPort.substring(colon + 1).toIntOrNull()
                if (parsed != null && parsed in 1..65535) {
                    host = hostPort.substring(0, colon)
                    port = parsed
                }
            }
            if (host.isBlank()) return null

            // The streamid query value, kept raw. A path segment (the slash
            // form `srt://host:port/streamid`) is tolerated as the stream id
            // the same way — some tools write it there.
            var streamId: String? = null
            val qmark = rest.indexOf('?')
            if (qmark >= 0) {
                val pathPart = rest.substring(0, qmark).trimStart('/')
                val query = rest.substring(qmark + 1)
                if (pathPart.isNotEmpty()) {
                    streamId = pathPart
                }
                for (pair in query.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq <= 0) continue
                    if (pair.substring(0, eq) == "streamid") {
                        streamId = pair.substring(eq + 1).takeIf { it.isNotEmpty() } ?: streamId
                    }
                }
            } else {
                val pathPart = rest.trimStart('/')
                if (pathPart.isNotEmpty()) streamId = pathPart
            }

            return SrtUrl(
                host = host,
                port = port,
                username = username,
                password = password,
                streamId = streamId,
            )
        }
    }
}

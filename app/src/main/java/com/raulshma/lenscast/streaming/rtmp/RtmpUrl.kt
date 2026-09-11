package com.raulshma.lenscast.streaming.rtmp

/**
 * The RTMP push endpoint parsed out of its URL — the one pure mapper between
 * what the user configures and what the publisher needs on the wire:
 *
 * `rtmp://[user:pass@]host[:port]/app/streamKey` (or `rtmps://` for TLS) —
 * a portless authority defaults to 1935 for `rtmp` and 443 for `rtmps`
 *
 * - [app] carries every path segment except the last (multi-level apps like
 *   `live/extra` stay whole) plus any `?query` (nginx-rtmp auth reads
 *   credentials from the app query string);
 * - [streamKey] is the last path segment and must be non-blank;
 * - [username]/[password] come from the URL userinfo and ride the connect
 *   command object;
 * - [tcUrl] is the connect command's `tcUrl` (`rtmp://host[:port]/app`).
 *
 * Tolerant where tolerance is free (blank path segments skipped, scheme case
 * ignored), strict where silence would lie: a null return means "not a usable
 * RTMP push URL", and the caller surfaces the readable error. Pure Kotlin, no
 * Android — JVM-tested.
 */
data class RtmpUrl(
    /** True for `rtmps://` — the socket is TLS. */
    val secure: Boolean,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    /** The app name (with optional query string), without leading slash. */
    val app: String,
    val streamKey: String,
) {
    /** `rtmp://host[:port]/app` — the connect command's tcUrl. */
    val tcUrl: String
        get() = "${if (secure) "rtmps" else "rtmp"}://$hostAndPort/$app"

    /** host[:port], the port shown only when it differs from the scheme's default. */
    val hostAndPort: String
        get() = if (port == defaultPort) host else "$host:$port"

    private val defaultPort: Int
        get() = if (secure) DEFAULT_SECURE_PORT else DEFAULT_PORT

    companion object {
        const val DEFAULT_PORT = 1935
        const val DEFAULT_SECURE_PORT = 443

        /** The tolerant decode: null for anything not a usable push URL. */
        fun parse(raw: String): RtmpUrl? {
            val url = raw.trim()
            val secure: Boolean
            val afterScheme: String
            val lower = url.lowercase()
            when {
                lower.startsWith("rtmp://") -> {
                    secure = false
                    afterScheme = url.substring("rtmp://".length)
                }
                lower.startsWith("rtmps://") -> {
                    secure = true
                    afterScheme = url.substring("rtmps://".length)
                }
                else -> return null
            }

            val slash = afterScheme.indexOf('/')
            if (slash <= 0) return null // no path at all, or no authority
            val authority = afterScheme.substring(0, slash)
            val pathAndQuery = afterScheme.substring(slash + 1)
            if (authority.isBlank()) return null

            // userinfo@host:port — credentials are optional and split on the
            // first ':' (a password may contain colons).
            val userInfo: String?
            val hostPort: String
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                userInfo = authority.substring(0, at)
                hostPort = authority.substring(at + 1)
            } else {
                userInfo = null
                hostPort = authority
            }
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

            // host[:port] — split on the last ':' only when the tail parses as
            // a port, so bracketed IPv6 literals survive (their colons are
            // inside brackets and the last ':' is the port separator at most).
            var host = hostPort
            var port = if (secure) DEFAULT_SECURE_PORT else DEFAULT_PORT
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0 && hostPort.indexOf(']') < colon) {
                val portPart = hostPort.substring(colon + 1)
                val parsed = portPart.toIntOrNull()
                if (parsed != null && parsed in 1..65535) {
                    host = hostPort.substring(0, colon)
                    port = parsed
                }
            }
            if (host.isBlank()) return null

            // path (and optional ?query): the query stays with the app —
            // nginx-rtmp reads auth from `app?user=..&pass=..`.
            val query: String
            val path: String
            val qmark = pathAndQuery.indexOf('?')
            if (qmark >= 0) {
                path = pathAndQuery.substring(0, qmark)
                query = pathAndQuery.substring(qmark + 1)
            } else {
                path = pathAndQuery
                query = ""
            }
            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.size < 2) return null // app + stream key are both required
            val app = if (query.isEmpty()) {
                segments.subList(0, segments.size - 1).joinToString("/")
            } else {
                segments.subList(0, segments.size - 1).joinToString("/") + "?" + query
            }
            if (app.isBlank()) return null

            return RtmpUrl(
                secure = secure,
                host = host,
                port = port,
                username = username,
                password = password,
                app = app,
                streamKey = segments.last(),
            )
        }
    }
}

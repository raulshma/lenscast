package com.raulshma.lenscast.streaming.whip

/**
 * The WHIP push endpoint parsed out of its URL — the one pure mapper between
 * what the user configures and what the signaling needs on the wire
 * (RFC 9725's configured publish resource):
 *
 * `http(s)://[user:pass@]host[:port]/endpoint[?query]`
 *
 * - the path is the WHIP resource the offer is POSTed to, one non-blank
 *   segment at least; a `?query` stays with the path (some services embed
 *   the publish id there);
 * - [username]/[password] come from the URL userinfo and ride an HTTP Basic
 *   Authorization header — RFC 9725 expects a bearer token instead, which is
 *   the separate `whip_token` setting ([WhipSignaling.authorizationHeader]
 *   prefers it over URL Basic);
 * - plain `http://` is accepted (a LAN WHIP server such as MediaMTX);
 *   `https://` is validated with the system trust stores — LensCast has no
 *   client-side self-signed-trust helper (the TlsCertManager issues the
 *   phone's *server* identity only), so there is no `whips://` scheme and no
 *   trust-all opt-in: a private CA must be installed as a user credential.
 *
 * Tolerant where tolerance is free (blank path segments skipped, scheme case
 * ignored), strict where silence would lie: a null return means "not a usable
 * WHIP endpoint URL", and the caller surfaces the readable error. Pure
 * Kotlin, no Android — JVM-tested.
 */
data class WhipUrl(
    /** True for `https://`. */
    val secure: Boolean,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    /** The resource path (with optional query string), with the leading slash. */
    val resourcePath: String,
) {
    /** The full URL the offer is POSTed to. */
    val resourceUrl: String
        get() = "${if (secure) "https" else "http"}://$hostAndPort$resourcePath"

    /** host[:port], the port shown only when it differs from the scheme default. */
    val hostAndPort: String
        get() = if (port == defaultPort) host else "$host:$port"

    /** The scheme's default port — 443 for https, 80 for http. */
    val defaultPort: Int
        get() = if (secure) 443 else 80

    companion object {
        /** The tolerant decode: null for anything not a usable WHIP endpoint. */
        fun parse(raw: String): WhipUrl? {
            val url = raw.trim()
            val secure: Boolean
            val afterScheme: String
            val lower = url.lowercase()
            when {
                lower.startsWith("http://") -> {
                    secure = false
                    afterScheme = url.substring("http://".length)
                }
                lower.startsWith("https://") -> {
                    secure = true
                    afterScheme = url.substring("https://".length)
                }
                else -> return null
            }

            val slash = afterScheme.indexOf('/')
            if (slash <= 0) return null // no path at all, or no authority
            val authority = afterScheme.substring(0, slash)
            val pathAndQuery = afterScheme.substring(slash)
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
            var port = if (secure) 443 else 80
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

            val segments = pathAndQuery.split('?').first().split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null // a bare "/" is not an endpoint

            return WhipUrl(
                secure = secure,
                host = host,
                port = port,
                username = username,
                password = password,
                resourcePath = pathAndQuery,
            )
        }
    }
}

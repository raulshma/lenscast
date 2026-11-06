package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.core.StreamDefaults

/**
 * The pure derivations the connect sheet's composition used to inline: which
 * port the USB-forwarding instruction quotes, and whether the HTTP URL is
 * the TLS scheme. Extracted verbatim — this policy pins the sheet's current
 * behavior, quirks included; it is a truth seam, not idealized URL parsing.
 *
 * Port parsing truth (pinned by [currentPort], do not "fix" without
 * re-pinning the sheet's output):
 *
 * - The slice is everything after the FIRST colon, up to (before) the FIRST
 *   slash, parsed with `toIntOrNull` (lenient: an optional sign survives,
 *   there is no port-range check, out-of-Int-range digits fail to null).
 * - Every schemed URL — `scheme://host:port/path`, the only shape the
 *   StreamingManager's URL builders produce, IPv4 or bracketed IPv6 alike —
 *   resolves to [StreamDefaults.WEB_PORT]: the first colon is the scheme's,
 *   so the slice starts at `//`, and that `//`'s own leading slash ends it,
 *   leaving an empty string. A custom server port is therefore NOT quoted
 *   today; only a scheme-less `host:port(/path)` string yields the real
 *   port.
 */
object ConnectInfoPolicy {

    /**
     * The port the connect sheet's USB-forwarding instruction quotes,
     * defaulting to [StreamDefaults.WEB_PORT] whenever the slice above does
     * not parse as an Int.
     */
    fun currentPort(httpUrl: String): Int =
        httpUrl.substringAfter(":", "").substringBefore("/").toIntOrNull() ?: StreamDefaults.WEB_PORT

    /**
     * Whether [httpUrl] is the TLS scheme — the exact case-sensitive
     * `https://` prefix check the sheet's fingerprint gate rode.
     */
    fun isSecureHttp(httpUrl: String): Boolean = httpUrl.startsWith("https://")
}

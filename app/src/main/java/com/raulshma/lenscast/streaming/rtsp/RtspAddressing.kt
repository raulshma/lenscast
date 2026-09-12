package com.raulshma.lenscast.streaming.rtsp

/**
 * Pure RTSP listener addressing knowledge: the dual-stack bind literal, the
 * v4-mapped→IPv4 SDP advertisement normalization, the SDP network-type
 * selection, and the URL bracket rule for IPv6 literals. Android-free so the
 * advertisement behavior is JVM-tested; [RtspServer] applies the answers to
 * its bind and its DESCRIBE/Content-Base outputs.
 */
object RtspAddressing {

    /**
     * The listener bind host: IPv6 any-address. A `::`-bound socket accepts
     * IPv4 connections as v4-mapped addresses on dual-stack hosts (every
     * Android device), so one listener serves both families — the old
     * `0.0.0.0` bind refused IPv6 peers outright.
     */
    const val BIND_HOST = "::"

    fun isIpv6(host: String): Boolean = host.contains(':')

    /** The SDP network type for an advertised host: `IP6` when it is an IPv6 literal, else `IP4`. */
    fun networkType(host: String): String = if (isIpv6(host)) "IP6" else "IP4"

    /** The SDP connection address matching [networkType]: the unspecified address of that family. */
    fun connectionAddress(networkType: String): String = if (networkType == "IP6") "::" else "0.0.0.0"

    /** Bracket an IPv6 literal for use inside a URL authority (`rtsp://[::1]:8554/…`); IPv4/hosts pass through. */
    fun urlHost(host: String): String = if (isIpv6(host)) "[$host]" else host

    /**
     * The host an SDP/URL should advertise for a listener-side address:
     * a v4-mapped IPv6 literal (`::ffff:192.168.1.5` — what an accepted
     * socket's local address looks like on a dual-stack bind) reduces to plain
     * IPv4, the unspecified addresses (`0.0.0.0`, `::`) answer null (the
     * caller falls back to a real interface address), and anything else — a
     * concrete IPv4 or genuine IPv6 literal — passes through trimmed.
     */
    fun advertisedHost(rawHost: String?): String? {
        if (rawHost.isNullOrBlank()) return null
        val host = rawHost.trim()
        if (host == "0.0.0.0" || host == "::") return null
        val mapped = V4_MAPPED_REGEX.matchEntire(host) ?: return host
        return mapped.groupValues[1]
    }

    private val V4_MAPPED_REGEX = Regex(
        pattern = "^::ffff:(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$",
        option = RegexOption.IGNORE_CASE,
    )
}

package com.raulshma.lenscast.companion.discovery

import com.raulshma.lenscast.core.StreamDefaults

/**
 * The pure half of the pairing sheet's LAN radar — every decision
 * [NsdDiscoveryClient] makes about a discovered `_http._tcp` record, kept
 * Android-free so the JVM tests can pin it (NsdManager itself has no JVM
 * seam; the client stays a thin shell over these verdicts).
 *
 * Two decisions: is this record a LensCast device at all (the TXT contract
 * the camera app's mDNS registration publishes — other phones, printers and
 * AirPlay boxes also shout `_http._tcp` on a home LAN, so the service type
 * alone cannot be the gate), and what should the UI call it (NsdManager's
 * resolved service name sometimes arrives trailing the DN suffix the
 * resolver merged in, which must never render as part of the device name).
 *
 * The QR pairing-payload parser lives here too: the camera app's connect
 * sheet encodes `http(s)://host:port(#fp=<sha256>)` — the same string the
 * manual-pairing field accepts — and its decode is a pure string problem.
 */
object NsdDiscoveryFilter {

    /**
     * The TXT key/value that marks a record as a LensCast device. The camera
     * side publishes it (`streaming/ServiceDiscoveryManager`, through
     * [com.raulshma.lenscast.streaming.DiscoveryRegistrationPolicy]), the
     * discovery client keys on it — one contract, so the pair re-exports the
     * policy's constants (compile-time inlined: the filter stays pure and
     * JVM-testable) rather than spelling its own copy.
     */
    const val LENSCAST_TXT_KEY =
        com.raulshma.lenscast.streaming.DiscoveryRegistrationPolicy.LENSCAST_TXT_KEY
    const val LENSCAST_TXT_VALUE =
        com.raulshma.lenscast.streaming.DiscoveryRegistrationPolicy.LENSCAST_TXT_VALUE

    /** The mDNS suffix NsdManager's resolved names can arrive wearing. */
    private const val SERVICE_TYPE_SUFFIX = "._http._tcp"

    /**
     * The LensCast verdict for one discovered record: true only when the TXT
     * map carries the [LENSCAST_TXT_KEY]=`1` contract. The service name is
     * accepted (and ignored) to keep the call site honest — a name alone
     * proves nothing, since every `_http._tcp` printer on the LAN is also
     * called something plausible. Keys normalize to lowercase because
     * NsdManager hands TXT attributes back lowercased, and hand-built maps
     * in tests should not depend on that.
     */
    fun isLensCastService(serviceName: String, txtRecords: Map<String, String>): Boolean =
        txtRecords.entries
            .firstOrNull { it.key.equals(LENSCAST_TXT_KEY, ignoreCase = true) }
            ?.value?.trim() == LENSCAST_TXT_VALUE

    /**
     * Excludes this device's own mDNS advertisement from discovery results:
     * a resolved [host] matching any of this device's local interface
     * addresses (or loopback) belongs to this device itself (issue #26).
     */
    fun isSelfService(host: String, localAddresses: Set<String>): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return false
        if (normalized == "127.0.0.1" || normalized.equals("::1", ignoreCase = true) ||
            normalized.equals("0:0:0:0:0:0:0:1", ignoreCase = true)
        ) {
            return true
        }
        return localAddresses.any { normalizeHost(it).equals(normalized, ignoreCase = true) }
    }

    private fun normalizeHost(host: String): String =
        host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')

    /**
     * Resolve-queue ordering hint only (never an acceptance filter — TXT
     * records arrive only after resolve). Services whose name starts with
     * `LensCast` move to the front of the sequential resolve queue so busy LANs
     * resolve LensCast servers before unrelated `_http._tcp` printers.
     */
    fun isPrioritizedCandidate(serviceName: String): Boolean =
        friendlyName(serviceName).startsWith("LensCast", ignoreCase = true)

    /**
     * The display name for a discovered record: the resolver's service name
     * with the `._http._tcp.` DN suffix stripped (NsdManager occasionally
     * delivers the fully-qualified name), trimmed of residual dots —
     * `LensCast-Pixel8._http._tcp.` renders as `LensCast-Pixel8`, and a
     * name that never wore the suffix passes through untouched.
     */
    fun friendlyName(serviceName: String): String {
        // Trailing dots first: the fully-qualified form ends "._http._tcp.",
        // and the suffix check must run against the dot-stripped name.
        var name = serviceName.trim().trimEnd('.')
        if (name.endsWith(SERVICE_TYPE_SUFFIX, ignoreCase = true)) {
            name = name.removeSuffix(SERVICE_TYPE_SUFFIX)
        }
        return name.trimEnd('.').trim()
    }

    /**
     * Decodes the camera app's connect-sheet QR payload —
     * `http(s)://host:port(#fp=<sha256>)` — into a [PairingPayload], or null
     * when the string is not URL-shaped at all (a mis-scanned code carrying
     * text, wifi credentials, or a non-HTTP scheme; lenient everywhere else).
     *
     * Leniency contract: a missing port folds to the scheme's default
     * ([StreamDefaults.WEB_PORT] for http, 443 for https), a trailing path is
     * ignored, a bracketed IPv6 host keeps its address, and the `#fp=`
     * fragment normalizes to bare lowercase hex (the fingerprint renders
     * colon-separated uppercase — the connect sheet's format — which must
     * compare equal to a pasted lowercase hex digest). A non-numeric port,
     * an out-of-range port, or an empty host still rejects: those are
     * corrupt payloads, not lenient ones.
     */
    fun parsePairingPayload(raw: String): PairingPayload? {
        val withoutFragment = raw.trim()
        if (withoutFragment.isEmpty()) return null

        // The TLS fingerprint rides the fragment (#fp=hex) — split it off
        // before any URL structural parsing, since '#' is not a URL delimiter
        // the authority slice below respects.
        val body = withoutFragment.substringBefore("#")
        val fragment = withoutFragment.substringAfter("#", missingDelimiterValue = "")
        val fingerprint = normalizeFingerprint(
            if (fragment.startsWith("fp=")) fragment.removePrefix("fp=") else fragment,
        )

        val (scheme, useTls) = when {
            body.startsWith(SCHEME_HTTP, ignoreCase = true) -> SCHEME_HTTP to false
            body.startsWith(SCHEME_HTTPS, ignoreCase = true) -> SCHEME_HTTPS to true
            else -> return null
        }
        val authority = body.substring(scheme.length).substringBefore("/")
        if (authority.isEmpty()) return null

        val host: String
        val port: Int
        if (authority.startsWith("[")) {
            // Bracketed IPv6: the address runs to ']', an optional port
            // follows on ':'.
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(1, end)
            val portText = authority.substring(end + 1).removePrefix(":")
            port = if (portText.isEmpty()) defaultPort(useTls) else portText.toIntOrNull() ?: return null
        } else {
            val colon = authority.indexOf(':')
            if (colon < 0) {
                host = authority
                port = defaultPort(useTls)
            } else {
                host = authority.substring(0, colon)
                val portText = authority.substring(colon + 1)
                port = portText.toIntOrNull() ?: return null
            }
        }
        if (host.isEmpty() || host.any { it.isWhitespace() } || host.contains('@')) return null
        if (port !in 1..65535) return null

        return PairingPayload(
            host = host,
            port = port,
            useTls = useTls,
            tlsFingerprint = fingerprint,
        )
    }

    /** The scheme's default port when the payload omits one. */
    private fun defaultPort(useTls: Boolean): Int = if (useTls) DEFAULT_TLS_PORT else StreamDefaults.WEB_PORT

    /**
     * The fingerprint's comparison form: colon/space separators stripped,
     * lowercase hex — so the connect sheet's `AB:CD:…` rendering and a
     * bare `abcd…` digest both land in the same shape the TLS pinning
     * compares. Garbage (non-hex) is kept as-is: the pairing flow's TOFU
     * confirm, not the parser, owns trusting or rejecting it.
     */
    private fun normalizeFingerprint(fragment: String): String? =
        fragment.filter { it != ':' && !it.isWhitespace() }
            .lowercase()
            .takeIf { it.isNotEmpty() }

    private const val SCHEME_HTTP = "http://"
    private const val SCHEME_HTTPS = "https://"
    private const val DEFAULT_TLS_PORT = 443
}

/**
 * One decoded pairing payload: where to dial the device ([host]/[port]),
 * whether to dial it over TLS ([useTls]), and the pinned certificate
 * fingerprint when the QR carried one (null → the pairing flow falls back
 * to the trust-on-first-use confirm).
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val tlsFingerprint: String? = null,
)

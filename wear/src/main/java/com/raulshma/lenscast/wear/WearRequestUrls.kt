package com.raulshma.lenscast.wear

/**
 * The pure URL builder for the phone's HTTP API — the one JVM-testable seam
 * in the module, kept free of Android and OkHttp types so the small JUnit
 * suite under src/test can pin the exact wire paths.
 *
 * Every endpoint this remote consumes resolves here, single-sourced: the
 * settings test (status + system), the poll (status), the snapshot, and the
 * two write commands. The host field is forgiving by design — users paste
 * `http://192.168.1.20` as often as `192.168.1.20` — and this is the place
 * that forgiveness is normalized once.
 */
object WearRequestUrls {

    /**
     * The base `http://host:port` for a raw settings pair. A host the user
     * typed WITH a scheme keeps it (so an https-fronted proxy still works);
     * a bare host gets `http://` — the phone's LAN server is plain HTTP.
     * Whitespace and trailing slashes are trimmed; a blank host yields an
     * empty string that callers treat as "unconfigured".
     */
    fun baseUrl(host: String, port: Int): String {
        val trimmed = host.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return "$withScheme:$port"
    }

    /** GET /api/status — the poll: stream state, battery, camera, thermal. */
    fun status(host: String, port: Int): String = "${baseUrl(host, port)}/api/status"

    /**
     * GET /api/system — the read-only diagnostics snapshot. Only the test
     * button calls it, to surface device model + app version on success.
     */
    fun system(host: String, port: Int): String = "${baseUrl(host, port)}/api/system"

    /** GET /snapshot — the current preview frame as JPEG bytes. */
    fun snapshot(host: String, port: Int): String = "${baseUrl(host, port)}/snapshot"

    /** POST /api/stream/start — mirrors the dashboard's stream toggle. */
    fun streamStart(host: String, port: Int): String = "${baseUrl(host, port)}/api/stream/start"

    /** POST /api/stream/stop — the other half of the toggle. */
    fun streamStop(host: String, port: Int): String = "${baseUrl(host, port)}/api/stream/stop"

    /** POST /api/capture — a photo through the phone's capture pipeline. */
    fun capture(host: String, port: Int): String = "${baseUrl(host, port)}/api/capture"
}

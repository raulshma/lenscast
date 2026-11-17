package com.raulshma.lenscast.core

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The watchdog's loopback liveness probe: one bounded `HttpURLConnection`
 * GET to the server's own `/api/system` (blueprint P0 item 5; self-healing
 * §6's three non-negotiables). The [StreamingManager.isServerRunning] flag
 * is exactly the class of lie this exists to catch — a wedged accept loop or
 * exhausted API executor keeps the flag true while nothing answers.
 *
 * Invokable (`() -> Boolean`) so [StreamWatchdog] takes it as a plain lambda
 * seam — JVM tests inject a fake, no real HTTP. The verdict:
 * - **true** — any HTTP response below 500 arrived. A 401/403 with auth on
 *   is a *living* server; only the answer matters, never the payload.
 * - **false** — connect/read timeout, connection refused, or a 5xx: the
 *   server did not answer within the bounds. Any throw maps to false; the
 *   probe never raises into the watchdog's loop.
 *
 * Non-negotiables honored: its own connection (never the server's executor
 * or sockets), one-shot, short connect+read timeouts (a wedged server must
 * not hang the probe — or the watchdog tick — with the patient). Under a
 * retained TLS listener the certificate is the app's self-signed identity,
 * so the https form pins a trust-everything factory and hostname verifier —
 * sound here because the probe asks exactly one question (did it answer?)
 * and trusts no content, and the URL is loopback, never attacker-chosen.
 */
class LoopbackServerProbe(
    /** Supplies the loopback `/api/system` URL for the CURRENT transport (port/scheme change with it). */
    private val urlProvider: () -> String,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : () -> Boolean {

    override fun invoke(): Boolean = runCatching {
        val url = URL(urlProvider())
        // The TLS knobs live on HttpsURLConnection; a retained TLS listener
        // gets the trust-everything factory, plain http goes through bare.
        val connection: HttpURLConnection =
            if (url.protocol.equals("https", ignoreCase = true)) {
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = trustingSocketFactory()
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
            } else {
                url.openConnection() as HttpURLConnection
            }
        connection.apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            requestMethod = "GET"
        }
        try {
            connection.responseCode in 200..499
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    companion object {
        /** ~2 s connect: enough for a healthy loopback, short enough to bound the watchdog tick. */
        const val CONNECT_TIMEOUT_MS = 2_000

        /** ~2 s read: the whole failure class is an answer that never comes. */
        const val READ_TIMEOUT_MS = 2_000

        /**
         * The probe-only trust-everything factory — liveness asks whether the
         * socket answers, not who it is; created once and reused.
         */
        private val trustingFactory: SSLSocketFactory by lazy {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }), null)
            context.socketFactory
        }

        private fun trustingSocketFactory(): SSLSocketFactory = trustingFactory
    }
}

package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.UpdateCheckResult
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP half of the update stack, minus the transport itself: the pure
 * status/parse outcome -> [UpdateCheckResult] mapping ([mapCheckOutcome])
 * and the one GitHub connection setup ([openConnection]) shared by the
 * release check ([UpdateChecker]) and the APK download ([UpdateDownloader]).
 * No Android dependencies — unit-tested on the JVM.
 */
object UpdateHttp {

    private const val USER_AGENT = "LensCast"

    /**
     * The HTTP outcome of a release check, exactly as the network checker
     * mapped it inline: 403 is the GitHub rate limit
     * ([UpdateCheckResult.RateLimited]), any other non-200 is
     * [UpdateCheckResult.Error] carrying the status code alone (the error
     * body is call-site diagnostics; it never reaches a message), and a 200
     * whose body did not parse is the parse-failure error. On a parsed 200
     * the caller's own success wrap ([parsed] — UpdateAvailable or UpToDate)
     * passes through unchanged.
     */
    fun mapCheckOutcome(statusCode: Int, parsed: UpdateCheckResult?): UpdateCheckResult =
        when {
            statusCode == 403 -> UpdateCheckResult.RateLimited
            statusCode != 200 -> UpdateCheckResult.Error("Server returned HTTP $statusCode")
            parsed == null -> UpdateCheckResult.Error("Failed to parse release JSON")
            else -> parsed
        }

    /**
     * The one GitHub connection: [url] opened as an [HttpURLConnection] with
     * the caller's timeouts, the LensCast User-Agent, and redirect following.
     * Request-specific headers (e.g. the release check's Accept) stay with
     * the caller.
     */
    fun openConnection(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        applyDefaults(URL(url).openConnection() as HttpURLConnection, connectTimeoutMs, readTimeoutMs)

    /**
     * The shared settings, applied to any [HttpURLConnection] so tests can
     * pin them against a recorded fake.
     */
    fun applyDefaults(conn: HttpURLConnection, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection = conn.apply {
        this.connectTimeout = connectTimeoutMs
        this.readTimeout = readTimeoutMs
        setRequestProperty("User-Agent", USER_AGENT)
        instanceFollowRedirects = true
    }
}

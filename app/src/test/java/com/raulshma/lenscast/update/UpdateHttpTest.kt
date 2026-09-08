package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP-outcome mapping and the shared GitHub connection defaults under
 * JVM tests: 403 vs error status vs unparseable body, the caller's parsed
 * success passing through, and the LensCast connection settings both the
 * release check and the download share.
 */
class UpdateHttpTest {

    // mapCheckOutcome

    @Test
    fun `403 maps to rate limited`() {
        assertEquals(
            UpdateCheckResult.RateLimited,
            UpdateHttp.mapCheckOutcome(403, parsed = null),
        )
    }

    @Test
    fun `other non-200 maps to the status-code error`() {
        // The message carries the status code alone — there is no body
        // parameter to leak into it.
        assertEquals(
            UpdateCheckResult.Error("Server returned HTTP 500"),
            UpdateHttp.mapCheckOutcome(500, parsed = null),
        )
    }

    @Test
    fun `a 200 with no parsed result is the parse error`() {
        assertEquals(
            UpdateCheckResult.Error("Failed to parse release JSON"),
            UpdateHttp.mapCheckOutcome(200, parsed = null),
        )
    }

    @Test
    fun `a 200 with a parsed result passes it through unchanged`() {
        val release = GitHubRelease(
            tagName = "v1.2.0",
            name = "Release v1.2.0",
            body = "Notes",
            htmlUrl = "https://example.invalid",
            assets = listOf(GitHubAsset("app-universal.apk", "https://example.invalid/app-universal.apk", 1024L)),
        )
        val available = UpdateCheckResult.UpdateAvailable(release, release.assets.first())
        assertEquals(available, UpdateHttp.mapCheckOutcome(200, parsed = available))
    }

    // connection factory

    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
    }

    @Test
    fun `the connection defaults are the timeouts, the LensCast user agent, and redirects`() {
        val connection = UpdateHttp.applyDefaults(FakeConnection(URL("http://example.invalid/app.apk")), 30_000, 60_000)
        assertEquals(30_000, connection.connectTimeout)
        assertEquals(60_000, connection.readTimeout)
        assertEquals("LensCast", connection.requestProperties["User-Agent"]?.single())
        assertTrue(connection.instanceFollowRedirects)
    }

    @Test
    fun `an opened connection carries the same defaults`() {
        val connection = UpdateHttp.openConnection("http://example.invalid/latest", 15_000, 15_000)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertEquals("LensCast", connection.requestProperties["User-Agent"]?.single())
        assertTrue(connection.instanceFollowRedirects)
    }
}

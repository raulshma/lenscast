package com.raulshma.lenscast.streaming.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The WHIP endpoint parse/validate matrix — every wire-facing decision the
 * signaling builds on (scheme, authority, credentials, port, resource path)
 * pinned as pure JVM behavior, including the strict nulls for anything that
 * is not a usable endpoint.
 */
class WhipUrlTest {

    // ── the happy paths ──

    @Test
    fun `a plain http endpoint parses with the default port`() {
        val url = WhipUrl.parse("http://example.com/whip")!!
        assertEquals(false, url.secure)
        assertEquals("example.com", url.host)
        assertEquals(80, url.port)
        assertEquals("/whip", url.resourcePath)
        assertNull(url.username)
        assertNull(url.password)
        assertEquals("http://example.com/whip", url.resourceUrl)
        assertEquals("example.com", url.hostAndPort)
    }

    @Test
    fun `an https endpoint defaults to port 443`() {
        val url = WhipUrl.parse("https://whip.example.com/endpoint")!!
        assertEquals(true, url.secure)
        assertEquals(443, url.port)
        assertEquals("https://whip.example.com/endpoint", url.resourceUrl)
    }

    @Test
    fun `an explicit port overrides the default and hides from hostAndPort only when default`() {
        val url = WhipUrl.parse("http://192.168.1.10:8889/whip")!!
        assertEquals(8889, url.port)
        assertEquals("192.168.1.10:8889", url.hostAndPort)
        assertEquals("http://192.168.1.10:8889/whip", url.resourceUrl)

        val explicitDefault = WhipUrl.parse("http://example.com:80/whip")!!
        assertEquals(80, explicitDefault.port)
        assertEquals("example.com", explicitDefault.hostAndPort)
    }

    @Test
    fun `deep paths and multi-segment endpoints survive`() {
        val url = WhipUrl.parse("https://example.com/whip/my-camera/room")!!
        assertEquals("/whip/my-camera/room", url.resourcePath)
    }

    @Test
    fun `a query string stays with the resource path`() {
        val url = WhipUrl.parse("https://example.com/whip?publish=abc")!!
        assertEquals("/whip?publish=abc", url.resourcePath)
        assertEquals("https://example.com/whip?publish=abc", url.resourceUrl)
        // The query never counts as path segments.
        assertEquals(listOf("whip"), url.resourcePath.split('?').first().split('/').filter { it.isNotBlank() })
    }

    @Test
    fun `the scheme is case-insensitive`() {
        val http = WhipUrl.parse("HTTP://example.com/whip")!!
        assertEquals(false, http.secure)
        val https = WhipUrl.parse("HTTPS://example.com/whip")!!
        assertEquals(true, https.secure)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val url = WhipUrl.parse("  http://example.com/whip  ")!!
        assertEquals("example.com", url.host)
    }

    // ── credentials in the userinfo ──

    @Test
    fun `user and password split on the first colon so passwords keep theirs`() {
        val url = WhipUrl.parse("http://publish:se:cret@example.com/whip")!!
        assertEquals("publish", url.username)
        assertEquals("se:cret", url.password)
    }

    @Test
    fun `a bare username parses with a null password`() {
        val url = WhipUrl.parse("http://publish@example.com/whip")!!
        assertEquals("publish", url.username)
        assertNull(url.password)
    }

    @Test
    fun `an empty password segment parses as null`() {
        val url = WhipUrl.parse("http://publish:@example.com/whip")!!
        assertEquals("publish", url.username)
        assertNull(url.password)
    }

    // ── IPv6 literals ──

    @Test
    fun `a bracketed IPv6 literal with a port parses both`() {
        val url = WhipUrl.parse("http://[::1]:8000/whip")!!
        assertEquals("[::1]", url.host)
        assertEquals(8000, url.port)
        assertEquals("[::1]:8000", url.hostAndPort)
    }

    @Test
    fun `a bracketed IPv6 literal without a port keeps its colons`() {
        val url = WhipUrl.parse("http://[fe80::1]/whip")!!
        assertEquals("[fe80::1]", url.host)
        assertEquals(80, url.port)
    }

    // ── the strict side: nulls ──

    @Test
    fun `non-http schemes are refused`() {
        assertNull(WhipUrl.parse(""))
        assertNull(WhipUrl.parse("rtmp://example.com/live/key"))
        assertNull(WhipUrl.parse("rtsp://example.com/live"))
        assertNull(WhipUrl.parse("ftp://example.com/whip"))
        assertNull(WhipUrl.parse("example.com/whip"))
        assertNull(WhipUrl.parse("//example.com/whip"))
    }

    @Test
    fun `a missing path is refused`() {
        assertNull(WhipUrl.parse("http://"))
        assertNull(WhipUrl.parse("https://"))
        assertNull(WhipUrl.parse("http://example.com"))
        assertNull(WhipUrl.parse("https://example.com:8889"))
    }

    @Test
    fun `a bare root path is not an endpoint`() {
        assertNull(WhipUrl.parse("http://example.com/"))
        assertNull(WhipUrl.parse("http://example.com//"))
    }

    @Test
    fun `a blank authority is refused`() {
        assertNull(WhipUrl.parse("http:///whip"))
        assertNull(WhipUrl.parse("http:// /whip"))
        assertNull(WhipUrl.parse("http://@/whip"))
    }

    @Test
    fun `an out-of-range port never parses as a port`() {
        // Tolerant: the tail stays in the host string, the default port holds.
        val url = WhipUrl.parse("http://example.com:99999/whip")!!
        assertEquals(80, url.port)
        assertEquals("http://example.com:99999/whip", url.resourceUrl)
    }

    @Test
    fun `credentials before a missing host are refused`() {
        assertNull(WhipUrl.parse("http://user:pass@/whip"))
    }
}

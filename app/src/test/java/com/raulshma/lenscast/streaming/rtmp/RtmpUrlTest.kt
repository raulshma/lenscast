package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtmpUrlTest {

    // ── the happy paths ──

    @Test
    fun `parses the plain push url`() {
        val url = RtmpUrl.parse("rtmp://example.com/live/my-stream-key")!!
        assertEquals(false, url.secure)
        assertEquals("example.com", url.host)
        assertEquals(1935, url.port)
        assertNull(url.username)
        assertNull(url.password)
        assertEquals("live", url.app)
        assertEquals("my-stream-key", url.streamKey)
        assertEquals("rtmp://example.com/live", url.tcUrl)
    }

    @Test
    fun `parses rtmps as secure`() {
        val url = RtmpUrl.parse("rtmps://example.com/live/key")!!
        assertEquals(true, url.secure)
        assertEquals(443, url.port)
        assertEquals("rtmps://example.com/live", url.tcUrl)
        // The TLS default is 443 (1935 is the plaintext one), so a portless
        // rtmps authority dials 443 and hides it from hostAndPort/tcUrl.
        assertEquals("example.com", url.hostAndPort)
    }

    @Test
    fun `an explicit rtmps port lands and a non-default port is shown`() {
        val explicit = RtmpUrl.parse("rtmps://example.com:1935/live/key")!!
        assertEquals(1935, explicit.port)
        assertEquals("example.com:1935", explicit.hostAndPort)
        assertEquals("rtmps://example.com:1935/live", explicit.tcUrl)
    }

    @Test
    fun `scheme case is ignored`() {
        assertEquals("key", RtmpUrl.parse("RTMP://h/live/key")!!.streamKey)
        val url = RtmpUrl.parse("RtmpS://h/live/key")!!
        assertEquals(true, url.secure)
    }

    @Test
    fun `explicit port lands and the default port is hidden from tcUrl`() {
        val explicit = RtmpUrl.parse("rtmp://example.com:1936/live/key")!!
        assertEquals(1936, explicit.port)
        assertEquals("rtmp://example.com:1936/live", explicit.tcUrl)
        assertEquals("example.com:1936", explicit.hostAndPort)

        val defaulted = RtmpUrl.parse("rtmp://example.com/live/key")!!
        assertEquals("example.com", defaulted.hostAndPort)
        assertEquals("rtmp://example.com/live", defaulted.tcUrl)
    }

    @Test
    fun `userinfo credentials split on the first colon`() {
        // A password may contain colons; only the first colon separates user and pass.
        val url = RtmpUrl.parse("rtmp://user:pa:ss@example.com/live/key")!!
        assertEquals("user", url.username)
        assertEquals("pa:ss", url.password)

        val noPassword = RtmpUrl.parse("rtmp://justuser@example.com/live/key")!!
        assertEquals("justuser", noPassword.username)
        assertNull(noPassword.password)

        val emptyUser = RtmpUrl.parse("rtmp://:secret@example.com/live/key")!!
        assertNull(emptyUser.username)
        assertEquals("secret", emptyUser.password)
    }

    @Test
    fun `multi-level apps stay whole`() {
        val url = RtmpUrl.parse("rtmp://example.com/live/extra/key")!!
        assertEquals("live/extra", url.app)
        assertEquals("key", url.streamKey)
        assertEquals("rtmp://example.com/live/extra", url.tcUrl)
    }

    @Test
    fun `the query string rides the app`() {
        // nginx-rtmp reads auth credentials from the app query string.
        val url = RtmpUrl.parse("rtmp://example.com/live/key?user=u&pass=p")!!
        assertEquals("live?user=u&pass=p", url.app)
        assertEquals("key", url.streamKey)
        assertEquals("rtmp://example.com/live?user=u&pass=p", url.tcUrl)
    }

    @Test
    fun `blank path segments are skipped`() {
        val url = RtmpUrl.parse(" rtmp://example.com/live//key/ ")!!
        assertEquals("live", url.app)
        assertEquals("key", url.streamKey)
    }

    @Test
    fun `a bracketed ipv6 literal survives the port split`() {
        val url = RtmpUrl.parse("rtmp://[2001:db8::1]/live/key")!!
        assertEquals("[2001:db8::1]", url.host)
        assertEquals(1935, url.port)
        assertEquals("rtmp://[2001:db8::1]/live", url.tcUrl)
    }

    // ── the refusals: null means "not a usable push URL" ──

    @Test
    fun `non-rtmp schemes are refused`() {
        assertNull(RtmpUrl.parse(""))
        assertNull(RtmpUrl.parse("   "))
        assertNull(RtmpUrl.parse("http://example.com/live/key"))
        assertNull(RtmpUrl.parse("rtsp://example.com/live"))
        assertNull(RtmpUrl.parse("example.com/live/key"))
    }

    @Test
    fun `a missing stream key is refused`() {
        assertNull(RtmpUrl.parse("rtmp://example.com"))
        assertNull(RtmpUrl.parse("rtmp://example.com/"))
        assertNull(RtmpUrl.parse("rtmp://example.com/live"))
    }

    @Test
    fun `a missing authority is refused`() {
        assertNull(RtmpUrl.parse("rtmp:///live/key"))
        assertNull(RtmpUrl.parse("rtmp://"))
        assertNull(RtmpUrl.parse("rtmp:///"))
    }
}

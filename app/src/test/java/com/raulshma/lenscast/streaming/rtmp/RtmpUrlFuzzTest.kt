package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push-URL parser under hostile strings: a deterministic corpus fuzzer
 * over [RtmpUrl.parse].
 *
 * ── The contract ──
 * parse answers null or a fully-formed [RtmpUrl] (port in 1..65535, host and
 * stream key non-blank) — never an exception, for any input. Every test runs
 * under a timeout.
 *
 * ── The corpus ──
 * scheme/case variants, missing authority or path, userinfo shapes (no user,
 * no password, colons in the password, multiple @), port shapes (overflow,
 * negative, garbage, IPv6 brackets), query strings, unicode/control
 * characters, blank segments, and megabyte-scale inputs.
 */
class RtmpUrlFuzzTest {

    private fun assertValidOrNull(raw: String) {
        val parsed = RtmpUrl.parse(raw)
        if (parsed != null) {
            assertTrue("$raw port", parsed.port in 1..65535)
            assertTrue("$raw host", parsed.host.isNotBlank())
            assertTrue("$raw app", parsed.app.isNotBlank())
            assertTrue("$raw key", parsed.streamKey.isNotBlank())
            assertTrue("$raw tcUrl", parsed.tcUrl.startsWith(if (parsed.secure) "rtmps://" else "rtmp://"))
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - hostile URLs answer null or a formed RtmpUrl, never a crash`() {
        val hostile = listOf(
            "", " ", "rtmp", "rtmp:", "rtmp://", "rtmps://", "rtmp:///", "rtmp:// /",
            "RTMP://Host/live/key", "RtMpS://h/live/key",
            "rtmp://host", "rtmp://host/", "rtmp://host//",
            "rtmp://:pass@host/live/key", "rtmp://user@host/live/key",
            "rtmp://user:pass:word@host/live/key", "rtmp://a@b@host/live/key",
            "rtmp://host:1935/live/key", "rtmp://host:0/live/key", "rtmp://host:-1/live/key",
            "rtmp://host:99999999999999/live/key", "rtmp://host:port/live/key",
            "rtmp://host:/live/key", "rtmp://[::1]/live/key", "rtmp://user:pass@[::1]:1936/live/key",
            "rtmp://host/live/key?user=u&pass=p", "rtmp://host/live/a/b/c/key",
            "rtmp://host/live//key", "rtmp://host/live/key/", "rtmp://host/ /key",
            "rtmp://host/\u0000/key", "rtmp://h\u00f6st/live/k\u00e9y",
            "ftp://host/live/key", "//host/live/key", "rtmp://" + "a".repeat(1_000_000) + "/l/k",
            "rtmp://host/" + "a".repeat(500_000) + "/key",
        )
        for (raw in hostile) {
            assertValidOrNull(raw)
        }
    }

    @Test(timeout = 10_000)
    fun `the documented mappings still hold`() {
        val plain = RtmpUrl.parse("rtmp://example.com/live/myKey")!!
        assertEquals(false, plain.secure)
        assertEquals("example.com", plain.host)
        assertEquals(1935, plain.port)
        assertEquals("live", plain.app)
        assertEquals("myKey", plain.streamKey)
        assertEquals("rtmp://example.com/live", plain.tcUrl)

        val secure = RtmpUrl.parse("rtmps://example.com:443/live/extra/key?user=u&pass=p")!!
        assertTrue(secure.secure)
        assertEquals(443, secure.port)
        assertEquals("live/extra?user=u&pass=p", secure.app)
        assertEquals("key", secure.streamKey)

        val credentialed = RtmpUrl.parse("rtmp://user:pa:ss@host/live/key")!!
        assertEquals("user", credentialed.username)
        assertEquals("pa:ss", credentialed.password)

        assertNull(RtmpUrl.parse("rtmp://host/onlyStreamKey"))
        assertNull(RtmpUrl.parse("not rtmp at all"))
    }
}

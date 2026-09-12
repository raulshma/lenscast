package com.raulshma.lenscast.streaming.srt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SRT push URL parser, pinned: the default port, userinfo credentials,
 * the raw stream id, and the tolerance rules — a null return is always
 * "not a usable push URL" and never a silently fixed target.
 */
class SrtUrlTest {

    @Test
    fun `a plain host with the default port`() {
        val url = SrtUrl.parse("srt://listener.local")
        assertNotNull(url)
        assertEquals("listener.local", url!!.host)
        assertEquals(9710, url.port)
        assertNull(url.username)
        assertNull(url.streamId)
    }

    @Test
    fun `an explicit port and a stream id`() {
        val url = SrtUrl.parse("srt://192.168.1.20:9000?streamid=publish-secret")
        assertNotNull(url)
        assertEquals("192.168.1.20", url!!.host)
        assertEquals(9000, url.port)
        // The stream id is an opaque token carried verbatim (no decoding,
        // no re-trimming inside the query).
        assertEquals("publish-secret", url.streamId)
    }

    @Test
    fun `userinfo credentials split on the first colon`() {
        val url = SrtUrl.parse("srt://user:pas:sw0rd@listener.local:8000")
        assertNotNull(url)
        assertEquals("user", url!!.username)
        assertEquals("pas:sw0rd", url.password)
    }

    @Test
    fun `the path-segment stream id form is tolerated`() {
        val url = SrtUrl.parse("srt://listener.local:9000/my-stream-id")
        assertNotNull(url)
        assertEquals("my-stream-id", url!!.streamId)
    }

    @Test
    fun `extra query parameters are tolerated and ignored`() {
        val url = SrtUrl.parse("srt://listener.local?mode=caller&latency=120&streamid=abc")
        assertNotNull(url)
        assertEquals("abc", url!!.streamId)
    }

    @Test
    fun `scheme case is tolerated`() {
        assertNotNull(SrtUrl.parse("SRT://listener.local"))
    }

    @Test
    fun `an empty streamid query value keeps a path one`() {
        assertEquals("path-id", SrtUrl.parse("srt://h/path-id?streamid=")!!.streamId)
    }

    @Test
    fun `malformed urls are rejected, not fixed`() {
        assertNull(SrtUrl.parse(""))
        assertNull(SrtUrl.parse("srt://"))
        assertNull(SrtUrl.parse("rtmp://listener.local/live"))
        assertNull(SrtUrl.parse("listener.local"))
        assertNull(SrtUrl.parse("srt://user@:9000"))
    }

    @Test
    fun `an out-of-range port does not parse as a port`() {
        val url = SrtUrl.parse("srt://listener.local:99999")
        assertNotNull(url)
        // The tail is not a valid port, so it folds back to the default —
        // the host keeps its literal text (validity is judged at start).
        assertEquals(9710, url!!.port)
    }

    @Test
    fun `redaction never carries the credentials`() {
        val url = SrtUrl.parse("srt://user:pass@listener.local:9000?streamid=top-secret")
        val rendered = url!!.redacted()
        assertEquals("srt://listener.local:9000?streamid=<redacted>", rendered)
        assertTrue(!rendered.contains("top-secret"))
        assertTrue(!rendered.contains("user:pass"))
        // And without a stream id there is no empty query either.
        assertEquals("srt://listener.local", SrtUrl.parse("srt://listener.local")!!.redacted())
    }

    @Test
    fun `host and port rendering omits the default port`() {
        assertEquals("listener.local", SrtUrl.parse("srt://listener.local")!!.hostAndPort)
        assertEquals("listener.local:9000", SrtUrl.parse("srt://listener.local:9000")!!.hostAndPort)
    }
}

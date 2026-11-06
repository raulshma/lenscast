package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests pinning the connect sheet's port/scheme derivations exactly as
 * the ConnectSheet composition computed them — including the substring
 * quirks that make every schemed URL resolve to the default port. These are
 * truth pins of the moved code, not idealized URL parsing: change them only
 * alongside [ConnectInfoPolicy]'s contract.
 */
class ConnectInfoPolicyTest {

    @Test
    fun `schemed urls always fall through to the default web port`() {
        // "http://192.168.1.5:8080/stream".substringAfter(":") is
        // "//192.168.1.5:8080/stream" (the first colon is the scheme's), and
        // its first "/" is that leading "//"'s own — so the slice before it
        // is empty and the Int parse falls through to the default. The
        // StreamingManager's URL builders only ever produce this shape, so
        // the sheet's USB instruction quotes the default port today, even
        // when the server runs on a custom port (pinned, not fixed).
        assertEquals(StreamDefaults.WEB_PORT, ConnectInfoPolicy.currentPort("http://192.168.1.5:8080/stream"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("http://192.168.1.5:9000/stream"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("https://192.168.1.5:8443/stream"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("http://[fd11::a1b2]:8080/stream"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("http://192.168.1.5:8080"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("http://host"))
    }

    @Test
    fun `scheme-less host port strings yield the real port`() {
        // Without a scheme colon the slice starts at the port digits, so the
        // parse succeeds — the only input class where the real port survives.
        assertEquals(9000, ConnectInfoPolicy.currentPort("host:9000/path"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("host:8080/"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("192.168.1.5:8080"))
        assertEquals(8080, ConnectInfoPolicy.currentPort(":8080"))
    }

    @Test
    fun `malformed ports and unparseable slices fall through to the default`() {
        assertEquals(8080, ConnectInfoPolicy.currentPort("host:abc"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("host:"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("host:99999999999")) // out of Int range
        assertEquals(8080, ConnectInfoPolicy.currentPort("a:1:2")) // second colon poisons the Int parse
        assertEquals(8080, ConnectInfoPolicy.currentPort("http://host:8080/stream:99"))
        assertEquals(8080, ConnectInfoPolicy.currentPort(""))
        assertEquals(8080, ConnectInfoPolicy.currentPort("no-colon"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("8080")) // no colon -> empty slice
    }

    @Test
    fun `int parsing is lenient with signs and has no port-range check`() {
        // toIntOrNull accepts an optional sign and never range-checks a
        // port — pinned as current truth, surprising or not.
        assertEquals(-1, ConnectInfoPolicy.currentPort("host:-1"))
        assertEquals(8080, ConnectInfoPolicy.currentPort("host:+8080"))
    }

    @Test
    fun `secure http is the case-sensitive https prefix`() {
        assertTrue(ConnectInfoPolicy.isSecureHttp("https://192.168.1.5:8443/stream"))
        assertTrue(ConnectInfoPolicy.isSecureHttp("https://"))
        assertFalse(ConnectInfoPolicy.isSecureHttp("http://192.168.1.5:8080/stream"))
        assertFalse(ConnectInfoPolicy.isSecureHttp("HTTPS://host/stream")) // startsWith is case-sensitive
        assertFalse(ConnectInfoPolicy.isSecureHttp("https:/"))
        assertFalse(ConnectInfoPolicy.isSecureHttp(""))
    }
}

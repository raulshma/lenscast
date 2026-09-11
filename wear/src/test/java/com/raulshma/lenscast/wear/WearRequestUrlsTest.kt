package com.raulshma.lenscast.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-builder pins: the exact wire paths the phone's StreamingServer
 * registers, plus the host-normalization contract the settings field
 * promises (bare IP, scheme-carrying host, whitespace, blank).
 */
class WearRequestUrlsTest {

    @Test fun `bare host gets http scheme and port`() {
        assertEquals("http://192.168.1.20:8080", WearRequestUrls.baseUrl("192.168.1.20", 8080))
    }

    @Test fun `host with typed scheme is preserved`() {
        assertEquals(
            "http://192.168.1.20:8080",
            WearRequestUrls.baseUrl("http://192.168.1.20", 8080),
        )
        assertEquals(
            "https://lenscast.example.com:8443",
            WearRequestUrls.baseUrl("https://lenscast.example.com", 8443),
        )
    }

    @Test fun `whitespace and trailing slashes are trimmed`() {
        assertEquals(
            "http://192.168.1.20:8080",
            WearRequestUrls.baseUrl(" 192.168.1.20// ", 8080),
        )
    }

    @Test fun `blank host yields empty base`() {
        assertEquals("", WearRequestUrls.baseUrl("   ", 8080))
        assertEquals("", WearRequestUrls.baseUrl("", 8080))
    }

    @Test fun `endpoint paths match the server's registered routes`() {
        assertEquals(
            "http://10.0.0.2:8080/api/status",
            WearRequestUrls.status("10.0.0.2", 8080),
        )
        assertEquals(
            "http://10.0.0.2:8080/api/system",
            WearRequestUrls.system("10.0.0.2", 8080),
        )
        assertEquals(
            "http://10.0.0.2:8080/snapshot",
            WearRequestUrls.snapshot("10.0.0.2", 8080),
        )
        assertEquals(
            "http://10.0.0.2:8080/api/stream/start",
            WearRequestUrls.streamStart("10.0.0.2", 8080),
        )
        assertEquals(
            "http://10.0.0.2:8080/api/stream/stop",
            WearRequestUrls.streamStop("10.0.0.2", 8080),
        )
        assertEquals(
            "http://10.0.0.2:8080/api/capture",
            WearRequestUrls.capture("10.0.0.2", 8080),
        )
    }

    @Test fun `default port constant matches the phone server`() {
        // The StreamingServer's default HTTP port; a deliberate drift alarm.
        assertTrue(WearSettings.DEFAULT_PORT in 1..65535)
        assertEquals(8080, WearSettings.DEFAULT_PORT)
    }
}

package com.raulshma.lenscast.companion.discovery

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The QR pairing payload's decode contract — the exact strings the camera
 * app's connect sheet renders (`http(s)://host:port(#fp=<sha256>)`, the
 * fingerprint colon-separated uppercase) plus the leniency edges a scanner
 * in the wild produces. The load-bearing pins: the TLS scheme drives
 * [PairingPayload.useTls], a missing port folds to the scheme's default,
 * and the fingerprint normalizes to bare lowercase hex so the connect
 * sheet's rendering and a pasted digest compare equal at the pinning step.
 */
class PairingPayloadTest {

    // ── the connect sheet's canonical payloads ──

    @Test
    fun `a plain http payload decodes with its port`() {
        val payload = NsdDiscoveryFilter.parsePairingPayload("http://192.168.1.10:8080")
        assertEquals(PairingPayload(host = "192.168.1.10", port = 8080, useTls = false), payload)
    }

    @Test
    fun `an https payload carries the fingerprint`() {
        val payload = NsdDiscoveryFilter.parsePairingPayload(
            "https://192.168.1.10:8443#fp=AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89",
        )
        assertEquals("192.168.1.10", payload?.host)
        assertEquals(8443, payload?.port)
        assertEquals(true, payload?.useTls)
        assertEquals(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            payload?.tlsFingerprint,
        )
    }

    @Test
    fun `a missing port folds to the scheme default`() {
        assertEquals(
            StreamDefaults.WEB_PORT,
            NsdDiscoveryFilter.parsePairingPayload("http://192.168.1.10")?.port,
        )
        val tls = NsdDiscoveryFilter.parsePairingPayload("https://192.168.1.10")
        assertEquals(443, tls?.port)
        assertEquals(true, tls?.useTls)
    }

    @Test
    fun `uppercase colon-separated hex normalizes to bare lowercase`() {
        // The connect sheet renders the fingerprint colon-separated
        // uppercase (TlsCertManager's format); the parser's comparison form
        // is bare lowercase — one normalization, applied at decode.
        val payload = NsdDiscoveryFilter.parsePairingPayload(
            "https://pixel8.local:8443#fp=3D:9A:07:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:0A:1B:2C:3D:4E:5F:60:71:82:93:A4:B5",
        )
        assertEquals(
            "3d9a07ff00112233445566778899aabbccddeeff0a1b2c3d4e5f60718293a4b5",
            payload?.tlsFingerprint,
        )
    }

    @Test
    fun `a hostname and a trailing path are tolerated`() {
        val payload = NsdDiscoveryFilter.parsePairingPayload("http://pixel8.local:8080/")
        assertEquals(PairingPayload(host = "pixel8.local", port = 8080, useTls = false), payload)
    }

    @Test
    fun `a bracketed ipv6 host decodes`() {
        val payload = NsdDiscoveryFilter.parsePairingPayload("http://[fe80::1%25wlan0]:8080")
        assertEquals("fe80::1%25wlan0", payload?.host)
        assertEquals(8080, payload?.port)
    }

    @Test
    fun `scheme case is insensitive`() {
        assertEquals(
            "192.168.1.10",
            NsdDiscoveryFilter.parsePairingPayload("HTTPS://192.168.1.10")?.host,
        )
        assertEquals(
            true,
            NsdDiscoveryFilter.parsePairingPayload("HTTPS://192.168.1.10")?.useTls,
        )
    }

    @Test
    fun `a fingerprint fragment without the fp prefix is still read leniently`() {
        val payload = NsdDiscoveryFilter.parsePairingPayload("https://192.168.1.10:8443#AB:CD:EF")
        assertEquals("abcdef", payload?.tlsFingerprint)
    }

    // ── garbage rejects ──

    @Test
    fun `garbage payloads answer null`() {
        assertNull(NsdDiscoveryFilter.parsePairingPayload(""))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("   "))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("not a pairing code"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("WIFI:S:LensCast;T:WPA;;"))
        // Non-HTTP schemes are mis-scans, not payloads.
        assertNull(NsdDiscoveryFilter.parsePairingPayload("rtsp://192.168.1.10:8554/live"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("192.168.1.10:8080"))
    }

    @Test
    fun `a corrupt authority answers null`() {
        assertNull(NsdDiscoveryFilter.parsePairingPayload("http://"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("http://host:not-a-port"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("http://host:99999"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("http://user@host:8080"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("http://two words:8080"))
        assertNull(NsdDiscoveryFilter.parsePairingPayload("http://[fe80::1:8080"))
    }
}

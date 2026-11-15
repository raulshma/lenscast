package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The pinning seam's pure logic — full TLS pin E2E lives on the loopback
 * checklist (it needs real PKI), so here: digest computation, the
 * display-form → canonical normalization (the QR ships colon-separated
 * uppercase), exact-match equality, the cause-chain walker that lifts pin
 * failures out of JSSE's wrapped handshake errors, and the no-pin path
 * (plain-HTTP device, no fingerprint) performing a real cleartext request.
 */
class PinTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sha256Fingerprint computes the canonical lowercase hex digest`() {
        // The reference digest of "abc" (FIPS 180 test vector).
        val digest = sha256Fingerprint("abc".toByteArray(Charsets.UTF_8))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest,
        )
        assertFalse(digest.any { it.isUpperCase() })
    }

    @Test
    fun `normalization strips colons spaces and casing`() {
        // The Connect-sheet QR form (`AA:BB:…`) folds to the canonical form
        // the trust manager compares.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            normalizeFingerprint(
                "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:" +
                    "B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            ),
        )
        assertEquals("a1b2c3", normalizeFingerprint(" A1 B2 C3 "))
    }

    @Test
    fun `fingerprint equality is exact after normalization`() {
        val pinned = "AA:BB:CC:DD"
        assertTrue(fingerprintsEqual(pinned, "aabbccdd"))
        assertTrue(fingerprintsEqual(pinned, "aa bb cc dd"))
        assertFalse(fingerprintsEqual(pinned, "aabbccde"))
        assertFalse(fingerprintsEqual(pinned, "aabbcc"))
        assertFalse(fingerprintsEqual(pinned, ""))
    }

    @Test
    fun `pin-mismatch cause is found through JSSE's wrapped handshake error`() {
        val mismatch = PinVerificationException("aabb", actualSha256Hex = "ccdd")
        // The shape the socket surfaces: SSLHandshake-style IOException with
        // the trust manager's marker buried in the cause chain.
        val wrapped = IOException("Handshake failed", mismatch)
        assertTrue(wrapped.hasPinMismatchCause())
        assertTrue(mismatch.hasPinMismatchCause())
        assertFalse(IOException("Connection refused").hasPinMismatchCause())
        assertFalse(IOException("Handshake failed", IOException("protocol error")).hasPinMismatchCause())
    }

    @Test
    fun `a null-fingerprint cleartext engine performs real requests`() {
        // useTls=false, no fingerprint: the plain-HTTP first-class path —
        // same budgets, system trust, no pin — against a live cleartext
        // server proves the engine needs no TLS machinery for HTTP devices.
        val device = CompanionDevice(
            id = "test-device",
            name = "Test Device",
            host = "127.0.0.1",
            port = server.port,
            useTls = false,
            tlsFingerprint = null,
            auth = CompanionAuthKind.TOKEN,
            role = "admin",
            addedAtMs = 0L,
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"required":false}"""),
        )

        val client = LenscastApiClient(device) {
            StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-123")
        }
        runBlocking { client.authStatus() }

        // RecordedRequest.path is the request target, not the absolute URL —
        // the exchange succeeding over cleartext at all is the scheme proof
        // (a TLS attempt against MockWebServer's plain socket would fail).
        assertEquals("/api/auth/status", server.takeRequest().path)
    }

    @Test
    fun `LAN-radio budgets match the wear client`() {
        // The budgets are compile-time constants beside the factory; pin
        // them so a budget change is a conscious cross-lane decision, not a
        // silent drift from the other remote-device lane.
        assertEquals(3L, CONNECT_TIMEOUT_S)
        assertEquals(8L, IO_TIMEOUT_S)
        assertEquals(12L, CALL_TIMEOUT_S)
    }
}

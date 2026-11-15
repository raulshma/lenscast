package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * The failure taxonomy: every non-2xx answer and transport failure surfaces
 * as the exact [CompanionApiException] variant the UI branches on — 401 is a
 * re-pair cue, 403 a read-only banner, anything else HTTP, and unreachable
 * is Network. Response bodies are drained and closed on every path (the
 * `use` block), which these round trips exercise implicitly.
 */
class ErrorTest {

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

    private fun client(port: Int = server.port, role: String? = "viewer") =
        LenscastApiClient(
            CompanionDevice(
                id = "test-device",
                name = "Test Device",
                host = "127.0.0.1",
                port = port,
                auth = CompanionAuthKind.TOKEN,
                role = role,
                addedAtMs = 0L,
            ),
        ) { StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-123") }

    @Test
    fun `401 maps to Auth - the re-pair cue`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":"Authentication required"}"""),
        )
        try {
            runBlocking { client().getStatus() }
            throw AssertionError("expected Auth")
        } catch (expected: CompanionApiException.Auth) {
            // The variant itself is the signal; no body sniffing.
        }
    }

    @Test
    fun `403 maps to Forbidden carrying the roster role`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":"Admin access required"}"""),
        )
        try {
            runBlocking { client(role = "viewer").getStreamClients() }
            throw AssertionError("expected Forbidden")
        } catch (expected: CompanionApiException.Forbidden) {
            assertEquals("viewer", expected.role)
        }
    }

    @Test
    fun `other non-2xx maps to Http with code and body`() {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"error":"Internal error"}"""),
        )
        try {
            runBlocking { client().getStatus() }
            throw AssertionError("expected Http")
        } catch (expected: CompanionApiException.Http) {
            assertEquals(500, expected.code)
            assertEquals("""{"error":"Internal error"}""", expected.body)
        }
    }

    @Test
    fun `404 (unknown route on an older device) maps to Http`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}"""))
        try {
            runBlocking { client().getDetectionStats() }
            throw AssertionError("expected Http")
        } catch (expected: CompanionApiException.Http) {
            assertEquals(404, expected.code)
        }
    }

    @Test
    fun `connect refused maps to Network`() {
        // A port with nothing listening: bind, read the number, release —
        // loopback connects to it then refuse instantly (no radio-budget
        // timeout wait in the test).
        val dead = ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()

        try {
            runBlocking { client(port = deadPort).getStatus() }
            throw AssertionError("expected Network")
        } catch (expected: CompanionApiException.Network) {
            assertTrue(expected.cause is IOException)
        }
    }
}

package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The auth ladder's wire behavior, one MockWebServer round trip per rule:
 * token → `X-Api-Token`, basic → RFC 7617, every write → the CSRF header,
 * and the login-captured cookie riding (and only riding) when no stored
 * credential answers the provider. These lock the request-side contract the
 * server's HttpAuthFilter/WebAuthGate ladder actually enforces.
 */
class AuthTest {

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

    private fun device(auth: CompanionAuthKind = CompanionAuthKind.TOKEN) = CompanionDevice(
        id = "test-device",
        name = "Test Device",
        host = "127.0.0.1",
        port = server.port,
        auth = auth,
        role = "admin",
        addedAtMs = 0L,
    )

    private fun ok(body: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `token credentials ride X-Api-Token`() {
        val client = LenscastApiClient(device()) {
            StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-123")
        }
        server.enqueue(ok("""{"required":false}"""))

        runBlocking { client.authStatus() }

        assertEquals("tok-123", server.takeRequest().getHeader("X-Api-Token"))
    }

    @Test
    fun `basic credentials ride Authorization`() {
        val client = LenscastApiClient(device(CompanionAuthKind.BASIC)) {
            StoredCredentials(CompanionAuthKind.BASIC, username = "user", password = "pass")
        }
        server.enqueue(ok("""{"required":false}"""))

        runBlocking { client.authStatus() }

        // dXNlcjpwYXNz = base64("user:pass") — the RFC 7617 shape the gate's
        // cookie path ignores but the pairing probe relies on.
        assertEquals("Basic dXNlcjpwYXNz", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `non-GET carries the CSRF header and GET does not`() {
        val client = LenscastApiClient(device()) {
            StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-123")
        }
        server.enqueue(ok("""{"success":true}"""))
        server.enqueue(ok("""{"required":false}"""))

        runBlocking {
            client.setTorch(true)
            client.authStatus()
        }

        val write = server.takeRequest()
        assertEquals("XMLHttpRequest", write.getHeader("X-Requested-With"))
        assertEquals("""{"enabled":true}""", write.body.readUtf8())
        // GETs are state-safe and skip the CSRF check server-side; the
        // client mirrors the browser (which sends the header only on its
        // fetch wrapper's writes — here: writes only).
        assertNull(server.takeRequest().getHeader("X-Requested-With"))
    }

    @Test
    fun `login captures the session cookie and later requests ride it`() {
        val client = LenscastApiClient(device()) { null }
        server.enqueue(
            ok("""{"success":true,"role":"admin"}""")
                .setHeader("Set-Cookie", "lenscast_session=abc123; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax"),
        )
        server.enqueue(ok("""{"authenticated":true,"role":"admin"}"""))

        runBlocking {
            val login = client.login("user", "pass")
            assertEquals("admin", login.role)
            client.authSession()
        }

        val loginRequest = server.takeRequest()
        assertEquals("/api/auth/login", loginRequest.path)
        assertNull(loginRequest.getHeader("Cookie"))
        val followUp = server.takeRequest()
        assertEquals("lenscast_session=abc123", followUp.getHeader("Cookie"))
    }

    @Test
    fun `explicit credentials suppress the captured cookie`() {
        val client = LenscastApiClient(device()) { null }
        server.enqueue(
            ok("""{"success":true}""")
                .setHeader("Set-Cookie", "lenscast_session=abc123; Path=/"),
        )
        runBlocking { client.login("user", "pass") }

        // Now the roster carries a token: the header must win the ladder
        // ALONE — the server treats only Bearer/X-Api-Token as a token
        // presentation, so a Basic-or-cookie mixup would authenticate as the
        // cookie's (possibly revoked/viewer) session instead.
        val tokenedClient = LenscastApiClient(device()) {
            StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-123")
        }
        server.enqueue(ok("""{"required":false}"""))
        runBlocking { tokenedClient.authStatus() }

        server.takeRequest() // the login that minted the cookie
        val request = server.takeRequest()
        assertEquals("tok-123", request.getHeader("X-Api-Token"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun `a 401 while riding the cookie clears the stale session`() {
        val client = LenscastApiClient(device()) { null }
        server.enqueue(
            ok("""{"success":true}""").setHeader("Set-Cookie", "lenscast_session=stale; Path=/"),
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Authentication required"}"""))
        server.enqueue(ok("""{"required":false}"""))

        runBlocking {
            client.login("user", "pass")
            try {
                client.authSession()
                throw AssertionError("expected Auth")
            } catch (expected: CompanionApiException.Auth) {
                assertTrue(expected.message!!.isNotEmpty())
            }
            client.authStatus()
        }

        server.takeRequest() // login
        server.takeRequest() // the 401'd session probe
        val after = server.takeRequest()
        assertNull("stale cookie must not ride after its 401", after.getHeader("Cookie"))
    }
}

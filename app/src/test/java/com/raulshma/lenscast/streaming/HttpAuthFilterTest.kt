package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAuthFilterTest {

    private fun disabledGate() = WebAuthGate()

    private fun enabledGate(): WebAuthGate =
        WebAuthGate().apply { setCredentials("admin", "bogus-hash") }

    private val apiToken = "lenscast-api-token-3f9a"

    private fun tokenGate(): WebAuthGate =
        WebAuthGate().apply {
            setApiTokenProvider {
                WebAuthGate.ApiTokenConfig(enabled = true, hash = com.raulshma.lenscast.core.StreamAuthCrypto.sha256Hex(apiToken))
            }
        }

    // Routing

    @Test
    fun `non-auth route returns null from bodyless handler`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        assertNull(filter.handleBodylessAuthRoute("GET", "/api/settings", emptyMap()))
        assertNull(filter.handleBodylessAuthRoute("GET", "/stream", emptyMap()))
    }

    @Test
    fun `login route matches POST only`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        assertTrue(filter.isLoginRoute("POST", "/api/auth/login"))
        assertNull(filter.handleBodylessAuthRoute("GET", "/api/auth/login", emptyMap()))
    }

    @Test
    fun `protected routes are api stream audio and snapshot`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        assertTrue(filter.isProtectedRoute("/api/settings"))
        assertTrue(filter.isProtectedRoute("/stream"))
        assertTrue(filter.isProtectedRoute("/audio"))
        assertTrue(filter.isProtectedRoute("/snapshot?highres=1"))
        assertEquals(false, filter.isProtectedRoute("/index.html"))
        assertEquals(false, filter.isProtectedRoute("/"))
    }

    // Gate disabled: everything passes, nothing required

    @Test
    fun `status reports not required when gate disabled`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        val result = filter.handleBodylessAuthRoute("GET", "/api/auth/status", emptyMap())!!
        assertEquals(200, result.statusCode)
        assertEquals("""{"required":false}""", (result.body as HttpResult.ResponseBody.Text).text)
    }

    @Test
    fun `login reports not required when gate disabled`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        val result = filter.handleLogin("1.2.3.4", 2, "{}".toByteArray())
        assertEquals(200, result.statusCode)
        assertEquals("""{"required":false}""", (result.body as HttpResult.ResponseBody.Text).text)
    }

    @Test
    fun `protected route is allowed when gate disabled`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        assertNull(filter.authorize("GET", "/api/settings", emptyMap()))
    }

    // Login body handling (gate enabled, unknown user stays clear of device crypto)

    @Test
    fun `login without body is rejected`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val result = filter.handleLogin("1.2.3.4", 0, ByteArray(0))
        assertEquals(400, result.statusCode)
        assertEquals(
            """{"error":"Missing request body"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `login with malformed json is rejected`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val result = filter.handleLogin("1.2.3.4", 6, "{oops!".toByteArray())
        assertEquals(400, result.statusCode)
        assertEquals(
            """{"error":"Invalid request"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `login with blank credentials is rejected`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val body = """{"username":"","password":""}""".toByteArray()
        val result = filter.handleLogin("1.2.3.4", body.size, body)
        assertEquals(400, result.statusCode)
        assertEquals(
            """{"error":"Missing credentials"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `login with unknown user fails closed`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val body = """{"username":"intruder","password":"nope"}""".toByteArray()
        val result = filter.handleLogin("1.2.3.4", body.size, body)
        assertEquals(401, result.statusCode)
        assertEquals(
            """{"error":"Invalid credentials"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `rate limited login is unauthorized with the lockout message`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val body = """{"username":"intruder","password":"nope"}""".toByteArray()
        // Ten bad attempts fill the gate's budget; the eleventh is the lockout.
        repeat(10) { filter.handleLogin("1.2.3.4", body.size, body) }
        val result = filter.handleLogin("1.2.3.4", body.size, body)
        assertEquals(401, result.statusCode)
        assertEquals(
            """{"error":"Too many attempts. Try again later."}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `login tolerates whitespace and field order`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val body = """{ "password" : "nope" , "username" : "intruder" }""".toByteArray()
        val result = filter.handleLogin("1.2.3.4", body.size, body)
        assertEquals(401, result.statusCode)
    }

    // Gate enabled: authorization

    @Test
    fun `protected route without cookie is unauthorized`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val result = filter.authorize("GET", "/api/settings", emptyMap())!!
        assertEquals(401, result.statusCode)
        assertEquals(
            """{"error":"Authentication required"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `state-changing request without csrf cover is forbidden even when gate disabled`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        val result = filter.authorize("POST", "/api/settings", emptyMap())!!
        assertEquals(403, result.statusCode)
    }

    @Test
    fun `requested-with header passes the csrf check`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        val headers = mapOf("x-requested-with" to "XMLHttpRequest")
        assertNull(filter.authorize("POST", "/api/settings", headers))
    }

    @Test
    fun `local origin passes the csrf check`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        val headers = mapOf("origin" to "http://localhost:8080")
        assertNull(filter.authorize("POST", "/api/settings", headers))
    }

    @Test
    fun `foreign origin fails the csrf check`() {
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        val headers = mapOf("origin" to "http://evil.example")
        val result = filter.authorize("POST", "/api/settings", headers)!!
        assertEquals(403, result.statusCode)
    }

    @Test
    fun `logout without cookie is unauthorized`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val result = filter.handleBodylessAuthRoute("POST", "/api/auth/logout", emptyMap())!!
        assertEquals(401, result.statusCode)
    }

    @Test
    fun `session without cookie reports unauthenticated`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val result = filter.handleBodylessAuthRoute("GET", "/api/auth/session", emptyMap())!!
        assertEquals("""{"authenticated":false}""", (result.body as HttpResult.ResponseBody.Text).text)
    }

    // Credential parsing

    @Test
    fun `credential parser reads flat fields`() {
        val parsed = HttpAuthFilter.parseLoginCredentials(
            """{"username":"admin","password":"s3cret"}""".toByteArray(),
        )!!
        assertEquals("admin", parsed.username)
        assertEquals("s3cret", parsed.password)
    }

    @Test
    fun `credential parser unescapes values`() {
        val parsed = HttpAuthFilter.parseLoginCredentials(
            """{"username":"a\"b","password":"c\\d"}""".toByteArray(),
        )!!
        assertEquals("a\"b", parsed.username)
        assertEquals("c\\d", parsed.password)
    }

    @Test
    fun `login with trailing garbage is rejected`() {
        val filter = HttpAuthFilter(enabledGate(), port = 8080)
        val body = """{"username":"x","password":"y"} trailing""".toByteArray()
        val result = filter.handleLogin("1.2.3.4", body.size, body)
        assertEquals(400, result.statusCode)
        assertEquals(
            """{"error":"Invalid request"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `credential parser rejects non-objects`() {
        assertNull(HttpAuthFilter.parseLoginCredentials("[1,2]".toByteArray()))
        assertNull(HttpAuthFilter.parseLoginCredentials("".toByteArray()))
    }

    @Test
    fun `credential parser defaults missing fields to blank`() {
        val parsed = HttpAuthFilter.parseLoginCredentials("{}".toByteArray())!!
        assertEquals("", parsed.username)
        assertEquals("", parsed.password)
    }

    // API token ladder (header extraction + authorize order)

    @Test
    fun `bearer header authorizes a GET without any session cookie`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        val headers = mapOf("authorization" to "Bearer $apiToken")
        assertNull(filter.authorize("GET", "/snapshot", headers))
        assertNull(filter.authorize("GET", "/api/settings", headers))
    }

    @Test
    fun `x-api-token header authorizes a GET too`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        assertNull(filter.authorize("GET", "/snapshot", mapOf("x-api-token" to apiToken)))
    }

    @Test
    fun `the bearer scheme is case-insensitive`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        assertNull(filter.authorize("GET", "/snapshot", mapOf("authorization" to "bearer $apiToken")))
        assertNull(filter.authorize("GET", "/snapshot", mapOf("authorization" to "BEARER $apiToken")))
    }

    @Test
    fun `a valid token skips the csrf origin check`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        // GET with a token and no requested-with/origin headers: allowed.
        assertNull(filter.authorize("GET", "/api/settings", mapOf("authorization" to "Bearer $apiToken")))
    }

    @Test
    fun `an invalid token fails closed even with no cookie`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        val result = filter.authorize("GET", "/snapshot", mapOf("authorization" to "Bearer wrong"))!!
        assertEquals(401, result.statusCode)
    }

    @Test
    fun `a valid token on a state-changing method is denied by the gate`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        val result = filter.authorize("POST", "/api/settings", mapOf("authorization" to "Bearer $apiToken"))!!
        assertEquals(401, result.statusCode)
    }

    @Test
    fun `a valid token writes the TokenWritePolicy POST routes without a session`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        assertNull(filter.authorize("POST", "/api/recording/start", mapOf("authorization" to "Bearer $apiToken")))
        assertNull(filter.authorize("POST", "/api/capture", mapOf("x-api-token" to apiToken)))
    }

    @Test
    fun `a valid token never reaches the auth routes`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        val result = filter.authorize("GET", "/api/auth/config", mapOf("authorization" to "Bearer $apiToken"))!!
        assertEquals(401, result.statusCode)
    }

    @Test
    fun `token header extraction picks bearer then x-api-token`() {
        val filter = HttpAuthFilter(tokenGate(), port = 8080)
        assertEquals(apiToken, filter.apiTokenFrom(mapOf("authorization" to "Bearer $apiToken")))
        assertEquals(apiToken, filter.apiTokenFrom(mapOf("x-api-token" to "  $apiToken ")))
        assertEquals(null, filter.apiTokenFrom(emptyMap()))
        assertEquals(null, filter.apiTokenFrom(mapOf("authorization" to "Basic dXNlcjpwYXNz")))
        assertEquals(null, filter.apiTokenFrom(mapOf("authorization" to "Bearer")))
        assertEquals(null, filter.apiTokenFrom(mapOf("authorization" to "Bearer   ")))
        assertEquals(null, filter.apiTokenFrom(mapOf("x-api-token" to "   ")))
    }

    // Token setting off: presented token headers are inert

    @Test
    fun `a token header is inert while the token setting is off`() {
        // Auth disabled and token disarmed: a stale or garbage bearer header
        // must never 401 a route that is simply public.
        val filter = HttpAuthFilter(disabledGate(), port = 8080)
        assertNull(filter.authorize("GET", "/snapshot", mapOf("authorization" to "Bearer stale-token")))
        assertNull(filter.authorize("GET", "/snapshot", mapOf("x-api-token" to "stale-token")))
    }

    @Test
    fun `a disarmed token header falls through to the cookie ladder`() {
        val gate = WebAuthGate().apply {
            setCredentials("admin", com.raulshma.lenscast.core.StreamAuthCrypto.hashPassword("s3cret"))
            setApiTokenProvider { WebAuthGate.ApiTokenConfig(enabled = false, hash = "ignored") }
        }
        val filter = HttpAuthFilter(gate, port = 8080)
        val cookie = loginCookie(gate)
        // Reached the cookie ladder: the invalid token was not failed closed.
        assertEquals(
            401,
            filter.authorize("GET", "/snapshot", mapOf("authorization" to "Bearer stale"))!!.statusCode,
        )
        val headers = mapOf(
            "authorization" to "Bearer stale",
            "cookie" to "${WebAuthGate.COOKIE_NAME}=$cookie",
        )
        assertNull(filter.authorize("GET", "/snapshot", headers))
    }

    private fun loginCookie(gate: WebAuthGate): String {
        val result = gate.login("1.2.3.4", "admin", "s3cret")
        assertTrue(result.success)
        return result.token!!
    }
}

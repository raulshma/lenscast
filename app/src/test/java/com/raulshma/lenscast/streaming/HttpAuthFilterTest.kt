package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAuthFilterTest {

    private fun disabledGate() = WebAuthGate()

    private fun enabledGate(): WebAuthGate =
        WebAuthGate().apply { setCredentials("admin", "bogus-hash") }

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
}

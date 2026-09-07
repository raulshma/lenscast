package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamAuthCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthGateTest {

    private var nowMs = 1_000_000L

    // ── helpers ──

    /** The gate under test over a fake clock this test advances by hand. */
    private fun gate(
        username: String? = USER,
        password: String? = PASSWORD,
    ): WebAuthGate = WebAuthGate(clock = { nowMs }).apply {
        if (username != null && password != null) {
            setCredentials(username, StreamAuthCrypto.hashPassword(password))
        }
    }

    private fun WebAuthGate.loginAs(
        user: String = USER,
        pass: String = PASSWORD,
        ip: String = CLIENT_IP,
    ): WebAuthGate.LoginResult = login(ip, user, pass)

    private fun cookie(token: String): String = "${WebAuthGate.COOKIE_NAME}=$token"

    /** Burns the rate-limit budget: ten checked-and-rejected bad attempts. */
    private fun WebAuthGate.exhaustAttemptBudget(ip: String = CLIENT_IP) {
        repeat(10) { attempt ->
            val result = loginAs(pass = "wrong-$attempt", ip = ip)
            assertEquals(WebAuthGate.LoginFailure.InvalidCredentials, result.failure)
        }
    }

    companion object {
        private const val USER = "admin"
        private const val PASSWORD = "correct horse battery staple"
        private const val CLIENT_IP = "10.0.0.7"
    }

    // ── login ──

    @Test
    fun `correct password logs in and mints a session token`() {
        val gate = gate()
        val result = gate.loginAs()
        assertTrue(result.success)
        assertNull(result.failure)
        assertNull(result.error)
        assertFalse(result.token.isNullOrBlank())
        assertTrue(gate.authenticate(cookie(result.token!!)))
    }

    @Test
    fun `wrong password fails with invalid credentials`() {
        val result = gate().loginAs(pass = "wrong")
        assertFalse(result.success)
        assertNull(result.token)
        assertEquals("Invalid credentials", result.error)
        assertEquals(WebAuthGate.LoginFailure.InvalidCredentials, result.failure)
    }

    @Test
    fun `wrong username fails with invalid credentials`() {
        val result = gate().loginAs(user = "intruder")
        assertFalse(result.success)
        assertNull(result.token)
        assertEquals("Invalid credentials", result.error)
        assertEquals(WebAuthGate.LoginFailure.InvalidCredentials, result.failure)
    }

    @Test
    fun `login without configured credentials is not configured`() {
        val gate = gate(username = null)
        assertFalse(gate.isEnabled)
        val result = gate.loginAs()
        assertFalse(result.success)
        assertNull(result.token)
        assertEquals("Auth not configured", result.error)
        assertEquals(WebAuthGate.LoginFailure.NotConfigured, result.failure)
    }

    @Test
    fun `a blank password hash disables the gate`() {
        val gate = WebAuthGate(clock = { nowMs }).apply { setCredentials(USER, "   ") }
        assertFalse(gate.isEnabled)
        assertEquals(WebAuthGate.LoginFailure.NotConfigured, gate.loginAs().failure)
    }

    // ── login rate limiting ──

    @Test
    fun `eleventh attempt is rate limited even with the correct password`() {
        val gate = gate()
        gate.exhaustAttemptBudget()
        val eleventh = gate.loginAs()
        assertFalse(eleventh.success)
        assertNull(eleventh.token)
        assertEquals("Too many attempts. Try again later.", eleventh.error)
        assertEquals(WebAuthGate.LoginFailure.RateLimited, eleventh.failure)
    }

    @Test
    fun `lockout window lasts exactly sixty seconds`() {
        val gate = gate()
        val windowStartMs = nowMs
        gate.exhaustAttemptBudget()
        // The next attempt arms the 60s lockout...
        assertEquals(WebAuthGate.LoginFailure.RateLimited, gate.loginAs(pass = "wrong").failure)
        // ...still locked one millisecond before the mark...
        nowMs = windowStartMs + 59_999
        assertEquals(WebAuthGate.LoginFailure.RateLimited, gate.loginAs().failure)
        // ...and open again exactly at it.
        nowMs = windowStartMs + 60_000
        assertTrue(gate.loginAs().success)
    }

    @Test
    fun `the rate limit counts attempts per client ip`() {
        val gate = gate()
        gate.exhaustAttemptBudget(ip = "10.0.0.9")
        // A different client's budget is untouched.
        assertTrue(gate.loginAs(ip = "10.0.0.8").success)
    }

    // ── sessions ──

    @Test
    fun `session survives until exactly the twenty-four-hour mark`() {
        val gate = gate()
        val loginTimeMs = nowMs
        val sessionCookie = cookie(gate.loginAs().token!!)
        nowMs = loginTimeMs + WebAuthGate.SESSION_DURATION_MS
        assertTrue(gate.authenticate(sessionCookie))
        nowMs = loginTimeMs + WebAuthGate.SESSION_DURATION_MS + 1
        assertFalse(gate.authenticate(sessionCookie))
    }

    @Test
    fun `authenticate rejects missing malformed and unknown tokens`() {
        val gate = gate()
        assertFalse(gate.authenticate(null))
        assertFalse(gate.authenticate("garbage"))
        assertFalse(gate.authenticate(cookie("unknown-token")))
        assertTrue(gate.authenticate(cookie(gate.loginAs().token!!)))
    }

    @Test
    fun `authenticate lets everything through when auth is disabled`() {
        val gate = gate(username = null)
        assertTrue(gate.authenticate(null))
        assertTrue(gate.authenticate(cookie("anything")))
    }

    @Test
    fun `logout invalidates the session token`() {
        val gate = gate()
        val sessionCookie = cookie(gate.loginAs().token!!)
        assertTrue(gate.authenticate(sessionCookie))
        gate.logout(gate.tokenFromCookie(sessionCookie))
        assertFalse(gate.authenticate(sessionCookie))
    }

    // ── CSRF origin checks ──

    @Test
    fun `a requested-with header is csrf safe on its own`() {
        val gate = gate()
        assertTrue(gate.isCsrfSafe(originHeader = null, hasRequestedWithHeader = true, port = 8080))
    }

    @Test
    fun `localhost and loopback origins at the server port are csrf safe`() {
        val gate = gate()
        assertTrue(gate.isCsrfSafe("http://localhost:8080", hasRequestedWithHeader = false, port = 8080))
        assertTrue(gate.isCsrfSafe("http://127.0.0.1:8080", hasRequestedWithHeader = false, port = 8080))
    }

    @Test
    fun `an origin is compared by scheme host and port`() {
        val gate = gate()
        assertFalse(gate.isCsrfSafe("http://localhost:8081", hasRequestedWithHeader = false, port = 8080))
        assertFalse(gate.isCsrfSafe("https://localhost:8080", hasRequestedWithHeader = false, port = 8080))
        assertFalse(gate.isCsrfSafe("http://evil.example", hasRequestedWithHeader = false, port = 8080))
    }

    @Test
    fun `an unparseable origin is not csrf safe`() {
        val gate = gate()
        assertFalse(gate.isCsrfSafe("not a uri", hasRequestedWithHeader = false, port = 8080))
    }

    @Test
    fun `no origin and no requested-with header is not csrf safe`() {
        val gate = gate()
        assertFalse(gate.isCsrfSafe(originHeader = null, hasRequestedWithHeader = false, port = 8080))
    }
}

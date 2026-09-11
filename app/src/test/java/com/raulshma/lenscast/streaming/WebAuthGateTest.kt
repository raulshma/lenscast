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

    // ── viewer roles (login ladder, sessions, targeted revocation) ──

    private fun gateWithViewer(
        user: String = USER,
        password: String = PASSWORD,
        viewerUser: String? = VIEWER_USER,
        viewerPass: String? = VIEWER_PASSWORD,
    ): WebAuthGate = WebAuthGate(clock = { nowMs }).apply {
        setCredentials(user, StreamAuthCrypto.hashPassword(password))
        if (viewerUser != null && viewerPass != null) {
            setViewerCredentials(viewerUser, StreamAuthCrypto.hashPassword(viewerPass))
        }
    }

    @Test
    fun `admin credentials mint an admin session`() {
        val gate = gateWithViewer()
        val result = gate.loginAs()
        assertTrue(result.success)
        assertEquals(SessionRole.ADMIN, result.role)
        assertEquals(SessionRole.ADMIN, gate.sessionRoleFor(cookie(result.token!!)))
    }

    @Test
    fun `viewer credentials mint a viewer session`() {
        val gate = gateWithViewer()
        val result = gate.login(CLIENT_IP, VIEWER_USER, VIEWER_PASSWORD)
        assertTrue(result.success)
        assertEquals(SessionRole.VIEWER, result.role)
        assertTrue(gate.authenticate(cookie(result.token!!)))
        assertEquals(SessionRole.VIEWER, gate.sessionRoleFor(cookie(result.token!!)))
    }

    @Test
    fun `viewer password with the admin username fails like any other wrong password`() {
        val gate = gateWithViewer()
        val result = gate.loginAs(pass = VIEWER_PASSWORD)
        assertFalse(result.success)
        assertNull(result.token)
        assertEquals(WebAuthGate.LoginFailure.InvalidCredentials, result.failure)
        assertEquals("Invalid credentials", result.error)
    }

    @Test
    fun `both-wrong and admin-username-viewer-password failures are identical`() {
        val gate = gateWithViewer()
        val bothWrong = gate.loginAs(user = "intruder", pass = "nope")
        val viewerPassAdminUser = gate.loginAs(pass = VIEWER_PASSWORD)
        assertEquals(bothWrong.failure, viewerPassAdminUser.failure)
        assertEquals(bothWrong.error, viewerPassAdminUser.error)
        assertEquals(bothWrong.success, viewerPassAdminUser.success)
    }

    @Test
    fun `viewer credentials without a configured viewer pair fail as invalid`() {
        val gate = gateWithViewer(viewerUser = null, viewerPass = null)
        val result = gate.login(CLIENT_IP, VIEWER_USER, VIEWER_PASSWORD)
        assertFalse(result.success)
        assertEquals("Invalid credentials", result.error)
        assertEquals(WebAuthGate.LoginFailure.InvalidCredentials, result.failure)
        assertFalse(gate.isViewerConfigured)
    }

    @Test
    fun `the login ladder shares one rate-limit budget per ip`() {
        val gate = gateWithViewer()
        // Ten bad viewer attempts exhaust the budget...
        repeat(10) {
            assertEquals(
                WebAuthGate.LoginFailure.InvalidCredentials,
                gate.login(CLIENT_IP, VIEWER_USER, "wrong-$it").failure,
            )
        }
        // ...and even the correct admin password is then locked out.
        assertEquals(WebAuthGate.LoginFailure.RateLimited, gate.loginAs().failure)
    }

    @Test
    fun `revoking viewer sessions leaves admin sessions signed in`() {
        val gate = gateWithViewer()
        val adminToken = gate.loginAs().token!!
        val viewerToken = gate.login(CLIENT_IP, VIEWER_USER, VIEWER_PASSWORD).token!!
        gate.revokeViewerSessions()
        assertTrue(gate.authenticate(cookie(adminToken)))
        assertFalse(gate.authenticate(cookie(viewerToken)))
    }

    @Test
    fun `revoking viewer sessions with none present is a harmless no-op`() {
        val gate = gateWithViewer()
        val adminToken = gate.loginAs().token!!
        gate.revokeViewerSessions()
        assertTrue(gate.authenticate(cookie(adminToken)))
    }

    @Test
    fun `sessions info carries the role of each session`() {
        val gate = gateWithViewer()
        gate.loginAs()
        gate.login(CLIENT_IP, VIEWER_USER, VIEWER_PASSWORD)
        val roles = gate.sessionsInfo().map { it.role }.sortedBy { it.wireName }
        assertEquals(listOf(SessionRole.ADMIN, SessionRole.VIEWER), roles)
    }

    @Test
    fun `clearing viewer credentials stops viewer logins but keeps admin`() {
        val gate = gateWithViewer()
        gate.setViewerCredentials(null, null)
        assertFalse(gate.isViewerConfigured)
        assertEquals(WebAuthGate.LoginFailure.InvalidCredentials, gate.login(CLIENT_IP, VIEWER_USER, VIEWER_PASSWORD).failure)
        assertTrue(gate.loginAs().success)
    }

    companion object {
        private const val USER = "admin"
        private const val PASSWORD = "correct horse battery staple"
        private const val VIEWER_USER = "doorbell"
        private const val VIEWER_PASSWORD = "peek viewer pass"
        private const val CLIENT_IP = "10.0.0.7"
    }
}

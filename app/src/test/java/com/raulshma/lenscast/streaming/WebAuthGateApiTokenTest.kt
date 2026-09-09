package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamAuthCrypto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API-token verdict ladder: enabled + configured hash + constant-time
 * SHA-256 match, GET/HEAD anywhere protected, POST only on the
 * TokenWritePolicy allow-list, never on the auth routes. The token provider
 * is injected (and re-pointable) so the live-read contract is tested without
 * a store or a socket.
 */
class WebAuthGateApiTokenTest {

    private val token = "lenscast-api-token-3f9a"
    private val tokenHash = StreamAuthCrypto.sha256Hex(token)

    private fun gate(
        enabled: Boolean = true,
        storedHash: String = tokenHash,
        provider: (() -> WebAuthGate.ApiTokenConfig)? = null,
    ): WebAuthGate = WebAuthGate().apply {
        setApiTokenProvider(provider ?: { WebAuthGate.ApiTokenConfig(enabled, storedHash) })
    }

    // ── the happy path ──

    @Test
    fun `a valid token authorizes a GET on a protected path`() {
        assertTrue(gate().authorizeApiToken(token, "GET", "/snapshot"))
        assertTrue(gate().authorizeApiToken(token, "GET", "/api/settings"))
        assertTrue(gate().authorizeApiToken(token, "GET", "/stream"))
    }

    @Test
    fun `HEAD is allowed like GET`() {
        assertTrue(gate().authorizeApiToken(token, "HEAD", "/snapshot"))
    }

    // ── method ladder ──

    @Test
    fun `state-changing methods are denied even with a valid token`() {
        val g = gate()
        assertFalse(g.authorizeApiToken(token, "POST", "/api/settings"))
        assertFalse(g.authorizeApiToken(token, "PUT", "/api/settings"))
        assertFalse(g.authorizeApiToken(token, "DELETE", "/api/media/x"))
    }

    // ── the write allow-list ──

    @Test
    fun `a valid token writes exactly the TokenWritePolicy POST routes`() {
        val g = gate()
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/start"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/resume"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/stop"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/web/start"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/web/stop"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/rtsp/start"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/stream/rtsp/stop"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/capture"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/recording/start"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/recording/stop"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/deterrence/siren"))
        assertTrue(g.authorizeApiToken(token, "POST", "/api/camera/torch"))
    }

    @Test
    fun `a POST outside the write allow-list is still denied`() {
        val g = gate()
        assertFalse(g.authorizeApiToken(token, "POST", "/api/settings"))
        assertFalse(g.authorizeApiToken(token, "POST", "/api/camera/lens"))
        assertFalse(g.authorizeApiToken(token, "POST", "/api/media/batch-delete"))
    }

    @Test
    fun `auth routes are never token-writable even when listed`() {
        val g = gate()
        // Defense in depth: the denied-prefix check holds regardless of the
        // allow-list's contents.
        assertFalse(g.authorizeApiToken(token, "POST", "/api/auth/config"))
        assertFalse(g.authorizeApiToken(token, "POST", "/api/auth/logout"))
    }

    @Test
    fun `a wrong token cannot use the write allow-list`() {
        assertFalse(gate().authorizeApiToken("wrong-token", "POST", "/api/recording/start"))
    }

    // ── path ladder ──

    @Test
    fun `auth routes are denied even with a valid token`() {
        val g = gate()
        assertFalse(g.authorizeApiToken(token, "GET", "/api/auth/status"))
        assertFalse(g.authorizeApiToken(token, "GET", "/api/auth/session"))
        assertFalse(g.authorizeApiToken(token, "GET", "/api/auth/config"))
        assertFalse(g.authorizeApiToken(token, "GET", "/api/auth/sessions"))
    }

    @Test
    fun `a path merely containing the auth prefix is not denied`() {
        // The denial is a path-prefix rule, not a substring rule.
        assertTrue(gate().authorizeApiToken(token, "GET", "/api/authx"))
    }

    // ── config ladder ──

    @Test
    fun `a disabled token config denies everything`() {
        assertFalse(gate(enabled = false).authorizeApiToken(token, "GET", "/snapshot"))
    }

    @Test
    fun `a blank stored hash denies even when enabled`() {
        assertFalse(gate(storedHash = "").authorizeApiToken(token, "GET", "/snapshot"))
        assertFalse(gate(storedHash = "   ").authorizeApiToken(token, "GET", "/snapshot"))
    }

    // ── token ladder ──

    @Test
    fun `wrong null and blank tokens are denied`() {
        val g = gate()
        assertFalse(g.authorizeApiToken("wrong-token", "GET", "/snapshot"))
        assertFalse(g.authorizeApiToken(null, "GET", "/snapshot"))
        assertFalse(g.authorizeApiToken("", "GET", "/snapshot"))
        assertFalse(g.authorizeApiToken("   ", "GET", "/snapshot"))
    }

    @Test
    fun `the verdict compares the sha-256 hex of the presented token`() {
        // The stored value is the hash of the token; presenting the hash
        // itself must not pass.
        assertFalse(gate().authorizeApiToken(tokenHash, "GET", "/snapshot"))
    }

    // ── live provider ──

    @Test
    fun `the provider is read on every verdict - no snapshot`() {
        var config = WebAuthGate.ApiTokenConfig(enabled = true, hash = tokenHash)
        val g = gate(provider = { config })
        assertTrue(g.authorizeApiToken(token, "GET", "/snapshot"))
        config = WebAuthGate.ApiTokenConfig(enabled = false, hash = tokenHash)
        assertFalse(g.authorizeApiToken(token, "GET", "/snapshot"))
        config = WebAuthGate.ApiTokenConfig(enabled = true, hash = tokenHash)
        assertTrue(g.authorizeApiToken(token, "GET", "/snapshot"))
    }

    @Test
    fun `setApiTokenProvider re-points the live source`() {
        val g = gate()
        assertTrue(g.authorizeApiToken(token, "GET", "/snapshot"))
        g.setApiTokenProvider { WebAuthGate.ApiTokenConfig(enabled = true, hash = StreamAuthCrypto.sha256Hex("rotated")) }
        assertFalse(g.authorizeApiToken(token, "GET", "/snapshot"))
        assertTrue(g.authorizeApiToken("rotated", "GET", "/snapshot"))
    }

    // ── independence from the session gate ──

    @Test
    fun `the token path works without any web credentials configured`() {
        val g = WebAuthGate().apply {
            setApiTokenProvider { WebAuthGate.ApiTokenConfig(enabled = true, hash = tokenHash) }
        }
        assertFalse(g.isEnabled)
        assertTrue(g.authorizeApiToken(token, "GET", "/snapshot"))
    }

    @Test
    fun `the default gate has no token config`() {
        assertFalse(WebAuthGate().authorizeApiToken(token, "GET", "/snapshot"))
    }

    // ── the arming flag the transport reads ──

    @Test
    fun `isApiTokenArmed mirrors the live config`() {
        val g = gate(enabled = false)
        assertFalse(g.isApiTokenArmed)
        assertTrue(gate().isApiTokenArmed)
    }
}

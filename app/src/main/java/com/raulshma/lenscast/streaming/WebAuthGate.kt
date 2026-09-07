package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.StreamAuthCrypto
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the web-client auth policy: session tokens, login rate limiting, and
 * CSRF origin checks. The transport ([StreamingServer]) translates requests
 * to this small interface; the policy itself is reachable — and testable —
 * without a live socket.
 *
 * Credentials arrive via [setCredentials]; null or blank username/hash means
 * auth is disabled and [authenticate] lets everything through.
 */
class WebAuthGate {

    data class LoginResult(
        val success: Boolean,
        val token: String? = null,
        val error: String? = null,
    )

    @Volatile
    private var username: String? = null

    @Volatile
    private var passwordHash: String? = null

    private val sessions = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()

    private data class AuthAttempt(var count: Int, var blockedUntil: Long)
    private val authAttempts = ConcurrentHashMap<String, AuthAttempt>()
    private val authAttemptsLock = Any()
    private var lastSessionCleanupMillis = 0L

    val isEnabled: Boolean
        get() = !username.isNullOrBlank() && passwordHash != null

    fun setCredentials(username: String?, passwordHash: String?) {
        this.username = if (username.isNullOrBlank()) null else username
        this.passwordHash = if (passwordHash.isNullOrBlank()) null else passwordHash
    }

    /** True when the request may proceed: auth off, or a valid session cookie. */
    fun authenticate(cookieHeader: String?): Boolean {
        // setCredentials blanks empty values to null, so non-null means enabled.
        if (username == null) return true
        val token = tokenFromCookie(cookieHeader) ?: return false
        return validateSession(token)
    }

    /** Rate-limited, constant-time credential check; mints a session on success. */
    fun login(clientIp: String?, username: String, password: String): LoginResult {
        val storedUsername = this.username
        val storedHash = this.passwordHash
        if (storedUsername == null || storedHash == null) {
            return LoginResult(success = false, error = "Auth not configured")
        }

        val now = System.currentTimeMillis()
        synchronized(authAttemptsLock) {
            cleanupExpiredAuthAttempts(now)
            val attempt = authAttempts.getOrPut(clientIp ?: "unknown") { AuthAttempt(0, 0L) }
            if (attempt.blockedUntil > now) {
                return LoginResult(success = false, error = "Too many attempts. Try again later.")
            }
            if (attempt.count >= MAX_AUTH_ATTEMPTS) {
                attempt.blockedUntil = now + AUTH_LOCKOUT_MS
                attempt.count = 0
                return LoginResult(success = false, error = "Too many attempts. Try again later.")
            }
            if (!StreamAuthCrypto.constantTimeEquals(username, storedUsername)) {
                attempt.count++
                return LoginResult(success = false, error = "Invalid credentials")
            }
            if (!StreamAuthCrypto.verifyPassword(password, storedHash)) {
                attempt.count++
                return LoginResult(success = false, error = "Invalid credentials")
            }
            attempt.count = 0
        }
        return LoginResult(success = true, token = createSession())
    }

    fun logout(token: String?) {
        if (token != null) {
            sessions.remove(token)
        }
    }

    /**
     * CSRF protection for state-changing requests: a recognized X-Requested-With
     * header or an Origin/Referer matching this server's [port].
     */
    fun isCsrfSafe(originHeader: String?, hasRequestedWithHeader: Boolean, port: Int): Boolean {
        if (hasRequestedWithHeader) return true

        if (originHeader != null) {
            val localIp = NetworkUtils.getLocalIpAddress()
            val allowedOrigins = buildList {
                add("http://localhost:$port")
                add("http://127.0.0.1:$port")
                if (localIp != null) add("http://$localIp:$port")
            }
            return try {
                val requestUri = URI(originHeader)
                val requestOrigin = "${requestUri.scheme}://${requestUri.host}:${requestUri.port}"
                allowedOrigins.any { allowed ->
                    val allowedUri = URI(allowed)
                    val normalizedAllowed = "${allowedUri.scheme}://${allowedUri.host}:${allowedUri.port}"
                    requestOrigin == normalizedAllowed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse CSRF origin header: $originHeader", e)
                false
            }
        }

        // No recognized CSRF protection headers found
        return false
    }

    fun tokenFromCookie(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrEmpty()) return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .find { it.startsWith("$COOKIE_NAME=") }
            ?.substring(COOKIE_NAME.length + 1)
    }

    fun clearSessions() {
        sessions.clear()
    }

    private fun createSession(): String {
        cleanExpiredSessions()
        // Enforce maximum session count to prevent OOM via session flooding
        if (sessions.size >= MAX_SESSIONS) {
            // Evict oldest sessions beyond the cap
            val sorted = sessions.entries.sortedBy { it.value }
            val toRemove = sorted.take(sessions.size - MAX_SESSIONS + 1)
            toRemove.forEach { sessions.remove(it.key) }
        }
        val bytes = ByteArray(SESSION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessions[token] = System.currentTimeMillis() + SESSION_DURATION_MS
        return token
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        if (now - lastSessionCleanupMillis < SESSION_CLEANUP_INTERVAL_MS && sessions.size < MAX_SESSIONS * 0.9) return
        lastSessionCleanupMillis = now
        sessions.entries.removeAll { now > it.value }
    }

    private fun validateSession(token: String): Boolean {
        // Opportunistically clean expired sessions on each validation
        cleanExpiredSessions()
        val expiry = sessions[token] ?: return false
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun cleanupExpiredAuthAttempts(now: Long) {
        authAttempts.entries.removeAll { (_, attempt) ->
            attempt.blockedUntil > 0 && now > attempt.blockedUntil + AUTH_LOCKOUT_MS * 2 && attempt.count == 0
        }
        if (authAttempts.size > MAX_AUTH_ATTEMPTS_TRACKED) {
            val sorted = authAttempts.entries.sortedByDescending { it.value.blockedUntil }
            sorted.take(authAttempts.size - MAX_AUTH_ATTEMPTS_TRACKED).forEach { authAttempts.remove(it.key) }
        }
    }

    companion object {
        private const val TAG = "WebAuthGate"
        const val SESSION_DURATION_MS = 24 * 60 * 60 * 1000L
        private const val SESSION_CLEANUP_INTERVAL_MS = 60 * 1000L
        const val SESSION_MAX_AGE_SEC = 24 * 60 * 60
        private const val MAX_SESSIONS = 1000
        private const val SESSION_TOKEN_BYTES = 32
        const val COOKIE_NAME = "lenscast_session"
        const val COOKIE_PATH = "/"
        private const val MAX_AUTH_ATTEMPTS = 10
        private const val AUTH_LOCKOUT_MS = 60 * 1000L
        private const val MAX_AUTH_ATTEMPTS_TRACKED = 500
    }
}

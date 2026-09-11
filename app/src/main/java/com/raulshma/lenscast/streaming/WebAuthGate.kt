package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.toHexString
import com.raulshma.lenscast.streaming.web.TokenWritePolicy
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the web-client auth policy: session tokens, login rate limiting, and
 * CSRF origin checks — plus the API-token verdict for programmatic clients
 * (GET/HEAD anywhere protected, POST only on [TokenWritePolicy]'s exact
 * allow-list). The transport ([StreamingServer]) translates requests
 * to this small interface; the policy itself is reachable — and testable —
 * without a live socket.
 *
 * Credentials arrive via [setCredentials]; null or blank username/hash means
 * auth is disabled and [authenticate] lets everything through. The optional
 * read-only viewer pair arrives via [setViewerCredentials] and rides the same
 * pipeline — [login] accepts either pair and the minted session carries the
 * matching [SessionRole]. Time reads go
 * through the injected [clock] — the [com.raulshma.lenscast.streaming.rtsp.RtspSessionAuthorizer]
 * seam — so the lockout window and the session expiry are JVM-tested without
 * waiting. Sessions survive process death through the optional
 * [SessionPersistence] hook: when it is set, every session mutation is
 * mirrored to it and the store is reloaded on construction.
 *
 * The API token is not a session and always speaks admin: [authorizeApiToken]
 * compares SHA-256 hex
 * against the hash supplied by the live [tokenProvider] source — never a
 * snapshot, so a token saved over /api/settings authorizes (or stops
 * authorizing) on the very next request.
 */
class WebAuthGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionPersistence: SessionPersistence? = null,
) {

    /**
     * Durability hook for the session map (token → record). The tokens are
     * secrets; implementations must keep them in app-private storage. Null
     * (the default) keeps sessions in memory only.
     */
    interface SessionPersistence {
        fun loadSessions(): Map<String, StoredSession>
        fun saveSessions(sessions: Map<String, StoredSession>)
    }

    /** One live session: when it expires and which role it speaks for. */
    data class StoredSession(val expiresAtMs: Long, val role: SessionRole)

    /** The typed reason behind a failed login; the transport maps it to a status code. */
    enum class LoginFailure {
        /** No credentials configured — a server-side misconfiguration, not the client's fault. */
        NotConfigured,
        /** The client exhausted its attempts and is inside the lockout window. */
        RateLimited,
        /** Wrong username or wrong password. */
        InvalidCredentials,
    }

    /**
     * The login verdict. [error] is the client-facing message, forwarded
     * verbatim by the transport; [failure] is the typed reason behind it
     * (null on success) — and the single source [error] derives from.
     * [role] is the credential pair that matched (always ADMIN on the
     * pre-viewer code paths; check [success] first).
     */
    data class LoginResult(
        val success: Boolean,
        val token: String? = null,
        val error: String? = null,
        val failure: LoginFailure? = null,
        val role: SessionRole = SessionRole.ADMIN,
    )

    @Volatile
    private var username: String? = null

    @Volatile
    private var passwordHash: String? = null

    @Volatile
    private var viewerUsername: String? = null

    @Volatile
    private var viewerPasswordHash: String? = null

    /**
     * The live API-token config source. Installed with [setApiTokenProvider]
     * and re-runnable on every verdict, so the store's current value is what
     * each request sees. Defaults to a disarmed config.
     */
    @Volatile
    private var tokenProvider: () -> ApiTokenConfig = { ApiTokenConfig() }

    /**
     * The stored API-token state: whether the token path is armed and the
     * SHA-256 hex hash of the token (never the token itself).
     */
    data class ApiTokenConfig(val enabled: Boolean = false, val hash: String = "")

    /** Re-points the live config source (the one installer is StreamingManager, at the composition root). */
    fun setApiTokenProvider(provider: () -> ApiTokenConfig) {
        tokenProvider = provider
    }

    /**
     * True when the token path is armed: presented token headers are then
     * validated (and fail closed). While disarmed they are inert — the
     * transport skips the token ladder, so a stale or garbage header never
     * 401s a route that is public or cookie-checkable.
     */
    val isApiTokenArmed: Boolean
        get() = tokenProvider().enabled

    /**
     * The API-token verdict, checked before the login/session path. True only
     * when: the token is enabled, a hash is configured, [token] matches it
     * (constant-time over the SHA-256 hex), the method is GET/HEAD — or POST
     * on exactly the routes [TokenWritePolicy] allows — and [path] is outside
     * `/api/auth/`. A bearer token never mints sessions, rotates credentials,
     * or logs out, and stays read-only everywhere the write policy does not
     * name. Stateless: no session map entry, no rate-limit budget of its own
     * beyond the comparison itself.
     */
    fun authorizeApiToken(token: String?, method: String, path: String): Boolean {
        val methodAllowed = method == "GET" || method == "HEAD" ||
            (method == "POST" && TokenWritePolicy.allowsPost(path))
        if (!methodAllowed) return false
        if (path.startsWith(API_TOKEN_DENIED_PATH_PREFIX)) return false
        val config = tokenProvider()
        if (!config.enabled) return false
        if (config.hash.isBlank()) return false
        if (token.isNullOrBlank()) return false
        return StreamAuthCrypto.constantTimeEquals(StreamAuthCrypto.sha256Hex(token), config.hash)
    }

    private val sessions = ConcurrentHashMap<String, StoredSession>()
    private val secureRandom = SecureRandom()

    init {
        // Reload persisted sessions, dropping anything already expired so a
        // stale store cannot resurrect dead logins.
        val restored = sessionPersistence?.loadSessions().orEmpty()
        val now = clock()
        restored.filterValues { it.expiresAtMs > now }.forEach { (token, record) -> sessions[token] = record }
        if (sessions.isNotEmpty()) {
            Log.d(TAG, "Restored ${sessions.size} persisted session(s)")
        }
    }

    private fun persistSessions() {
        sessionPersistence?.saveSessions(LinkedHashMap(sessions))
    }

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

    /**
     * The optional read-only viewer pair; blank values clear it. Same blanking
     * convention as [setCredentials] — null on either side means no viewer
     * access, and the pair is only ever set/cleared together by the handler.
     */
    fun setViewerCredentials(username: String?, passwordHash: String?) {
        this.viewerUsername = if (username.isNullOrBlank()) null else username
        this.viewerPasswordHash = if (passwordHash.isNullOrBlank()) null else passwordHash
    }

    /** True when a usable viewer pair is configured. */
    val isViewerConfigured: Boolean
        get() = !viewerUsername.isNullOrBlank() && viewerPasswordHash != null

    /** True when the request may proceed: auth off, or a valid session cookie. */
    fun authenticate(cookieHeader: String?): Boolean {
        // setCredentials blanks empty values to null, so non-null means enabled.
        if (username == null) return true
        val token = tokenFromCookie(cookieHeader) ?: return false
        return validateSession(token)
    }

    /**
     * The role behind the cookie: the session record's role, ADMIN when auth
     * is off. Only meaningful after [authenticate] returned true — an unknown
     * cookie folds to ADMIN here because the caller has already rejected it.
     */
    fun sessionRoleFor(cookieHeader: String?): SessionRole {
        if (username == null) return SessionRole.ADMIN
        val token = tokenFromCookie(cookieHeader) ?: return SessionRole.ADMIN
        return sessions[token]?.role ?: SessionRole.ADMIN
    }

    /**
     * Rate-limited, constant-time credential check; mints a session on
     * success. The ladder tries the admin pair first, then the viewer pair —
     * a viewer-username miss falls through to the identical
     * invalid-credentials answer, so a wrong password never reveals which
     * usernames exist.
     */
    fun login(clientIp: String?, username: String, password: String): LoginResult {
        val storedUsername = this.username
        val storedHash = this.passwordHash
        if (storedUsername == null || storedHash == null) {
            return notConfigured()
        }
        val storedViewerUsername = this.viewerUsername
        val storedViewerHash = this.viewerPasswordHash

        val now = clock()
        val matchedRole: SessionRole = synchronized(authAttemptsLock) {
            cleanupExpiredAuthAttempts(now)
            val attempt = authAttempts.getOrPut(clientIp ?: "unknown") { AuthAttempt(0, 0L) }
            if (attempt.blockedUntil > now) return rateLimited()
            if (attempt.count >= MAX_AUTH_ATTEMPTS) {
                attempt.blockedUntil = now + AUTH_LOCKOUT_MS
                attempt.count = 0
                return rateLimited()
            }
            val matched = when {
                StreamAuthCrypto.constantTimeEquals(username, storedUsername) &&
                    StreamAuthCrypto.verifyPassword(password, storedHash) -> SessionRole.ADMIN
                storedViewerUsername != null && storedViewerHash != null &&
                    StreamAuthCrypto.constantTimeEquals(username, storedViewerUsername) &&
                    StreamAuthCrypto.verifyPassword(password, storedViewerHash) -> SessionRole.VIEWER
                else -> null
            }
            if (matched == null) {
                attempt.count++
                return invalidCredentials()
            }
            attempt.count = 0
            matched
        }
        return LoginResult(success = true, token = createSession(matchedRole), role = matchedRole)
    }

    /** The failed-login results: the message is derived from the typed reason. */
    private fun notConfigured(): LoginResult = failure(LoginFailure.NotConfigured)

    private fun rateLimited(): LoginResult = failure(LoginFailure.RateLimited)

    private fun invalidCredentials(): LoginResult = failure(LoginFailure.InvalidCredentials)

    private fun failure(failure: LoginFailure): LoginResult =
        LoginResult(
            success = false,
            error = messageFor(failure),
            failure = failure,
        )

    /** The client-facing message for a failed login, single-homed per reason. */
    private fun messageFor(failure: LoginFailure): String = when (failure) {
        LoginFailure.NotConfigured -> "Auth not configured"
        LoginFailure.RateLimited -> "Too many attempts. Try again later."
        LoginFailure.InvalidCredentials -> "Invalid credentials"
    }

    fun logout(token: String?) {
        if (token != null && sessions.remove(token) != null) {
            persistSessions()
        }
    }

    /** Session bookkeeping for the management surface; the token itself never leaves. */
    data class SessionInfo(val tokenPrefix: String, val expiresAtMs: Long, val role: SessionRole = SessionRole.ADMIN)

    fun sessionsInfo(): List<SessionInfo> {
        cleanExpiredSessions()
        return sessions.entries
            .sortedBy { it.value.expiresAtMs }
            .map { SessionInfo(tokenPrefix = it.key.take(8), expiresAtMs = it.value.expiresAtMs, role = it.value.role) }
    }

    /** Revoke every session (e.g. after an admin credential rotation). */
    fun revokeAllSessions() {
        if (sessions.isNotEmpty()) {
            sessions.clear()
            persistSessions()
        }
    }

    /**
     * Revoke only the viewer-role sessions — the twin of [revokeAllSessions]
     * for viewer-credential rotation, so admin browsers stay signed in when
     * the viewer pair changes.
     */
    fun revokeViewerSessions() {
        val viewerTokens = sessions.entries.filter { it.value.role == SessionRole.VIEWER }.map { it.key }
        if (viewerTokens.isNotEmpty()) {
            viewerTokens.forEach { sessions.remove(it) }
            persistSessions()
        }
    }

    /** Revoke the session whose token starts with [prefix]; true when found. */
    fun revokeSessionByPrefix(prefix: String): Boolean {
        val match = sessions.keys.firstOrNull { it.startsWith(prefix) } ?: return false
        sessions.remove(match)
        persistSessions()
        return true
    }

    /**
     * CSRF protection for state-changing requests: a recognized X-Requested-With
     * header or an Origin/Referer matching this server's [port] and [scheme]
     * (https when the TLS certificate is active).
     */
    fun isCsrfSafe(
        originHeader: String?,
        hasRequestedWithHeader: Boolean,
        port: Int,
        scheme: String = "http",
    ): Boolean {
        if (hasRequestedWithHeader) return true

        if (originHeader != null) {
            val localIps = try {
                NetworkUtils.getAllLocalIpAddresses()
            } catch (_: Exception) {
                listOfNotNull(NetworkUtils.getLocalIpAddress())
            }
            val allowedOrigins = buildList {
                add("$scheme://localhost:$port")
                add("$scheme://127.0.0.1:$port")
                add("$scheme://[::1]:$port")
                localIps.forEach { add("$scheme://${NetworkUtils.formatHostForUrl(it)}:$port") }
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

    private fun createSession(role: SessionRole): String {
        cleanExpiredSessions()
        // Enforce maximum session count to prevent OOM via session flooding
        if (sessions.size >= MAX_SESSIONS) {
            // Evict oldest sessions beyond the cap
            val sorted = sessions.entries.sortedBy { it.value.expiresAtMs }
            val toRemove = sorted.take(sessions.size - MAX_SESSIONS + 1)
            toRemove.forEach { sessions.remove(it.key) }
        }
        val bytes = ByteArray(SESSION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        val token = bytes.toHexString()
        sessions[token] = StoredSession(clock() + SESSION_DURATION_MS, role)
        persistSessions()
        return token
    }

    private fun cleanExpiredSessions() {
        val now = clock()
        if (now - lastSessionCleanupMillis < SESSION_CLEANUP_INTERVAL_MS && sessions.size < MAX_SESSIONS * 0.9) return
        lastSessionCleanupMillis = now
        val expired = sessions.entries.filter { now > it.value.expiresAtMs }.map { it.key }
        expired.forEach { sessions.remove(it) }
        if (expired.isNotEmpty()) persistSessions()
    }

    private fun validateSession(token: String): Boolean {
        // Opportunistically clean expired sessions on each validation
        cleanExpiredSessions()
        val record = sessions[token] ?: return false
        if (clock() > record.expiresAtMs) {
            sessions.remove(token)
            persistSessions()
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

        /** Paths the API token can never touch — credentials and session control. */
        private const val API_TOKEN_DENIED_PATH_PREFIX = "/api/auth/"
    }
}

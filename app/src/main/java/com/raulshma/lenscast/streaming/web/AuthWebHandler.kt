package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.WebAuthGate
import com.raulshma.lenscast.streaming.model.SuccessResponse

/**
 * /api/auth/config + /api/auth/sessions — remote credential rotation and
 * session management. Unlike the four special-cased auth routes, these
 * follow the standard JSON contract (outcome in a 200 payload): they require a
 * live session through the ordinary protected-route gate, so a dashboard can
 * rotate credentials and revoke sessions without touching the phone.
 */
class AuthWebHandler(
    private val settingsDataStore: SettingsDataStore,
    private val webAuthGate: WebAuthGate,
) {

    private val configAdapter by lazy { AppJson.moshi.adapter(AuthConfigDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }
    private val sessionsAdapter by lazy {
        AppJson.moshi.adapter(SessionsResponseDto::class.java)
    }

    fun get(): String {
        val auth = settingsDataStore.authSettings.value
        return configAdapter.toJson(
            AuthConfigDto(enabled = auth.enabled, username = auth.username, password = ""),
        )
    }

    /**
     * Rotate credentials: an empty password keeps the stored hash (the
     * write-only-secret contract). A rotated password revokes every session
     * so stale browsers re-authenticate.
     */
    suspend fun put(body: String): String {
        val request = configAdapter.fromJson(body)
            ?: return successAdapter.toJson(SuccessResponse(success = false))
        val current = settingsDataStore.authSettings.value
        val username = request.username.trim()
        if (request.enabled && (username.isEmpty() || (request.password.isEmpty() && current.passwordHash.isEmpty()))) {
            return successAdapter.toJson(
                SuccessResponse(success = false),
            )
        }
        val passwordHash = if (request.password.isEmpty()) {
            current.passwordHash
        } else {
            StreamAuthCrypto.hashPassword(request.password)
        }
        val digestHa1 = if (request.password.isEmpty()) {
            current.rtspDigestHa1
        } else {
            StreamAuthCrypto.computeRtspDigestHa1(username, request.password)
        }
        settingsDataStore.saveAuthSettings(
            StreamAuthSettings(
                enabled = request.enabled,
                username = username,
                passwordHash = passwordHash,
                rtspDigestHa1 = digestHa1,
            ),
        )
        if (request.password.isNotEmpty()) {
            webAuthGate.revokeAllSessions()
        }
        return successAdapter.toJson(SuccessResponse())
    }

    fun listSessions(): String {
        val sessions = webAuthGate.sessionsInfo()
        return sessionsAdapter.toJson(
            SessionsResponseDto(
                sessions = sessions.map {
                    SessionDto(tokenPrefix = it.tokenPrefix, expiresAtMs = it.expiresAtMs)
                },
                count = sessions.size,
            ),
        )
    }

    fun revokeSession(prefix: String): String {
        val revoked = webAuthGate.revokeSessionByPrefix(prefix)
        return successAdapter.toJson(SuccessResponse(success = revoked))
    }

    data class AuthConfigDto(
        val enabled: Boolean = false,
        val username: String = "",
        val password: String = "",
    )

    data class SessionDto(val tokenPrefix: String, val expiresAtMs: Long)

    data class SessionsResponseDto(val sessions: List<SessionDto>, val count: Int)
}

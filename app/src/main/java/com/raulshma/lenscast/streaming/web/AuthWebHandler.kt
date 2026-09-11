package com.raulshma.lenscast.streaming.web

import com.squareup.moshi.JsonClass

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.SessionRole
import com.raulshma.lenscast.streaming.WebAuthGate
import com.raulshma.lenscast.streaming.model.SuccessResponse

/**
 * /api/auth/config + /api/auth/sessions — remote credential rotation and
 * session management. Unlike the four special-cased auth routes, these
 * follow the standard JSON contract (outcome in a 200 payload): they require a
 * live session through the ordinary protected-route gate, so a dashboard can
 * rotate credentials and revoke sessions without touching the phone. Both are
 * admin-only reads at the gate; [get] still takes the caller's role and
 * redacts the admin username from a viewer session — defense in depth at the
 * DTO should the gate ever change.
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

    /** The admin view shows both usernames; the viewer view hides the admin's. */
    fun get(callerRole: SessionRole = SessionRole.ADMIN): String {
        val auth = settingsDataStore.authSettings.value
        val viewerConfigured = !auth.viewerUsername.isNullOrEmpty() && !auth.viewerPasswordHash.isNullOrEmpty()
        return configAdapter.toJson(
            AuthConfigDto(
                enabled = auth.enabled,
                username = if (callerRole == SessionRole.VIEWER) "" else auth.username,
                password = "",
                viewerEnabled = viewerConfigured,
                viewerUsername = auth.viewerUsername,
                viewerConfigured = viewerConfigured,
            ),
        )
    }

    /**
     * Rotate credentials: an empty password keeps the stored hash (the
     * write-only-secret contract). A rotated admin password revokes every
     * session so stale browsers re-authenticate. The viewer section follows
     * the same contract: an empty viewerPassword keeps the stored viewer hash,
     * and any viewer change (username edit, password rotation, enable/disable)
     * revokes only the viewer sessions — admin browsers stay signed in.
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

        // The viewer pair mirrors the admin write-only-secret contract.
        val viewerUsername = request.viewerUsername?.trim().orEmpty()
        val storedViewerUsername = current.viewerUsername.orEmpty()
        val storedViewerHash = current.viewerPasswordHash.orEmpty()
        var nextViewerUsername: String? = null
        var nextViewerHash: String? = null
        var viewerChanged = false
        when {
            !request.viewerEnabled -> {
                // Disabling clears the pair; a previously configured viewer
                // loses its sessions exactly like a rotation would.
                viewerChanged = storedViewerUsername.isNotEmpty() || storedViewerHash.isNotEmpty()
            }
            viewerUsername.isEmpty() || (request.viewerPassword.isEmpty() && storedViewerHash.isEmpty()) -> {
                // Enabling without a usable pair is rejected without saving —
                // the same verdict the admin section gets.
                return successAdapter.toJson(SuccessResponse(success = false))
            }
            else -> {
                nextViewerUsername = viewerUsername
                nextViewerHash = if (request.viewerPassword.isEmpty()) {
                    storedViewerHash
                } else {
                    StreamAuthCrypto.hashPassword(request.viewerPassword)
                }
                viewerChanged = request.viewerPassword.isNotEmpty() || viewerUsername != storedViewerUsername
            }
        }

        settingsDataStore.saveAuthSettings(
            StreamAuthSettings(
                enabled = request.enabled,
                username = username,
                passwordHash = passwordHash,
                rtspDigestHa1 = digestHa1,
                viewerUsername = nextViewerUsername,
                viewerPasswordHash = nextViewerHash,
            ),
        )
        if (request.password.isNotEmpty()) {
            webAuthGate.revokeAllSessions()
        } else if (viewerChanged) {
            webAuthGate.revokeViewerSessions()
        }
        return successAdapter.toJson(SuccessResponse())
    }

    fun listSessions(): String {
        val sessions = webAuthGate.sessionsInfo()
        return sessionsAdapter.toJson(
            SessionsResponseDto(
                sessions = sessions.map {
                    SessionDto(
                        tokenPrefix = it.tokenPrefix,
                        expiresAtMs = it.expiresAtMs,
                        role = it.role.wireName,
                    )
                },
                count = sessions.size,
            ),
        )
    }

    fun revokeSession(prefix: String): String {
        val revoked = webAuthGate.revokeSessionByPrefix(prefix)
        return successAdapter.toJson(SuccessResponse(success = revoked))
    }

    @JsonClass(generateAdapter = true)
    data class AuthConfigDto(
        val enabled: Boolean = false,
        val username: String = "",
        val password: String = "",
        val viewerEnabled: Boolean = false,
        val viewerUsername: String? = null,
        val viewerPassword: String = "",
        val viewerConfigured: Boolean = false,
    )

    @JsonClass(generateAdapter = true)
    data class SessionDto(
        val tokenPrefix: String,
        val expiresAtMs: Long,
        val role: String = SessionRole.ADMIN.wireName,
    )

    @JsonClass(generateAdapter = true)
    data class SessionsResponseDto(val sessions: List<SessionDto>, val count: Int)
}

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
            ?: return failure("Invalid auth config")
        val current = settingsDataStore.authSettings.value
        val username = request.username.trim()
        if (request.enabled && (username.isEmpty() || (request.password.isEmpty() && current.passwordHash.isEmpty()))) {
            return failure("Username and password are required to enable authentication")
        }
        // The RTSP Digest HA1 is md5(username:realm:password). Only the
        // password hash is stored, so a username change cannot recompute it
        // without the plaintext password; keeping the old HA1 would lock out
        // both the old and the new username (the verifier also checks the
        // presented username). Require the password instead of silently
        // leaving RTSP in a broken state.
        if (request.enabled && username != current.username && request.password.isEmpty() &&
            current.rtspDigestHa1.isNotBlank()
        ) {
            return failure("Password is required when changing the username")
        }
        val passwordHash = if (request.password.isEmpty()) {
            current.passwordHash
        } else {
            StreamAuthCrypto.hashPassword(request.password)
        }
        val digestHa1 = when {
            request.password.isNotEmpty() -> StreamAuthCrypto.computeRtspDigestHa1(username, request.password)
            // A rename while auth is off cannot keep the old HA1 (it would
            // bind the old username to the new stored one). Drop it; RTSP
            // falls back to Basic until a password is entered.
            !request.enabled && username != current.username -> ""
            else -> current.rtspDigestHa1
        }

        // The viewer pair mirrors the admin write-only-secret contract. The
        // whole-config DTO cannot tell an omitted viewer section from an
        // explicit disable, so viewerEnabled is nullable: null preserves the
        // stored pair (the admin section's own save), and only an explicit
        // viewer write may clear or rotate it.
        val storedViewerUsername = current.viewerUsername.orEmpty()
        val storedViewerHash = current.viewerPasswordHash.orEmpty()
        var nextViewerUsername = current.viewerUsername
        var nextViewerHash = current.viewerPasswordHash
        var viewerChanged = false
        val viewerTouched = request.viewerEnabled != null ||
            request.viewerUsername != null ||
            request.viewerPassword.isNotEmpty()
        if (viewerTouched) {
            when (request.viewerEnabled) {
                false -> {
                    // Disabling clears the pair; a previously configured viewer
                    // loses its sessions exactly like a rotation would.
                    viewerChanged = storedViewerUsername.isNotEmpty() || storedViewerHash.isNotEmpty()
                    nextViewerUsername = null
                    nextViewerHash = null
                }
                else -> {
                    val candidateUsername = request.viewerUsername?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: storedViewerUsername
                    val candidateHash = if (request.viewerPassword.isEmpty()) {
                        storedViewerHash
                    } else {
                        StreamAuthCrypto.hashPassword(request.viewerPassword)
                    }
                    // Enabling without a usable pair is rejected without
                    // saving — the same verdict the admin section gets.
                    if (candidateUsername.isEmpty() || candidateHash.isEmpty()) {
                        return failure("Viewer username and password are required")
                    }
                    nextViewerUsername = candidateUsername
                    nextViewerHash = candidateHash
                    viewerChanged = request.viewerPassword.isNotEmpty() ||
                        candidateUsername != storedViewerUsername
                }
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
        val adminCredentialsChanged = request.password.isNotEmpty() || username != current.username
        val disabledNow = current.enabled && !request.enabled
        if (adminCredentialsChanged || disabledNow) {
            webAuthGate.revokeAllSessions()
        }
        if (viewerChanged) {
            webAuthGate.revokeViewerSessions()
        }
        return successAdapter.toJson(SuccessResponse())
    }

    private fun failure(message: String): String =
        successAdapter.toJson(SuccessResponse(success = false, error = message))

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
        /**
         * Null means "viewer fields were not part of this write" — the admin
         * section's save omits them, and a whole-config replace semantics
         * would otherwise read the missing field as `false` and delete the
         * viewer account on every admin edit.
         */
        val viewerEnabled: Boolean? = null,
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

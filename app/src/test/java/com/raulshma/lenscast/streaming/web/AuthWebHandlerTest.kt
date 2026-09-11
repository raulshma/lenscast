package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.SessionRole
import com.raulshma.lenscast.streaming.WebAuthGate
import com.raulshma.lenscast.streaming.model.SuccessResponse
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential-rotation verdicts behind POST /api/auth/config: an empty
 * password keeps the stored hash (the write-only-secret contract), rotation
 * with a new password revokes every session, and the enabled-without-usable-
 * credential combinations are rejected without saving. The viewer section
 * follows the same contract with a targeted revocation (viewer sessions
 * only), and the config read redacts the admin username from a viewer-role
 * caller. Deps are mocked; the handler's validation is the decision under test.
 */
class AuthWebHandlerTest {

    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    private val webAuthGate = mockk<WebAuthGate>(relaxed = true)
    private val saved = slot<StreamAuthSettings>()

    private fun stored(
        enabled: Boolean = true,
        username: String = "old",
        passwordHash: String = "stored-hash",
        rtspDigestHa1: String = "stored-ha1",
        viewerUsername: String? = null,
        viewerPasswordHash: String? = null,
    ) = StreamAuthSettings(
        enabled = enabled,
        username = username,
        passwordHash = passwordHash,
        rtspDigestHa1 = rtspDigestHa1,
        viewerUsername = viewerUsername,
        viewerPasswordHash = viewerPasswordHash,
    )

    private fun handlerWith(current: StreamAuthSettings): AuthWebHandler {
        every { settingsDataStore.authSettings } returns MutableStateFlow(current)
        coJustRun { settingsDataStore.saveAuthSettings(capture(saved)) }
        return AuthWebHandler(settingsDataStore, webAuthGate)
    }

    private fun put(current: StreamAuthSettings, body: String): String =
        runBlocking { handlerWith(current).put(body) }

    private fun successOf(body: String): Boolean =
        AppJson.moshi.adapter(SuccessResponse::class.java).fromJson(body)!!.success

    private fun nothingSaved() {
        coVerify(exactly = 0) { settingsDataStore.saveAuthSettings(any()) }
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
        verify(exactly = 0) { webAuthGate.revokeViewerSessions() }
    }

    // ── rotation with a new password ──

    @Test
    fun `a rotation with a new password hashes it and revokes every session`() {
        val response = put(
            stored(),
            """{"enabled":true,"username":"  dash  ","password":"s3cret"}""",
        )

        assertTrue(successOf(response))
        assertTrue(saved.isCaptured)
        assertEquals("dash", saved.captured.username) // the username is stored trimmed
        assertTrue(StreamAuthCrypto.verifyPassword("s3cret", saved.captured.passwordHash))
        assertEquals(
            StreamAuthCrypto.computeRtspDigestHa1("dash", "s3cret"),
            saved.captured.rtspDigestHa1,
        )
        verify(exactly = 1) { webAuthGate.revokeAllSessions() }
    }

    // ── the write-only-secret contract ──

    @Test
    fun `an empty password keeps the stored hash and digest without revoking sessions`() {
        val response = put(
            stored(passwordHash = "stored-hash", rtspDigestHa1 = "stored-ha1"),
            """{"enabled":true,"username":"dash","password":""}""",
        )

        assertTrue(successOf(response))
        assertEquals("stored-hash", saved.captured.passwordHash)
        assertEquals("stored-ha1", saved.captured.rtspDigestHa1)
        // Only a real rotation invalidates sessions — a username edit or a
        // disable must not log every browser out.
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
    }

    // ── rejection verdicts ──

    @Test
    fun `enabling with a blank username is rejected without saving`() {
        val response = put(
            stored(enabled = false),
            """{"enabled":true,"username":"   ","password":"s3cret"}""",
        )

        assertFalse(successOf(response))
        nothingSaved()
    }

    @Test
    fun `enabling with an empty password and no stored hash is rejected`() {
        val response = put(
            stored(enabled = false, passwordHash = ""),
            """{"enabled":true,"username":"dash","password":""}""",
        )

        assertFalse(successOf(response))
        nothingSaved()
    }

    @Test
    fun `disabling auth needs no username and keeps the stored secret`() {
        val response = put(
            stored(enabled = true, username = "dash", passwordHash = "stored-hash"),
            """{"enabled":false,"username":"","password":""}""",
        )

        assertTrue(successOf(response))
        assertEquals(false, saved.captured.enabled)
        assertEquals("stored-hash", saved.captured.passwordHash)
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
    }

    @Test
    fun `a null body answers failure without saving`() {
        val response = put(stored(), "null")

        assertFalse(successOf(response))
        nothingSaved()
    }

    // ── the config read ──

    @Test
    fun `the config read never returns the stored secret`() {
        val handler = handlerWith(
            stored(enabled = true, username = "dash", passwordHash = "stored-hash"),
        )
        val json = handler.get()
        val config = AppJson.moshi.adapter(AuthWebHandler.AuthConfigDto::class.java).fromJson(json)!!

        assertEquals(true, config.enabled)
        assertEquals("dash", config.username)
        assertEquals("", config.password)
        assertFalse(config.viewerEnabled)
        assertFalse(config.viewerConfigured)
        assertNull(config.viewerUsername)
    }

    // ── the viewer section of the config read ──

    @Test
    fun `the admin view of the config shows both usernames and viewer state`() {
        val handler = handlerWith(
            stored(
                enabled = true,
                username = "dash",
                viewerUsername = "doorbell",
                viewerPasswordHash = "viewer-hash",
            ),
        )
        val config = parse(handler.get())
        assertTrue(config.viewerEnabled)
        assertTrue(config.viewerConfigured)
        assertEquals("doorbell", config.viewerUsername)
        assertEquals("dash", config.username)
    }

    @Test
    fun `the viewer view of the config redacts the admin username but shows the viewer's own`() {
        val handler = handlerWith(
            stored(
                enabled = true,
                username = "dash",
                passwordHash = "stored-hash",
                viewerUsername = "doorbell",
                viewerPasswordHash = "viewer-hash",
            ),
        )
        val config = parse(handler.get(callerRole = SessionRole.VIEWER))
        // Defense in depth: the gate already 403s viewer GETs on this route.
        assertEquals("", config.username)
        assertEquals(true, config.enabled)
        assertTrue(config.viewerConfigured)
        assertEquals("doorbell", config.viewerUsername)
        assertEquals("", config.password)
    }

    private fun parse(json: String): AuthWebHandler.AuthConfigDto =
        AppJson.moshi.adapter(AuthWebHandler.AuthConfigDto::class.java).fromJson(json)!!

    // ── the viewer section of the config write ──

    @Test
    fun `enabling the viewer with a username and password hashes it and revokes only viewer sessions`() {
        val response = put(
            stored(),
            """{"enabled":true,"username":"dash","password":"","viewerEnabled":true,"viewerUsername":"doorbell","viewerPassword":"v3ry-secret"}""",
        )

        assertTrue(successOf(response))
        assertTrue(saved.isCaptured)
        assertEquals("doorbell", saved.captured.viewerUsername)
        assertTrue(StreamAuthCrypto.verifyPassword("v3ry-secret", saved.captured.viewerPasswordHash!!))
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
        verify(exactly = 1) { webAuthGate.revokeViewerSessions() }
    }

    @Test
    fun `an empty viewer password keeps the stored viewer hash without revoking sessions`() {
        val response = put(
            stored(viewerUsername = "doorbell", viewerPasswordHash = "viewer-hash"),
            """{"enabled":true,"username":"dash","password":"","viewerEnabled":true,"viewerUsername":"doorbell","viewerPassword":""}""",
        )

        assertTrue(successOf(response))
        assertEquals("viewer-hash", saved.captured.viewerPasswordHash)
        assertEquals("doorbell", saved.captured.viewerUsername)
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
        verify(exactly = 0) { webAuthGate.revokeViewerSessions() }
    }

    @Test
    fun `a viewer username edit without a password revokes only viewer sessions`() {
        val response = put(
            stored(viewerUsername = "doorbell", viewerPasswordHash = "viewer-hash"),
            """{"enabled":true,"username":"dash","password":"","viewerEnabled":true,"viewerUsername":"door2","viewerPassword":""}""",
        )

        assertTrue(successOf(response))
        assertEquals("door2", saved.captured.viewerUsername)
        assertEquals("viewer-hash", saved.captured.viewerPasswordHash)
        verify(exactly = 1) { webAuthGate.revokeViewerSessions() }
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
    }

    @Test
    fun `disabling the viewer clears the pair and revokes only viewer sessions`() {
        val response = put(
            stored(viewerUsername = "doorbell", viewerPasswordHash = "viewer-hash"),
            """{"enabled":true,"username":"dash","password":"","viewerEnabled":false,"viewerUsername":"","viewerPassword":""}""",
        )

        assertTrue(successOf(response))
        assertNull(saved.captured.viewerUsername)
        assertNull(saved.captured.viewerPasswordHash)
        verify(exactly = 1) { webAuthGate.revokeViewerSessions() }
        verify(exactly = 0) { webAuthGate.revokeAllSessions() }
    }

    @Test
    fun `enabling the viewer with a blank username is rejected without saving`() {
        val response = put(
            stored(),
            """{"enabled":true,"username":"dash","password":"","viewerEnabled":true,"viewerUsername":"  ","viewerPassword":"pw"}""",
        )

        assertFalse(successOf(response))
        nothingSaved()
    }

    @Test
    fun `enabling the viewer with an empty password and no stored hash is rejected`() {
        val response = put(
            stored(),
            """{"enabled":true,"username":"dash","password":"","viewerEnabled":true,"viewerUsername":"doorbell","viewerPassword":""}""",
        )

        assertFalse(successOf(response))
        nothingSaved()
    }

    @Test
    fun `an admin password rotation still revokes every session including viewers`() {
        val response = put(
            stored(viewerUsername = "doorbell", viewerPasswordHash = "viewer-hash"),
            """{"enabled":true,"username":"dash","password":"new-admin-pass","viewerEnabled":true,"viewerUsername":"doorbell","viewerPassword":""}""",
        )

        assertTrue(successOf(response))
        verify(exactly = 1) { webAuthGate.revokeAllSessions() }
        // The blanket revocation subsumes the viewer one.
        verify(exactly = 0) { webAuthGate.revokeViewerSessions() }
    }
}

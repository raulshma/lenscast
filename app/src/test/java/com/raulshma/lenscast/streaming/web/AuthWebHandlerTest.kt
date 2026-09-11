package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential-rotation verdicts behind POST /api/auth/config: an empty
 * password keeps the stored hash (the write-only-secret contract), rotation
 * with a new password revokes every session, and the enabled-without-usable-
 * credential combinations are rejected without saving. Deps are mocked; the
 * handler's validation is the decision under test.
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
    ) = StreamAuthSettings(
        enabled = enabled,
        username = username,
        passwordHash = passwordHash,
        rtspDigestHa1 = rtspDigestHa1,
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
    }
}

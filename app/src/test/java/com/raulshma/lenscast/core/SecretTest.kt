package com.raulshma.lenscast.core

import com.raulshma.lenscast.streaming.model.CameraSettingsDto
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import com.raulshma.lenscast.streaming.model.SettingsUpdateRequestDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Secret wire type's contract, tested at the App Json seam: the adapter
 * serializes every value blank (the write-only GET half), decodes transparently
 * (the PUT half's raw view of the presented plaintext), and [SecretUpdate] is
 * the keep-or-overwrite rule every settings write path resolves through.
 * The web-handler-level consequences (GET responses blank, PUT empty keeps,
 * PUT value overwrites, the apiToken hash-and-gate exception) are covered in
 * SettingsWebHandlerSecretTest; the wire's byte-identity against the checked-in
 * fixtures is DtoContractFixtureTest's job.
 */
class SecretTest {

    private val moshi = AppJson.moshi
    private val secretAdapter = moshi.adapter<Secret>(Secret::class.java)

    // ── serialize blank (the GET half) ──

    @Test
    fun `the adapter serializes every value blank`() {
        assertEquals("\"\"", secretAdapter.toJson(Secret("hunter2")))
        assertEquals("\"\"", secretAdapter.toJson(Secret("")))
        assertEquals("\"\"", secretAdapter.toJson(Secret("   ")))
    }

    @Test
    fun `a representative settings DTO serializes every secret blank through AppJson`() {
        // The values in memory are the real stored secrets — the wire bytes
        // still carry "" for each (this is what makes the handler's plain
        // field copies safe).
        val json = moshi.adapter(SettingsResponseDto::class.java).toJson(
            SettingsResponseDto(
                camera = CameraSettingsDto(),
                streaming = StreamingSettingsDto(
                    rtmpUrl = Secret("rtmp://user:pass@host/live/stream-key"),
                    whipToken = Secret("whip-bearer-token"),
                    srtUrl = Secret("srt://u:p@h:1000?streamid=secret-id"),
                    mqttPassword = Secret("mqtt-pass"),
                    backupWebdavPassword = Secret("dav-pass"),
                    telegramBotToken = Secret("tg-token"),
                    apiToken = Secret("plaintext-token"),
                ),
            ),
        )
        for (field in listOf(
            "rtmpUrl",
            "whipToken",
            "srtUrl",
            "mqttPassword",
            "backupWebdavPassword",
            "telegramBotToken",
            "apiToken",
        )) {
            assertTrue(
                "wire JSON should carry \"$field\":\"\" but was $json",
                json.contains("\"$field\":\"\""),
            )
        }
        // And none of the credentials appear anywhere in the bytes.
        assertFalse(json.contains("stream-key"))
        assertFalse(json.contains("whip-bearer-token"))
        assertFalse(json.contains("secret-id"))
        assertFalse(json.contains("mqtt-pass"))
        assertFalse(json.contains("dav-pass"))
        assertFalse(json.contains("tg-token"))
        assertFalse(json.contains("plaintext-token"))
    }

    // ── decode transparently (the PUT half) ──

    @Test
    fun `a presented secret decodes as-is so the PUT path can resolve it`() {
        val update = moshi.adapter(SettingsUpdateRequestDto::class.java)
            .fromJson("""{"streaming":{"whipToken":"fresh-token","rtmpUrl":""}}""")!!
        val streaming = requireNotNull(update.streaming)
        assertEquals("fresh-token", streaming.whipToken.value)
        // An empty presented value decodes empty — SecretUpdate keeps the stored one.
        assertEquals("", streaming.rtmpUrl.value)
    }

    // ── the keep-or-overwrite rule ──

    @Test
    fun `resolve with an empty incoming keeps the stored secret`() {
        assertEquals("stored", SecretUpdate.resolve("", "stored"))
    }

    @Test
    fun `resolve with an absent incoming keeps the stored secret`() {
        assertEquals("stored", SecretUpdate.resolve(null, "stored"))
    }

    @Test
    fun `resolve with a presented value overwrites`() {
        assertEquals("fresh", SecretUpdate.resolve("fresh", "stored"))
    }

    @Test
    fun `resolve treats whitespace as content`() {
        // Only null/empty keeps: the store's save-side descriptors own any
        // trimming, and the wire-level verdict must not swallow a value.
        assertEquals(" ", SecretUpdate.resolve(" ", "stored"))
    }

    @Test
    fun `overwrites mirrors the resolve branches`() {
        assertFalse(SecretUpdate.overwrites(null))
        assertFalse(SecretUpdate.overwrites(""))
        assertTrue(SecretUpdate.overwrites(" "))
        assertTrue(SecretUpdate.overwrites("fresh"))
    }

    @Test
    fun `willHaveSecret is the apiToken gate's credential-present verdict`() {
        // A presented token arms even with nothing stored...
        assertTrue(SecretUpdate.willHaveSecret("fresh", ""))
        // ...a stored hash keeps the gate satisfied when the update is empty...
        assertTrue(SecretUpdate.willHaveSecret("", "stored-hash"))
        assertTrue(SecretUpdate.willHaveSecret(null, "stored-hash"))
        // ...and an empty update with nothing stored fails closed — enabling
        // the token path without a credential is exactly what the gate exists
        // to prevent.
        assertFalse(SecretUpdate.willHaveSecret("", ""))
        assertFalse(SecretUpdate.willHaveSecret(null, ""))
    }
}

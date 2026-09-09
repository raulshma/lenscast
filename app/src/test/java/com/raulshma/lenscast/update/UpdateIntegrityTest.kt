package com.raulshma.lenscast.update

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.UpdateIntegrity.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * The APK integrity gate under JVM tests: the streaming SHA-256 over an
 * [InputStream] (pinned against known digests and the one-shot MessageDigest
 * across buffer boundaries), the constant-time digest verdict (absent vs
 * wrong-length vs case), the size cross-check, and the asset-model digest
 * decode through App Json.
 */
class UpdateIntegrityTest {

    // sha256Hex

    @Test
    fun `the empty stream hashes to the known sha256`() {
        val hex = UpdateIntegrity.sha256Hex(ByteArrayInputStream(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
    }

    @Test
    fun `abc hashes to the known sha256`() {
        val hex = UpdateIntegrity.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun `a multi-buffer stream matches the one-shot digest`() {
        // 20 KB of deterministic content: crosses the 8 KB read buffer twice.
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val hex = UpdateIntegrity.sha256Hex(ByteArrayInputStream(bytes))
        val oneShot = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format("%02x", it) }
        assertEquals(oneShot, hex)
    }

    // verdictFor

    @Test
    fun `a matching sha256 digest verifies`() {
        val actual = UpdateIntegrity.sha256Hex(ByteArrayInputStream("apk".toByteArray()))
        assertEquals(Verdict.Verified, UpdateIntegrity.verdictFor(actual, "sha256:$actual"))
    }

    @Test
    fun `the hex compare is case-insensitive`() {
        val actual = UpdateIntegrity.sha256Hex(ByteArrayInputStream("apk".toByteArray()))
        assertEquals(Verdict.Verified, UpdateIntegrity.verdictFor(actual, "sha256:${actual.uppercase()}"))
    }

    @Test
    fun `different content is a mismatch`() {
        assertEquals(
            Verdict.Mismatch,
            UpdateIntegrity.verdictFor(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
        )
    }

    @Test
    fun `a wrong-length digest cannot match`() {
        assertEquals(Verdict.Mismatch, UpdateIntegrity.verdictFor("abcd", "sha256:abcdef"))
    }

    @Test
    fun `absent blank or non-sha256 digests degrade to no digest provided`() {
        val actual = UpdateIntegrity.sha256Hex(ByteArrayInputStream("apk".toByteArray()))
        assertEquals(Verdict.NoDigestProvided, UpdateIntegrity.verdictFor(actual, null))
        assertEquals(Verdict.NoDigestProvided, UpdateIntegrity.verdictFor(actual, ""))
        assertEquals(Verdict.NoDigestProvided, UpdateIntegrity.verdictFor(actual, "   "))
        assertEquals(Verdict.NoDigestProvided, UpdateIntegrity.verdictFor(actual, "sha256:"))
        assertEquals(Verdict.NoDigestProvided, UpdateIntegrity.verdictFor(actual, "md5:${actual.take(32)}"))
    }

    // sizeMatches

    @Test
    fun `the size check accepts exact sizes and rejects drift`() {
        assertTrue(UpdateIntegrity.sizeMatches(1024L, 1024L))
        assertFalse(UpdateIntegrity.sizeMatches(1023L, 1024L))
        assertFalse(UpdateIntegrity.sizeMatches(1025L, 1024L))
    }

    @Test
    fun `an unknown expected size never fails the check`() {
        assertTrue(UpdateIntegrity.sizeMatches(0L, 0L))
        assertTrue(UpdateIntegrity.sizeMatches(123L, 0L))
        assertTrue(UpdateIntegrity.sizeMatches(123L, -1L))
    }

    // GitHubAsset digest decode

    @Test
    fun `the asset model decodes the release digest and defaults to null when absent`() {
        val adapter = AppJson.moshi.adapter(GitHubAsset::class.java)
        val withDigest = adapter.fromJson(
            """{"name":"app.apk","browser_download_url":"https://example.invalid/a.apk",""" +
                """"size":1024,"digest":"sha256:abcd"}""",
        )
        assertEquals("sha256:abcd", withDigest?.digest)

        val withoutDigest = adapter.fromJson(
            """{"name":"app.apk","browser_download_url":"https://example.invalid/a.apk","size":1024}""",
        )
        assertNull(withoutDigest?.digest)
    }

    @Test
    fun `the release model round-trips through App Json with the asset digest`() {
        val release = GitHubRelease(
            tagName = "v1.2.0",
            name = "Release v1.2.0",
            body = "Notes",
            htmlUrl = "https://example.invalid",
            assets = listOf(GitHubAsset("app.apk", "https://example.invalid/app.apk", 1024L, "sha256:ff00")),
        )
        val adapter = AppJson.moshi.adapter(GitHubRelease::class.java)
        assertEquals("sha256:ff00", adapter.fromJson(adapter.toJson(release))?.assets?.single()?.digest)
    }
}

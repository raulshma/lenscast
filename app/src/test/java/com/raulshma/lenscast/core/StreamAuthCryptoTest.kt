package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class StreamAuthCryptoTest {

    // ── md5Hex ──

    @Test
    fun `md5Hex matches the published MD5 vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", StreamAuthCrypto.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", StreamAuthCrypto.md5Hex("abc"))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            StreamAuthCrypto.md5Hex("The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun `constantTimeEquals is a plain equality verdict`() {
        assertTrue(StreamAuthCrypto.constantTimeEquals("abc", "abc"))
        assertTrue(StreamAuthCrypto.constantTimeEquals("", ""))
        assertFalse(StreamAuthCrypto.constantTimeEquals("abc", "abd"))
        assertFalse(StreamAuthCrypto.constantTimeEquals("abc", "abcd"))
        assertFalse(StreamAuthCrypto.constantTimeEquals("", "a"))
    }

    // ── sha256Hex ──

    @Test
    fun `sha256Hex matches the published SHA-256 vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            StreamAuthCrypto.sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            StreamAuthCrypto.sha256Hex("abc"),
        )
    }

    @Test
    fun `sha256Hex is lowercase hex and differs per input`() {
        val hex = StreamAuthCrypto.sha256Hex("lenscast-token")
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
        assertNotEquals(StreamAuthCrypto.sha256Hex("token-1"), StreamAuthCrypto.sha256Hex("token-2"))
    }

    // ── hashPassword / verifyPassword ──

    @Test
    fun `hashPassword emits the pbkdf2_sha256 format with 120000 iterations`() {
        val stored = StreamAuthCrypto.hashPassword("s3cret")
        val parts = stored.split("$")
        assertEquals(4, parts.size)
        assertEquals("pbkdf2_sha256", parts[0])
        assertEquals("120000", parts[1])
        assertEquals(16, Base64.getDecoder().decode(parts[2]).size) // random salt
        assertEquals(32, Base64.getDecoder().decode(parts[3]).size) // 256-bit key
    }

    @Test
    fun `hashPassword of empty password is empty`() {
        assertEquals("", StreamAuthCrypto.hashPassword(""))
    }

    @Test
    fun `hashPassword salts every hash differently`() {
        assertNotEquals(StreamAuthCrypto.hashPassword("same"), StreamAuthCrypto.hashPassword("same"))
    }

    @Test
    fun `verifyPassword round-trips the pbkdf2 hash`() {
        val stored = StreamAuthCrypto.hashPassword("s3cret")
        assertTrue(StreamAuthCrypto.verifyPassword("s3cret", stored))
        assertFalse(StreamAuthCrypto.verifyPassword("wrong", stored))
        assertFalse(StreamAuthCrypto.verifyPassword("s3cret ", stored))
    }

    @Test
    fun `verifyPassword rejects empty password or empty stored hash`() {
        assertFalse(StreamAuthCrypto.verifyPassword("", StreamAuthCrypto.hashPassword("x")))
        assertFalse(StreamAuthCrypto.verifyPassword("x", ""))
        assertFalse(StreamAuthCrypto.verifyPassword("", ""))
    }

    @Test
    fun `verifyPassword honors the stored iteration count`() {
        // A stored hash hashed with cheaper iterations still verifies.
        val salt = ByteArray(8) { it.toByte() }
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("pw".toCharArray(), salt, 1_000, 256)).encoded
        val stored = "pbkdf2_sha256\$1000\$" +
            Base64.getEncoder().encodeToString(salt) + "$" +
            Base64.getEncoder().encodeToString(derived)
        assertTrue(StreamAuthCrypto.verifyPassword("pw", stored))
        assertFalse(StreamAuthCrypto.verifyPassword("nope", stored))
    }

    @Test
    fun `verifyPassword rejects malformed pbkdf2 segments`() {
        // Non-numeric iterations → false, not a legacy fallback.
        val badIterations = "pbkdf2_sha256\$abc\$AAAA\$BBBB"
        assertFalse(StreamAuthCrypto.verifyPassword("pw", badIterations))
        // Undecodable salt/hash segments fall through to legacy and miss.
        val badBase64 = "pbkdf2_sha256\$1000\$!!!\$\$???"
        assertFalse(StreamAuthCrypto.verifyPassword("pw", badBase64))
        // Wrong segment count is not the pbkdf2 format.
        assertFalse(StreamAuthCrypto.verifyPassword("pw", "pbkdf2_sha256\$1000"))
    }

    // ── legacy SHA-256 migration branch ──

    @Test
    fun `legacy bare sha-256 base64 hashes still verify`() {
        val legacy = Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest("legacy-pw".toByteArray()))
        assertTrue(StreamAuthCrypto.verifyPassword("legacy-pw", legacy))
        assertFalse(StreamAuthCrypto.verifyPassword("wrong", legacy))
    }

    @Test
    fun `garbage stored hashes verify false rather than throwing`() {
        assertFalse(StreamAuthCrypto.verifyPassword("pw", "not-a-hash"))
        assertFalse(StreamAuthCrypto.verifyPassword("pw", ";;;"))
    }

    // ── computeRtspDigestHa1 ──

    @Test
    fun `ha1 is md5 of username realm password - rfc 2617 vector`() {
        assertEquals(
            "939e7578ed9e3c518a452acee763bce9",
            StreamAuthCrypto.computeRtspDigestHa1("Mufasa", "Circle Of Life", realm = "testrealm@host.com")
        )
    }

    @Test
    fun `ha1 defaults to the shared rtsp digest realm`() {
        assertEquals(
            StreamAuthCrypto.computeRtspDigestHa1("cam", "pw", StreamAuthCrypto.RTSP_DIGEST_REALM),
            StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        )
        assertEquals(
            StreamAuthCrypto.md5Hex("cam:${StreamAuthCrypto.RTSP_DIGEST_REALM}:pw"),
            StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        )
    }

    @Test
    fun `ha1 of empty username or password is empty`() {
        assertEquals("", StreamAuthCrypto.computeRtspDigestHa1("", "pw"))
        assertEquals("", StreamAuthCrypto.computeRtspDigestHa1("cam", ""))
        assertEquals("", StreamAuthCrypto.computeRtspDigestHa1("", ""))
    }

    @Test
    fun `the digest realm is the expected LensCast value`() {
        assertEquals("LensCast RTSP", StreamAuthCrypto.RTSP_DIGEST_REALM)
    }
}

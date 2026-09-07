package com.raulshma.lenscast.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Single home for the stream-auth crypto primitives shared by the RTSP
 * server, the web server, and the stored auth settings. The Digest realm is
 * the one symbol both the HA1 producer (at settings-save time) and the HA1
 * consumer (challenge/verify in the RTSP server) reference — two literals
 * would drift silently and break Digest auth at runtime.
 *
 * Android-free by design: [java.util.Base64] replaces android.util.Base64
 * (NO_WRAP encode == [Base64.getEncoder], DEFAULT decode of our own NO_WRAP
 * values == [Base64.getDecoder]), so the password hash/verify path — including
 * the legacy SHA-256 migration branch — is JVM-tested.
 */
object StreamAuthCrypto {
    const val RTSP_DIGEST_REALM = "LensCast RTSP"

    private const val HASH_PREFIX = "pbkdf2_sha256"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison that does not leak string length via timing.
     * Uses MessageDigest.isEqual on SHA-256 hashes so both operands are always
     * the same length regardless of input.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashA = digest.digest(a.toByteArray(Charsets.UTF_8))
        val hashB = digest.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(hashA, hashB)
    }

    /** PBKDF2-SHA256 with a random salt: "pbkdf2_sha256$iterations$salt$hash". */
    fun hashPassword(password: String): String {
        if (password.isEmpty()) return ""
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derivePassword(password, salt, PBKDF2_ITERATIONS)
        val saltEncoded = Base64.getEncoder().encodeToString(salt)
        val hashEncoded = Base64.getEncoder().encodeToString(derived)
        return "$HASH_PREFIX$$PBKDF2_ITERATIONS$$saltEncoded$$hashEncoded"
    }

    /**
     * Verifies against the PBKDF2 format, falling back to the legacy
     * bare-SHA-256 (Base64, no iterations) format so pre-PBKDF2 stored
     * passwords keep working until the next re-save.
     */
    fun verifyPassword(password: String, storedHash: String): Boolean {
        if (password.isEmpty() || storedHash.isEmpty()) return false

        val parts = storedHash.split("$")
        if (parts.size == 4 && parts[0] == HASH_PREFIX) {
            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = decodeBase64(parts[2]) ?: return false
            val expected = decodeBase64(parts[3]) ?: return false
            val candidate = derivePassword(password, salt, iterations)
            return MessageDigest.isEqual(candidate, expected)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val legacyHash = digest.digest(password.toByteArray(Charsets.UTF_8))
        val expectedLegacy = storedHash.toByteArray(Charsets.UTF_8)
        val candidateLegacy = Base64.getEncoder().encodeToString(legacyHash)
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(candidateLegacy, expectedLegacy)
    }

    fun computeRtspDigestHa1(
        username: String,
        password: String,
        realm: String = RTSP_DIGEST_REALM,
    ): String {
        if (username.isEmpty() || password.isEmpty()) return ""
        return md5Hex("$username:$realm:$password")
    }

    private fun derivePassword(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decodeBase64(value: String): ByteArray? {
        return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }
}

package com.raulshma.lenscast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 must stay byte-identical to the platform's
 * SecretKeyFactory implementation — hashes stored by either one must verify
 * against the other. The RFC 7914 vectors pin the algorithm itself; the
 * cross-checks pin the PBEKeySpec-compatible password encoding.
 */
class Pbkdf2Sha256Test {

    @Test
    fun `rfc 7914 vector - passwd salt 1 iteration 64 bytes`() {
        val derived = Pbkdf2Sha256.derive("passwd", "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            derived.toHex()
        )
    }

    @Test
    fun `rfc 7914 vector - password nacl 80000 iterations 64 bytes`() {
        val derived = Pbkdf2Sha256.derive("Password", "NaCl".toByteArray(), 80_000, 64)
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            derived.toHex()
        )
    }

    @Test
    fun `output is byte-identical to the platform secret key factory`() {
        val random = SecureRandom()
        val passwords = listOf("pw", "pässwörd✓", "quote\"colon:comma,", "1234567890".repeat(8))
        for (password in passwords) {
            val salt = ByteArray(16) { random.nextInt(256).toByte() }
            // PBEKeySpec takes the key length in bits; derive() takes bytes.
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, 1_000, 256))
                .encoded
            assertArrayEquals(expected, Pbkdf2Sha256.derive(password, salt, 1_000, 32))
        }
    }

    @Test
    fun `multi-block keys truncate the trailing block`() {
        val salt = "salt".toByteArray()
        val full = Pbkdf2Sha256.derive("pw", salt, 2, 64)
        val truncated = Pbkdf2Sha256.derive("pw", salt, 2, 10)
        assertArrayEquals(full.copyOf(10), truncated)
        assertEquals(10, truncated.size)
    }

    @Test
    fun `invalid arguments reject instead of deriving`() {
        assertThrows(IllegalArgumentException::class.java) {
            Pbkdf2Sha256.derive("pw", ByteArray(8), 0, 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pbkdf2Sha256.derive("pw", ByteArray(8), 1, 0)
        }
    }

    @Test
    fun `derivation is deterministic for identical inputs`() {
        val salt = ByteArray(8) { it.toByte() }
        assertArrayEquals(
            Pbkdf2Sha256.derive("pw", salt, 50, 32),
            Pbkdf2Sha256.derive("pw", salt, 50, 32),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

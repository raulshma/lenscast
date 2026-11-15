package com.raulshma.lenscast.companion.roster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The at-rest wire shape of the Keystore cipher — `IV || ciphertext` —
 * pinned through its pure assembly helpers. The AndroidKeyStore path itself
 * is deliberately NOT exercised here (the Keystore does not exist on the
 * JVM, and fabricating one under Robolectric would test the shadow, not the
 * TEE-backed key); what a JVM test CAN pin is the byte layout every real
 * decrypt depends on: the leading 12 bytes are the GCM IV, the rest the
 * body, and a payload too short to carry an IV is rejected loudly rather
 * than silently mis-read.
 */
class KeystoreCompanionCredentialCipherTest {

    @Test
    fun `the IV prefix length is GCM's standard 12 bytes`() {
        // A pin, not a tautology: decrypt slices the first IV_BYTES off, so
        // changing this constant silently corrupts every stored credential.
        assertEquals(12, KeystoreCompanionCredentialCipher.IV_BYTES)
    }

    @Test
    fun `combine then split round-trips the payload`() {
        val iv = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        val body = "ciphertext-body".toByteArray(Charsets.UTF_8)
        val (parsedIv, parsedBody) =
            KeystoreCompanionCredentialCipher.splitIvAndCiphertext(
                KeystoreCompanionCredentialCipher.combineIvAndCiphertext(iv, body),
            )
        assertTrue(iv.contentEquals(parsedIv))
        assertTrue(body.contentEquals(parsedBody))
    }

    @Test
    fun `the IV rides in front of the ciphertext`() {
        val iv = ByteArray(KeystoreCompanionCredentialCipher.IV_BYTES) { 0xAB.toByte() }
        val body = byteArrayOf(1, 2, 3)
        val wire = KeystoreCompanionCredentialCipher.combineIvAndCiphertext(iv, body)
        assertEquals(KeystoreCompanionCredentialCipher.IV_BYTES + 3, wire.size)
        for (i in iv.indices) {
            assertEquals(iv[i], wire[i])
        }
        assertEquals(1.toByte(), wire[KeystoreCompanionCredentialCipher.IV_BYTES])
    }

    @Test
    fun `a payload too short for an IV is rejected loudly`() {
        // Exactly-IV-sized and shorter payloads cannot carry a ciphertext
        // body — fail with IllegalArgumentException (the fail-loud posture;
        // never a silent empty-plaintext read).
        assertThrows(IllegalArgumentException::class.java) {
            KeystoreCompanionCredentialCipher.splitIvAndCiphertext(
                ByteArray(KeystoreCompanionCredentialCipher.IV_BYTES),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeystoreCompanionCredentialCipher.splitIvAndCiphertext(ByteArray(4))
        }
    }
}

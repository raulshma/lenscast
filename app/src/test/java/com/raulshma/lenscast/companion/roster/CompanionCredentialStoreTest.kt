package com.raulshma.lenscast.companion.roster

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.StoredCredentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The secret store's contract over a real temp-dir filesDir, sealed by an
 * in-memory XOR cipher (deterministic, reversible — the seam exists exactly
 * so tests need no Keystore): put/get round-trip, an unknown id answers
 * null, remove is a no-op when nothing was saved, a re-put replaces, and a
 * store rebuilt over the same filesDir decrypts what the first one wrote
 * (the base64-of-ciphertext persistence lands and re-reads).
 *
 * The load-bearing case is the at-rest one: the bytes on disk must not
 * contain the plaintext token — that assertion is the whole reason the
 * cipher seam exists, so it pins the file content directly.
 */
class CompanionCredentialStoreTest {

    private var filesDir: File = Files.createTempDirectory("lenscast-creds").toFile()

    /** Deterministic XOR seal — no randomness, so ciphertexts are comparable. */
    private class XorCipher(private val mask: Byte = 0x5A) : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { (plaintext[it].toInt() xor mask.toInt()).toByte() }

        override fun decrypt(ciphertext: ByteArray): ByteArray =
            ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor mask.toInt()).toByte() }
    }

    @After
    fun cleanUp() {
        filesDir.deleteRecursively()
    }

    private fun store(): CompanionCredentialStore = CompanionCredentialStore(filesDir, XorCipher())

    private val tokenCredentials = StoredCredentials(
        kind = CompanionAuthKind.TOKEN,
        token = "secret-bearer-token-abc123",
    )

    private val basicCredentials = StoredCredentials(
        kind = CompanionAuthKind.BASIC,
        username = "admin",
        password = "hunter2",
    )

    private val credentialsFile: File get() = File(File(filesDir, "companion"), "credentials.bin")

    @Test
    fun `put then get round-trips`() {
        val s = store()
        s.put("dev-1", tokenCredentials)
        s.put("dev-2", basicCredentials)
        assertEquals(tokenCredentials, s.get("dev-1"))
        assertEquals(basicCredentials, s.get("dev-2"))
    }

    @Test
    fun `an unknown id answers null`() {
        store().put("dev-1", tokenCredentials)
        assertNull(store().get("never-paired"))
        assertNull(store().get(""))
    }

    @Test
    fun `re-put replaces the stored credentials`() {
        val s = store()
        s.put("dev-1", tokenCredentials)
        val rotated = tokenCredentials.copy(token = "rotated-token-xyz")
        s.put("dev-1", rotated)
        assertEquals(rotated, s.get("dev-1"))
    }

    @Test
    fun `remove drops the entry and is a no-op when absent`() {
        val s = store()
        s.put("dev-1", tokenCredentials)
        s.remove("dev-1")
        assertNull(s.get("dev-1"))
        // Removing an unknown id must not throw or disturb the rest.
        s.remove("never-paired")
        assertNull(store().get("never-paired"))
    }

    @Test
    fun `a rebuilt store decrypts what the first one persisted`() {
        store().put("dev-1", tokenCredentials)
        store().put("dev-2", basicCredentials)
        // Fresh instance: the cache is empty, so this exercises the
        // load-from-disk + decrypt path, not the in-memory one.
        val reloaded = store()
        assertEquals(tokenCredentials, reloaded.get("dev-1"))
        assertEquals(basicCredentials, reloaded.get("dev-2"))
        reloaded.remove("dev-1")
        assertEquals(basicCredentials, store().get("dev-2"))
        assertNull(store().get("dev-1"))
    }

    @Test
    fun `the bytes at rest never contain the plaintext secret`() {
        val s = store()
        s.put("dev-1", tokenCredentials)
        val raw = credentialsFile.readText()
        // The token must not survive as readable text anywhere in the file.
        assertFalse(raw.contains(tokenCredentials.token!!))
        // The wire shape is the frozen `{deviceId: base64(ciphertext)}` map —
        // one level, blob value: no nested plaintext JSON.
        assertTrue(raw.startsWith("{\"dev-1\":\""))
        assertTrue(raw.endsWith("\"}"))
    }

    @Test
    fun `basic-auth secrets are equally absent from disk`() {
        store().put("dev-1", basicCredentials)
        val raw = credentialsFile.readText()
        assertFalse(raw.contains(basicCredentials.password!!))
        assertFalse(raw.contains(basicCredentials.username!!))
    }
}

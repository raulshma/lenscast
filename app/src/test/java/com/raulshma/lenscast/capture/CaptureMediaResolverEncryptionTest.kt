package com.raulshma.lenscast.capture

import com.raulshma.lenscast.core.MediaCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The transparent-decrypt half of the scheme ladder: a plaintext file passes
 * through untouched, an encrypted-at-rest file (the `LCE1` header) decrypts
 * through the wired [MediaCrypto.KeyProvider], and without a provider an
 * encrypted file fails closed — ciphertext is never served as media. Plain
 * paths only; the content:// branches stay device-only like the base
 * resolver test.
 */
class CaptureMediaResolverEncryptionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keyProvider = MediaCrypto.KeyProvider {
        javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
    }

    private val resolver = CaptureMediaResolver(keyProvider = keyProvider)

    private fun writePhoto(name: String, bytes: ByteArray): File {
        val file = tmp.root.resolve(name)
        file.writeBytes(bytes)
        return file
    }

    private fun encryptedFile(name: String, plaintext: ByteArray): File {
        val key = keyProvider.getOrCreateKey()
        return writePhoto(name, MediaCrypto.encrypt(key, plaintext))
    }

    // ── Sniff ──

    @Test
    fun `plaintext files do not sniff as encrypted`() {
        val file = writePhoto("IMG_plain.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3))
        assertFalse(resolver.isEncryptedAtRest(file.absolutePath))
    }

    @Test
    fun `encrypted files sniff positive and missing files negative`() {
        val file = encryptedFile("IMG_enc.jpg", "photo bytes".toByteArray())
        assertTrue(resolver.isEncryptedAtRest(file.absolutePath))
        assertFalse(resolver.isEncryptedAtRest(tmp.root.resolve("missing.jpg").absolutePath))
    }

    // ── Decrypt on read ──

    @Test
    fun `plaintext media passes through byte-identical`() {
        val bytes = ByteArray(64 * 1024 + 3) { (it % 251).toByte() }
        val file = writePhoto("IMG_full.jpg", bytes)
        resolver.openStream(file.absolutePath)!!.use { stream ->
            assertArrayEquals(bytes, stream.readBytes())
        }
    }

    @Test
    fun `a tiny plaintext file keeps its bytes after the sniff`() {
        val bytes = byteArrayOf(1, 2) // too small to even carry the header
        val file = writePhoto("IMG_tiny.jpg", bytes)
        resolver.openStream(file.absolutePath)!!.use { stream ->
            assertArrayEquals(bytes, stream.readBytes())
        }
    }

    @Test
    fun `encrypted media opens decrypted`() {
        val plaintext = "the actual jpeg bytes".toByteArray()
        val file = encryptedFile("IMG_secret.jpg", plaintext)
        resolver.openStream(file.absolutePath)!!.use { stream ->
            assertArrayEquals(plaintext, stream.readBytes())
        }
        // The explicit alias is the same contract.
        resolver.openDecryptedStream(file.absolutePath)!!.use { stream ->
            assertArrayEquals(plaintext, stream.readBytes())
        }
    }

    @Test
    fun `encrypted media with no key provider fails closed`() {
        val plaintext = "secret".toByteArray()
        val file = encryptedFile("IMG_locked.jpg", plaintext)
        val keyless = CaptureMediaResolver()
        assertNull(keyless.openStream(file.absolutePath))
        assertNull(keyless.openDecryptedStream(file.absolutePath))
    }

    // ── openMedia size reporting ──

    @Test
    fun `openMedia reports the plaintext size for encrypted media`() {
        val plaintext = ByteArray(5_000)
        val file = encryptedFile("IMG_sized.jpg", plaintext)
        val opened = resolver.openMedia(file.absolutePath, recordedSizeBytes = 7L)
        requireNotNull(opened)
        opened.stream.use { assertArrayEquals(plaintext, it.readBytes()) }
        assertEquals(MediaCrypto.ciphertextSize(plaintext.size.toLong()), file.length())
        assertEquals(plaintext.size.toLong(), opened.sizeBytes)
    }

    @Test
    fun `openMedia reports the stored size for plaintext media`() {
        val bytes = ByteArray(123)
        val file = writePhoto("IMG_plain_size.jpg", bytes)
        val opened = resolver.openMedia(file.absolutePath, recordedSizeBytes = 7L)
        requireNotNull(opened)
        assertEquals(123L, opened.sizeBytes)
    }

    // ── Mixed library (the migration discipline) ──

    @Test
    fun `plaintext and encrypted captures coexist`() {
        val plainBytes = "old plaintext capture".toByteArray()
        val encryptedBytes = "new encrypted capture".toByteArray()
        val plain = writePhoto("IMG_old.jpg", plainBytes)
        val encrypted = encryptedFile("IMG_new.jpg", encryptedBytes)

        resolver.openStream(plain.absolutePath)!!.use { assertArrayEquals(plainBytes, it.readBytes()) }
        resolver.openStream(encrypted.absolutePath)!!.use { assertArrayEquals(encryptedBytes, it.readBytes()) }
    }

    // ── Deletion is sniff-agnostic ──

    @Test
    fun `deleting an encrypted capture removes the stored file`() {
        val file = encryptedFile("IMG_del.jpg", "bye".toByteArray())
        assertTrue(resolver.delete(file.absolutePath))
        assertFalse(file.exists())
    }
}

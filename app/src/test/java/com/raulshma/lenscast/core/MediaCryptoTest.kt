package com.raulshma.lenscast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * The media-at-rest crypto: header format, size math, the one-shot and
 * stream round-trips, and the two failure modes that must never pass
 * silently — a tampered ciphertext and a truncated one. Everything runs on
 * the JVM over the fake [MediaCrypto.KeyProvider]; the Keystore itself is
 * device-only and hidden behind the interface.
 */
class MediaCryptoTest {

    private val key = javax.crypto.KeyGenerator.getInstance("AES").apply {
        init(MediaCrypto.KEY_SIZE_BITS)
    }.generateKey()

    /** The fake provider JVM tests wire in place of the Keystore. */
    private val fakeProvider = MediaCrypto.KeyProvider { key }

    // ── Header format ──

    @Test
    fun `header is magic plus nonce and parses back`() {
        val nonce = MediaCrypto.newNonce()
        val header = MediaCrypto.headerFor(nonce)
        assertEquals(MediaCrypto.MAGIC_TEXT, String(header, 0, MediaCrypto.MAGIC_BYTES.size, Charsets.US_ASCII))
        assertEquals(MediaCrypto.HEADER_SIZE_BYTES, header.size)
        assertArrayEquals(nonce, MediaCrypto.parseHeader(header))
    }

    @Test
    fun `parseHeader rejects a wrong magic, plaintext bytes, and short input`() {
        assertNull(MediaCrypto.parseHeader("JPEGxxxx".toByteArray()))
        assertNull(MediaCrypto.parseHeader(ByteArray(MediaCrypto.HEADER_SIZE_BYTES)))
        assertNull(MediaCrypto.parseHeader(MediaCrypto.MAGIC_BYTES)) // magic only, nonce missing
        assertNull(MediaCrypto.parseHeader(ByteArray(3)))
    }

    @Test
    fun `sniff accepts the magic prefix only`() {
        assertTrue(MediaCrypto.isEncryptedHeader(MediaCrypto.headerFor(MediaCrypto.newNonce())))
        assertFalse(MediaCrypto.isEncryptedHeader(byteArrayOf(1, 2)))
        assertFalse(MediaCrypto.isEncryptedHeader("LCE2 rest".toByteArray(Charsets.US_ASCII)))
        assertFalse(MediaCrypto.isEncryptedHeader(ByteArray(0)))
    }

    @Test
    fun `nonces are unique and 12 bytes`() {
        val seen = mutableSetOf<String>()
        repeat(64) {
            val nonce = MediaCrypto.newNonce(SecureRandom())
            assertEquals(MediaCrypto.NONCE_SIZE_BYTES, nonce.size)
            assertTrue(seen.add(nonce.joinToString("") { "%02x".format(it) }))
        }
    }

    // ── Size math ──

    @Test
    fun `plaintext and ciphertext sizes invert each other`() {
        assertEquals(MediaCrypto.OVERHEAD_BYTES.toLong(), MediaCrypto.ciphertextSize(0L))
        assertEquals(1000L, MediaCrypto.ciphertextSize(1000L) - MediaCrypto.OVERHEAD_BYTES)
        assertEquals(1000L, MediaCrypto.plaintextSize(MediaCrypto.ciphertextSize(1000L)))
        assertEquals(0L, MediaCrypto.plaintextSize(0L))
        assertEquals(0L, MediaCrypto.plaintextSize(1L)) // floored: never negative
    }

    // ── One-shot round-trip ──

    @Test
    fun `encrypt then decrypt restores the plaintext`() {
        val plaintext = ByteArray(777) { ('a' + it % 26).code.toByte() }
        val blob = MediaCrypto.encrypt(key, plaintext)
        assertEquals(MediaCrypto.ciphertextSize(plaintext.size.toLong()), blob.size.toLong())
        assertArrayEquals(plaintext, MediaCrypto.decrypt(key, blob))
    }

    @Test
    fun `same plaintext under two nonces encrypts differently`() {
        val plaintext = "lenscast".toByteArray()
        val first = MediaCrypto.encrypt(key, plaintext)
        val second = MediaCrypto.encrypt(key, plaintext)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, MediaCrypto.decrypt(key, first))
    }

    @Test
    fun `decrypting with the wrong key fails the tag check`() {
        val otherKey = javax.crypto.KeyGenerator.getInstance("AES").apply {
            init(MediaCrypto.KEY_SIZE_BITS)
        }.generateKey()
        val blob = MediaCrypto.encrypt(key, "secret".toByteArray())
        try {
            MediaCrypto.decrypt(otherKey, blob)
            throw AssertionError("expected AEADBadTagException")
        } catch (expected: AEADBadTagException) {
            // the GCM auth verdict
        }
    }

    @Test
    fun `a tampered ciphertext byte fails the tag check`() {
        val blob = MediaCrypto.encrypt(key, "secret capture bytes".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x41).toByte()
        try {
            MediaCrypto.decrypt(key, blob)
            throw AssertionError("expected AEADBadTagException")
        } catch (expected: AEADBadTagException) {
        }
    }

    @Test
    fun `a truncated ciphertext fails instead of decrypting partially`() {
        val blob = MediaCrypto.encrypt(key, "secret capture bytes".toByteArray())
        val truncated = blob.copyOf(blob.size - 5)
        try {
            MediaCrypto.decrypt(key, truncated)
            throw AssertionError("expected AEADBadTagException")
        } catch (expected: AEADBadTagException) {
        }
    }

    @Test
    fun `decrypt rejects a plaintext blob (no magic)`() {
        try {
            MediaCrypto.decrypt(key, "plain jpeg bytes".toByteArray())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ── Stream round-trip (the encrypt-on-write / decrypt-on-read path) ──

    @Test
    fun `stream round-trip restores data larger than the internal chunk`() {
        val plaintext = ByteArray(3 * 64 * 1024 + 17) { (it % 251).toByte() } // 3.3 chunks
        val sink = ByteArrayOutputStream()
        MediaCrypto.encryptTo(key, sink).use { encrypted ->
            ByteArrayInputStream(plaintext).copyTo(encrypted)
        }
        val blob = sink.toByteArray()
        assertEquals(MediaCrypto.ciphertextSize(plaintext.size.toLong()), blob.size.toLong())
        MediaCrypto.decryptingStream(key, blob).use { decrypted ->
            assertArrayEquals(plaintext, decrypted.readBytes())
        }
    }

    @Test
    fun `encrypt stream appends the header and the decrypt stream skips it`() {
        val plaintext = "photo bytes".toByteArray()
        val sink = ByteArrayOutputStream()
        MediaCrypto.encryptTo(key, sink, nonce = ByteArray(MediaCrypto.NONCE_SIZE_BYTES) { 7 }).use {
            it.write(plaintext)
        }
        val blob = sink.toByteArray()
        assertTrue(MediaCrypto.isEncryptedHeader(blob))
        MediaCrypto.decryptingStream(key, blob).use { decrypted ->
            assertArrayEquals(plaintext, decrypted.readBytes())
        }
    }

    @Test
    fun `tampering mid-stream surfaces on read, not as early EOF`() {
        val plaintext = ByteArray(64 * 1024 + 9) { (it % 251).toByte() }
        val sink = ByteArrayOutputStream()
        MediaCrypto.encryptTo(key, sink).use { ByteArrayInputStream(plaintext).copyTo(it) }
        val blob = sink.toByteArray()
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 0x01).toByte()
        try {
            MediaCrypto.decryptingStream(key, blob).use { it.readBytes() }
            throw AssertionError("expected the GCM tag check to fail")
        } catch (expected: AEADBadTagException) {
        }
    }

    @Test
    fun `decrypt stream skip read-discards sequentially`() {
        val plaintext = ByteArray(10_000) { (it % 251).toByte() }
        val blob = MediaCrypto.encrypt(key, plaintext)
        MediaCrypto.decryptingStream(key, blob).use { decrypted ->
            assertEquals(4_000L, decrypted.skip(4_000))
            assertEquals(plaintext[4_000].toInt() and 0xFF, decrypted.read())
        }
    }

    @Test
    fun `encryptTo honors an injected nonce (deterministic test path)`() {
        val nonce = MediaCrypto.newNonce()
        val sink = ByteArrayOutputStream()
        MediaCrypto.encryptTo(key, sink, nonce).use { it.write(ByteArray(10)) }
        assertArrayEquals(nonce, MediaCrypto.parseHeader(sink.toByteArray()))
    }

    // ── The provider seam ──

    @Test
    fun `the fake provider hands back a working key`() {
        val providerKey = fakeProvider.getOrCreateKey()
        val blob = MediaCrypto.encrypt(providerKey, "via provider".toByteArray())
        assertArrayEquals("via provider".toByteArray(), MediaCrypto.decrypt(providerKey, blob))
    }

    @Test
    fun `encrypting streams count the plaintext they accepted`() {
        val sink = ByteArrayOutputStream()
        val plaintext = ByteArray(1234)
        MediaCrypto.encryptTo(key, sink).use { encrypted ->
            ByteArrayInputStream(plaintext).copyTo(encrypted)
            assertEquals(1234L, encrypted.plaintextBytes)
        }
    }

    @Test
    fun `encryptTo writes the header before any plaintext write`() {
        val sink = ByteArrayOutputStream()
        val stream = MediaCrypto.encryptTo(key, sink, MediaCrypto.newNonce())
        // Header is out already, before a single write through the stream.
        assertEquals(MediaCrypto.HEADER_SIZE_BYTES, sink.size())
        stream.close()
    }

    @Test
    fun `magic bytes are stable`() {
        assertArrayEquals("LCE1".toByteArray(Charsets.US_ASCII), MediaCrypto.MAGIC_BYTES)
    }
}

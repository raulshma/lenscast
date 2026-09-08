package com.raulshma.lenscast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Base64Codec must be byte-identical to java.util.Base64 — the class it
 * replaces for minSdk 23 — including the strict decoder's accept/reject
 * line. java.util.Base64 itself is the oracle (JVM-only, allowed in tests).
 */
class Base64CodecTest {

    // ── encode ──

    @Test
    fun `encode matches the rfc 4648 test vectors`() {
        assertEquals("", Base64Codec.encode("".toByteArray()))
        assertEquals("Zg==", Base64Codec.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64Codec.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Codec.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", Base64Codec.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", Base64Codec.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Codec.encode("foobar".toByteArray()))
    }

    @Test
    fun `encode and encodeUrlSafe match java_util base64 on random inputs`() {
        val random = SecureRandom()
        repeat(500) {
            val bytes = ByteArray(random.nextInt(97)) { random.nextInt(256).toByte() }
            assertEquals(Base64.getEncoder().encodeToString(bytes), Base64Codec.encode(bytes))
            assertEquals(Base64.getUrlEncoder().encodeToString(bytes), Base64Codec.encodeUrlSafe(bytes))
        }
    }

    // ── decode: the accepts ──

    @Test
    fun `decode round-trips random bytes`() {
        val random = SecureRandom()
        repeat(500) {
            val bytes = ByteArray(random.nextInt(97)) { random.nextInt(256).toByte() }
            assertArrayEquals(bytes, Base64Codec.decodeOrNull(Base64Codec.encode(bytes)))
        }
    }

    @Test
    fun `decode accepts unpadded tails like java_util base64`() {
        assertArrayEquals(byteArrayOf(0x41), Base64Codec.decodeOrNull("QQ"))
        assertArrayEquals("AB".toByteArray(), Base64Codec.decodeOrNull("QUI"))
    }

    // ── decode: the rejects ──

    @Test
    fun `decode rejects what the strict java_util decoder throws on`() {
        assertNull("dangling unit", Base64Codec.decodeOrNull("Q"))
        assertNull("wrong padding length", Base64Codec.decodeOrNull("QQ="))
        assertNull("padding in the middle", Base64Codec.decodeOrNull("QQ=A"))
        assertNull("padding in the middle", Base64Codec.decodeOrNull("AA=A"))
        assertNull("padding only", Base64Codec.decodeOrNull("===="))
        assertNull("whitespace is not skipped", Base64Codec.decodeOrNull("QQ==\n"))
        assertNull("whitespace is not skipped", Base64Codec.decodeOrNull("QUJD\n"))
        assertNull("space is not skipped", Base64Codec.decodeOrNull("QU JD"))
        assertNull("url-safe alphabet is not basic", Base64Codec.decodeOrNull("QU-J"))
        assertNull("empty-ish garbage", Base64Codec.decodeOrNull("$"))
    }

    // ── strictness equivalence, property-style ──

    @Test
    fun `decode agrees with java_util base64 on encoded and corrupted inputs`() {
        val random = SecureRandom()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        repeat(500) {
            // Non-empty so there is always a character position to corrupt.
            val bytes = ByteArray(1 + random.nextInt(64)) { random.nextInt(256).toByte() }
            val encoded = Base64.getEncoder().encodeToString(bytes)

            // Untouched input decodes identically.
            assertArrayEquals(bytes, Base64Codec.decodeOrNull(encoded))

            // One corrupted character position: both decoders must reject.
            val chars = encoded.toCharArray()
            val position = random.nextInt(chars.size)
            val intruder = if (random.nextBoolean()) '=' else '\n'
            if (chars[position] != intruder) {
                chars[position] = intruder
                val corrupted = String(chars)
                val javaAccepts = runCatching { Base64.getDecoder().decode(corrupted) }.isSuccess
                if (!javaAccepts) {
                    assertNull("expected rejection: $corrupted", Base64Codec.decodeOrNull(corrupted))
                }
            }
        }
    }
}

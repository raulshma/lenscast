package com.raulshma.lenscast.core.push

import com.raulshma.lenscast.core.Base64Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * The Web Push wire crypto under JVM tests — self-consistency by construction
 * plus published-vector pinning where the RFCs fix bytes:
 *
 * - HKDF-SHA256 against RFC 5869 Appendix A Test Case 1 (the one published
 *   vector in the whole ladder).
 * - The aes128gcm round-trip: [PushEncryption.decrypt] re-derives every
 *   intermediate (ECDH secret, IKM, CEK, nonce) independently from the
 *   receiver's own key material, so a passing round-trip verifies the RFC
 *   8291 construction end to end (wrong key material or a flipped auth secret
   * fails the GCM tag, not the test).
 * - Structure checks pin the RFC 8188 header layout (salt ‖ rs ‖ idlen ‖
 *   keyid) and RFC 8291 §5's 0x02 padding delimiter.
 * - RFC 8292 VAPID: the JWT's ES256 signature verifies with the public key,
 *   the raw r‖s conversion is pinned, and `aud` derivation covers default and
 *   explicit ports.
 */
class PushEncryptionTest {

    private fun p256KeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return generator.generateKeyPair()
    }

    private fun receiverMaterial(): Triple<KeyPair, PushEncryption.ReceiverKey, ByteArray> {
        val receiver = p256KeyPair()
        val point = VapidKeys.uncompressedPoint(receiver.public as ECPublicKey)
        val authSecret = ByteArray(16) { it.toByte() }
        return Triple(receiver, PushEncryption.ReceiverKey(point, authSecret), authSecret)
    }

    // ── HKDF (RFC 5869) ──

    @Test
    fun `hkdf matches the RFC 5869 SHA-256 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())

        val okm = PushEncryption.hkdf(ikm, salt, info, 42)

        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        assertArrayEquals(expected, okm)
    }

    // ── receiver key admission ──

    @Test
    fun `receiverKeyOrNull accepts the 65-byte point with a 16-byte auth secret`() {
        val (_, key, auth) = receiverMaterial()
        val decoded = PushEncryption.receiverKeyOrNull(
            PushEncryption.base64Url(key.publicKey),
            PushEncryption.base64Url(auth),
        )
        assertNotNull(decoded)
        assertArrayEquals(key.publicKey, decoded!!.publicKey)
        assertArrayEquals(auth, decoded.authSecret)
    }

    @Test
    fun `receiverKeyOrNull rejects malformed shapes`() {
        val (_, key, auth) = receiverMaterial()
        // Compressed point (33 bytes), wrong auth length, undecodable base64.
        assertNull(PushEncryption.receiverKeyOrNull(PushEncryption.base64Url(ByteArray(33)), PushEncryption.base64Url(auth)))
        assertNull(PushEncryption.receiverKeyOrNull(PushEncryption.base64Url(key.publicKey), PushEncryption.base64Url(ByteArray(15))))
        assertNull(PushEncryption.receiverKeyOrNull("not@base64!!", PushEncryption.base64Url(auth)))
    }

    // ── aes128gcm round-trip and structure ──

    @Test
    fun `encrypt then decrypt round-trips the payload`() {
        val (receiverPair, receiverKey, authSecret) = receiverMaterial()
        val sender = p256KeyPair()
        val payload = """{"title":"LensCast Motion alert","body":"Doorway · person"}""".toByteArray()

        val body = PushEncryption.encrypt(payload, receiverKey, sender)

        val decrypted = PushEncryption.decrypt(
            body,
            receiverPublicPoint = receiverKey.publicKey,
            receiverPrivate = receiverPair.private,
            authSecret = authSecret,
        )
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun `the aes128gcm header carries salt, rs 4096, and the ephemeral keyid`() {
        val (_, receiverKey, _) = receiverMaterial()
        val sender = p256KeyPair()
        val senderPoint = VapidKeys.uncompressedPoint(sender.public as ECPublicKey)
        val salt = ByteArray(16) { (it * 3).toByte() }

        val body = PushEncryption.encrypt("payload".toByteArray(), receiverKey, sender, salt = salt)

        // Layout: salt(16) ‖ rs(4, big-endian) ‖ idlen(1) ‖ keyid(idlen) ‖ record.
        assertArrayEquals(salt, body.copyOfRange(0, 16))
        val rs = ((body[16].toInt() and 0xFF) shl 24) or ((body[17].toInt() and 0xFF) shl 16) or
            ((body[18].toInt() and 0xFF) shl 8) or (body[19].toInt() and 0xFF)
        assertEquals(PushEncryption.RECORD_SIZE, rs)
        assertEquals(65, body[20].toInt() and 0xFF)
        assertArrayEquals(senderPoint, body.copyOfRange(21, 21 + 65))
        // Record: plaintext + 0x02 delimiter + 16-byte GCM tag.
        assertEquals(21 + 65 + "payload".length + 1 + 16, body.size)
    }

    @Test
    fun `a different ephemeral sender yields a different ciphertext`() {
        val (_, receiverKey, _) = receiverMaterial()
        val payload = "same payload".toByteArray()

        val first = PushEncryption.encrypt(payload, receiverKey, p256KeyPair())
        val second = PushEncryption.encrypt(payload, receiverKey, p256KeyPair())

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `the wrong auth secret fails the GCM tag`() {
        val (receiverPair, receiverKey, _) = receiverMaterial()
        val sender = p256KeyPair()
        val body = PushEncryption.encrypt("secret".toByteArray(), receiverKey, sender)

        val wrongAuth = ByteArray(16) { (it + 1).toByte() }
        try {
            PushEncryption.decrypt(
                body,
                receiverPublicPoint = receiverKey.publicKey,
                receiverPrivate = receiverPair.private,
                authSecret = wrongAuth,
            )
            fail("decryption with the wrong auth secret must fail")
        } catch (expected: Exception) {
            assertTrue(expected is AEADBadTagException || expected.cause is AEADBadTagException)
        }
    }

    @Test
    fun `a different receiver private key fails the GCM tag`() {
        val (_, receiverKey, authSecret) = receiverMaterial()
        val sender = p256KeyPair()
        val body = PushEncryption.encrypt("secret".toByteArray(), receiverKey, sender)

        val stranger = p256KeyPair()
        try {
            PushEncryption.decrypt(
                body,
                receiverPublicPoint = VapidKeys.uncompressedPoint(stranger.public as ECPublicKey),
                receiverPrivate = stranger.private,
                authSecret = authSecret,
            )
            fail("decryption under the wrong receiver key must fail")
        } catch (expected: Exception) {
            assertTrue(expected is AEADBadTagException || expected.cause is AEADBadTagException)
        }
    }

    @Test
    fun `decrypt rejects a truncated or missing-keyid header`() {
        val (receiverPair, receiverKey, authSecret) = receiverMaterial()
        try {
            PushEncryption.decrypt(ByteArray(10), receiverKey.publicKey, receiverPair.private, authSecret)
            fail("truncated body must fail")
        } catch (_: IllegalArgumentException) {
        }
        // idlen = 0 means no keyid — the ephemeral key can never arrive.
        val zeroIdLen = ByteArray(21 + 65 + 20)
        try {
            PushEncryption.decrypt(zeroIdLen, receiverKey.publicKey, receiverPair.private, authSecret)
            fail("zero idlen must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── VAPID (RFC 8292) ──

    @Test
    fun `vapidAuthorization builds a verifiable compact JWT`() {
        val identity = p256KeyPair()
        val endpoint = "https://fcm.googleapis.com/fcm/send/abcd1234"

        val header = PushEncryption.vapidAuthorization(
            endpoint = endpoint,
            keyPair = identity,
            subject = "mailto:operator@example.com",
            nowMs = 1_700_000_000_000L,
        )

        assertTrue(header.startsWith("vapid t="))
        assertTrue(header.contains(", k="))
        val token = header.removePrefix("vapid t=").substringBefore(",")
        val parts = token.split('.')
        assertEquals(3, parts.size)

        // The header and claims segments decode to the RFC 8292 shape.
        val headerJson = String(Base64Codec.decodeUrlSafeOrNull(parts[0])!!, Charsets.UTF_8)
        val claimsJson = String(Base64Codec.decodeUrlSafeOrNull(parts[1])!!, Charsets.UTF_8)
        assertEquals("""{"typ":"JWT","alg":"ES256"}""", headerJson)
        assertTrue(claimsJson.contains("\"aud\":\"https://fcm.googleapis.com\""))
        assertTrue(claimsJson.contains("\"exp\":${1_700_000_000L + 12 * 60 * 60}"))
        assertTrue(claimsJson.contains("\"sub\":\"mailto:operator@example.com\""))

        // The third segment is the raw 64-byte r‖s signature, verifiable with
        // the public key through standard ECDSA over the signing input.
        val rawSignature = Base64Codec.decodeUrlSafeOrNull(parts[2])!!
        assertEquals(64, rawSignature.size)
        val derSignature = rawToDer(rawSignature)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(identity.public)
        verifier.update((parts[0] + "." + parts[1]).toByteArray())
        assertTrue(verifier.verify(derSignature))

        // k carries the uncompressed public point.
        val k = header.substringAfter(", k=")
        assertArrayEquals(
            VapidKeys.uncompressedPoint(identity.public as ECPublicKey),
            Base64Codec.decodeUrlSafeOrNull(k)!!,
        )
    }

    @Test
    fun `audienceOf elides the default port and keeps explicit ones`() {
        assertEquals("https://fcm.googleapis.com", PushEncryption.audienceOf("https://fcm.googleapis.com/fcm/send/x"))
        assertEquals("https://fcm.googleapis.com:8443", PushEncryption.audienceOf("https://fcm.googleapis.com:8443/fcm/send/x"))
        assertEquals("http://autoupdate.push.services.mozilla.com", PushEncryption.audienceOf("http://autoupdate.push.services.mozilla.com:80/"))
        assertEquals("https://example.com", PushEncryption.audienceOf("https://example.com"))
    }

    @Test
    fun `derToRaw converts DER signatures to the fixed 64-byte JOSE form`() {
        val identity = p256KeyPair()

        for (round in 1..8) {
            // A fresh signer per round: update() accumulates, so a shared
            // instance would sign the concatenated input of every prior round.
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(identity.private)
            signer.update("input $round".toByteArray())
            val der = signer.sign()
            val raw = PushEncryption.derToRaw(der)
            assertEquals(64, raw.size)
            // The conversion is invertible: re-encoding r‖s to DER verifies
            // under the same key for the same input.
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(identity.public)
            verifier.update("input $round".toByteArray())
            assertTrue(verifier.verify(rawToDer(raw)))
        }
    }

    // ── urgency + payload mapping ──

    @Test
    fun `urgency maps tamper and motion to high, the rest to normal`() {
        assertEquals("high", WebPushSender.urgencyFor("motion"))
        assertEquals("high", WebPushSender.urgencyFor("tamper"))
        assertEquals("normal", WebPushSender.urgencyFor("sound"))
        assertEquals("normal", WebPushSender.urgencyFor("test"))
    }

    // ── helpers ──

    private fun hex(value: String): ByteArray {
        val clean = value.replace(Regex("\\s"), "")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /** Re-encodes a raw 64-byte r‖s signature into DER for JCA verification. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun component(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
            val value = bytes.copyOfRange(start, bytes.size)
            val prefixed = if (value[0].toInt() and 0x80 != 0) byteArrayOf(0) + value else value
            return byteArrayOf(0x02, prefixed.size.toByte()) + prefixed
        }
        val body = component(raw.copyOfRange(0, 32)) + component(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

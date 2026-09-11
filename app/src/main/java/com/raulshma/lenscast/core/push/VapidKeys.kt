package com.raulshma.lenscast.core.push

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Owns the VAPID identity for Web Push (RFC 8292): one persistent P-256 key
 * pair, generated once and reused for the installation's lifetime. The same
 * private key signs every RFC 8292 JWT (ES256) and its public key is the
 * `applicationServerKey` every browser subscription binds to — a rotated key
 * would silently orphan existing subscriptions (push services reject payloads
 * signed under an unregistered key), so the pair is load-once, like the TLS
 * identity in [com.raulshma.lenscast.core.TlsCertManager].
 *
 * Persistence mirrors the TLS cert manager's choice — an app-private file in
 * filesDir rather than the Android Keystore: Keystore-backed EC keys only
 * support ECDH ([javax.crypto.KeyAgreement] against a subscription's raw
 * p256dh public key) from API 31 (`PURPOSE_AGREE_KEY`), the app's minSdk is
 * 23, and a Keystore path would also leave the generation side untestable on
 * the JVM. The file is a small self-describing container: an 8-byte magic, a
 * 4-byte big-endian length, the PKCS8 private encoding, then the X.509 public
 * encoding (stored beside the private key rather than re-derived — pure JCA
 * offers no point multiplication over `java.security.spec` curves). The
 * protection boundary is the filesDir sandbox, exactly as the TLS keystore's
 * fixed-password comment states.
 *
 * [publicKeyUncompressed] is the 65-byte uncompressed point (0x04 ‖ X ‖ Y)
 * base64url-encoded for the wire — the exact encoding the browser's
 * `pushManager.subscribe({ applicationServerKey })` expects and the `k`
 * parameter of the VAPID Authorization header carries.
 */
class VapidKeys private constructor(private val keyPair: KeyPair) {

    fun privateKey(): PrivateKey = keyPair.private

    fun publicKey(): PublicKey = keyPair.public

    /** The pair as one value, for the RFC 8292 signer and the ECDH-free call sites. */
    fun keyPair(): KeyPair = keyPair

    /** The 65-byte uncompressed EC point (0x04 ‖ X ‖ Y) — the RFC 8292 `k` form. */
    fun publicKeyUncompressed(): ByteArray = uncompressedPoint(keyPair.public as ECPublicKey)

    /** The public key base64url-encoded (no padding) — the subscription's applicationServerKey. */
    fun publicKeyBase64Url(): String = PushEncryption.base64Url(publicKeyUncompressed())

    companion object {

        private const val TAG = "VapidKeys"

        /** The P-256 (secp256r1) curve — the only one RFC 8291/8292 allow. */
        private const val CURVE = "secp256r1"

        /** Uncompressed-point indicator byte preceding X‖Y in the 65-byte form. */
        private const val POINT_FORM_UNCOMPRESSED = 0x04

        private val FILE_MAGIC = "LCVAPID1".toByteArray(Charsets.US_ASCII)

        /** The app-private home: `filesDir/push/vapid.key`. */
        fun keyFile(context: Context): File = File(File(context.filesDir, "push"), "vapid.key")

        /** Loads the persisted pair, generating and persisting a fresh one when absent or unreadable. */
        fun loadOrCreate(file: File): VapidKeys {
            file.parentFile?.mkdirs()
            if (file.exists()) {
                runCatching { decode(file.readBytes()) }
                    .onSuccess { return it }
                    .onFailure { Log.w(TAG, "Stored VAPID key unreadable; regenerating key pair: ${it.message}") }
            }
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE), SecureRandom())
            val keyPair = generator.generateKeyPair()
            file.writeBytes(encode(keyPair))
            return VapidKeys(keyPair)
        }

        private fun encode(keyPair: KeyPair): ByteArray {
            val private = keyPair.private.encoded
            val public = keyPair.public.encoded
            return FILE_MAGIC +
                byteArrayOf(
                    (private.size ushr 24).toByte(),
                    (private.size ushr 16).toByte(),
                    (private.size ushr 8).toByte(),
                    private.size.toByte(),
                ) +
                private +
                public
        }

        private fun decode(bytes: ByteArray): VapidKeys {
            val factory = KeyFactory.getInstance("EC")
            var offset = FILE_MAGIC.size
            val privateLength = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(bytes.copyOfRange(offset, offset + privateLength)))
            offset += privateLength
            val public = factory.generatePublic(X509EncodedKeySpec(bytes.copyOfRange(offset, bytes.size)))
            return VapidKeys(KeyPair(public, private))
        }

        /**
         * The uncompressed-point encoding of an EC public key: `0x04` followed
         * by the fixed-width (32-byte) big-endian X and Y coordinates — not
         * the JDK's X.509 `encoded` form, which browsers and push services
         * reject. Kept internal-and-pure so the exact bytes stay JVM-tested.
         */
        internal fun uncompressedPoint(key: ECPublicKey): ByteArray {
            val point = key.w
            return byteArrayOf(POINT_FORM_UNCOMPRESSED.toByte()) +
                fixedWidth(point.affineX) +
                fixedWidth(point.affineY)
        }

        /** Left-zero-pads (or trims a sign byte from) a coordinate to 32 bytes. */
        private fun fixedWidth(coordinate: java.math.BigInteger): ByteArray {
            val bytes = coordinate.toByteArray()
            val source = if (bytes.size > P256_COORDINATE_BYTES) {
                bytes.copyOfRange(bytes.size - P256_COORDINATE_BYTES, bytes.size)
            } else {
                bytes
            }
            val out = ByteArray(P256_COORDINATE_BYTES)
            source.copyInto(out, P256_COORDINATE_BYTES - source.size)
            return out
        }

        private const val P256_COORDINATE_BYTES = 32
    }
}

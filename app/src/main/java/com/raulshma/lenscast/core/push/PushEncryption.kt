package com.raulshma.lenscast.core.push

import com.raulshma.lenscast.core.Base64Codec
import java.math.BigInteger
import java.net.URI
import java.security.KeyPair
// The platform image ships KeyAgreement under javax.crypto, not java.security.
import javax.crypto.KeyAgreement
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Web Push wire crypto, pure and JVM-tested:
 *
 * Payload encryption — RFC 8291 `aes128gcm` (message encryption) on top of
 * RFC 8188's binary content-coding header. One record, one ephemeral ECDH
 * P-256 key pair per message: the shared secret combines with the
 * subscription's 16-byte auth secret through HKDF-SHA256 exactly as the RFC
 * fixes it (the `WebPush: info` context binds receiver-public ‖ sender-public
 * in that order), deriving a 16-byte CEK and a 12-byte nonce; the plaintext is
 * framed with the 0x02 padding delimiter (the aes128gcm scheme's marker, at
 * least one byte, zero padding bytes after it) and sealed with AES-GCM and a
 * 128-bit tag. The header is salt(16) ‖ rs(4096, big-endian) ‖ idlen(65) ‖
 * keyid(ephemeral public point, 65 bytes) — RFC 8291 §5 requires the
 * ephemeral public key to ride the header's keyid parameter so the user agent
 * can re-derive the same secrets.
 *
 * VAPID — RFC 8292: a compact JWT, header `{"typ":"JWT","alg":"ES256"}`,
 * claims `aud` (the endpoint's origin), `exp` (now + 12 h), `sub` (a mailto:
 * contact), signed ES256 over `b64url(header) || "." || b64url(claims)`; the
 * JCA `SHA256withECDSA` DER signature is converted to the raw 64-byte r‖s form
 * the JOSE format requires, and the whole thing ships as the
 * `Authorization: vapid t=<jwt>, k=<uncompressed public key>` header value.
 *
 * Only javax.crypto + java.security — no new dependencies.
 */
object PushEncryption {

    /** The content-coding name; the wire format's only self-description. */
    const val CONTENT_ENCODING = "aes128gcm"

    /** RFC 8188: one record per message, so the record size covers the payload. */
    const val RECORD_SIZE = 4096

    /** The Authorization header's TTL window for the VAPID `exp` claim. */
    const val VAPID_TOKEN_TTL_MS = 12L * 60 * 60 * 1000

    private const val SALT_BYTES = 16
    private const val AUTH_SECRET_BYTES = 16
    private const val CEK_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val RS_BYTES = 4
    private const val IDLEN_BYTES = 1
    private const val PAD_DELIMITER = 0x02

    private const val INFO_IKM = "WebPush: info"
    private const val INFO_CEK = "Content-Encoding: aes128gcm"
    private const val INFO_NONCE = "Content-Encoding: nonce"

    /**
     * The receiver-side key material of one browser subscription, decoded from
     * its base64url wire form: the client's P-256 public point (65 bytes,
     * 0x04-prefixed) and the 16-byte auth secret.
     */
    data class ReceiverKey(val publicKey: ByteArray, val authSecret: ByteArray)

    /**
     * Decrypts a `p256dh`/`auth` pair off the subscription wire. Null when the
     * shapes are wrong — the browser sends the uncompressed point (65 bytes,
     * 0x04) and a 16-byte auth secret; anything else can never encrypt.
     */
    fun receiverKeyOrNull(p256dhBase64Url: String, authBase64Url: String): ReceiverKey? {
        val publicKey = Base64Codec.decodeUrlSafeOrNull(p256dhBase64Url) ?: return null
        val auth = Base64Codec.decodeUrlSafeOrNull(authBase64Url) ?: return null
        val valid = publicKey.size == P256_POINT_BYTES && publicKey[0].toInt() == 0x04 &&
            auth.size == AUTH_SECRET_BYTES
        return if (valid) ReceiverKey(publicKey, auth) else null
    }

    /**
     * Encrypts [payload] to [receiver] under [sender] (the sender's ephemeral
     * pair for this one message — the VAPID identity key MUST NOT be reused
     * here; callers generate a fresh pair per dispatch). Returns the complete
     * aes128gcm binary body (header + single ciphertext record).
     *
     * Throws only on platform crypto failures — the receiver shapes are
     * validated at subscription time ([receiverKeyOrNull]), so a malformed
     * key can never reach this path.
     */
    fun encrypt(
        payload: ByteArray,
        receiver: ReceiverKey,
        sender: KeyPair,
        salt: ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) },
    ): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val senderPublic = VapidKeys.uncompressedPoint(sender.public as ECPublicKey)
        val ecdhSecret = ecdhSharedSecret(sender.private, receiver.publicKey)

        // RFC 8291 §4.1: ikm = HKDF(salt=auth_secret, ikm=ecdh, info=WebPush: info\0 ua_pub as_pub)
        val ikm = hkdf(
            ikm = ecdhSecret,
            salt = receiver.authSecret,
            info = INFO_IKM.toByteArray() + byteArrayOf(0) + receiver.publicKey + senderPublic,
            length = CEK_BYTES * 2,
        )
        // RFC 8291 §4.2: both keys derive from the same ikm under the message salt.
        val cek = hkdf(ikm, salt, INFO_CEK.toByteArray() + byteArrayOf(0), CEK_BYTES)
        val nonce = hkdf(ikm, salt, INFO_NONCE.toByteArray() + byteArrayOf(0), NONCE_BYTES)

        // RFC 8291 §5: pad = del (0x02) || 0x00…; zero padding bytes for a
        // single-record payload that always fits under rs.
        val plaintext = payload + byteArrayOf(PAD_DELIMITER.toByte())
        check(plaintext.size <= RECORD_SIZE - GCM_TAG_BYTES) { "payload exceeds one aes128gcm record" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plaintext)

        return salt +
            rsBytes(RECORD_SIZE) +
            byteArrayOf(senderPublic.size.toByte()) +
            senderPublic +
            encrypted
    }

    /**
     * The decrypt twin of [encrypt] — a verification aid the JVM tests
     * round-trip through (the phone never decrypts push payloads in
     * production; only the subscribing browser holds the receiver side).
     * Re-derives the RFC 8291 ladder from the receiver's own key material —
     * an independent derivation of every intermediate value — and returns the
     * plaintext with the padding delimiter (and zero padding bytes) stripped.
     * Throws when the GCM tag fails (wrong key material, wrong auth secret,
     * or tampering) or the padding framing is malformed.
     */
    fun decrypt(
        body: ByteArray,
        receiverPublicPoint: ByteArray,
        receiverPrivate: PrivateKey,
        authSecret: ByteArray,
    ): ByteArray {
        require(receiverPublicPoint.size == P256_POINT_BYTES) { "receiver public point must be 65 bytes" }
        require(authSecret.size == AUTH_SECRET_BYTES) { "auth secret must be $AUTH_SECRET_BYTES bytes" }
        require(body.size > SALT_BYTES + RS_BYTES + IDLEN_BYTES) { "truncated aes128gcm body" }
        val salt = body.copyOfRange(0, SALT_BYTES)
        val rs = ((body[SALT_BYTES].toInt() and 0xFF) shl 24) or
            ((body[SALT_BYTES + 1].toInt() and 0xFF) shl 16) or
            ((body[SALT_BYTES + 2].toInt() and 0xFF) shl 8) or
            (body[SALT_BYTES + 3].toInt() and 0xFF)
        val idlen = body[SALT_BYTES + RS_BYTES].toInt() and 0xFF
        require(idlen > 0 && idlen % IDLEN_BYTES == 0) { "aes128gcm header carries no keyid" }
        val headerEnd = SALT_BYTES + RS_BYTES + IDLEN_BYTES + idlen
        require(body.size > headerEnd) { "aes128gcm body carries no record" }
        require(rs >= body.size - headerEnd) { "payload spans multiple records; this decrypt reads one" }

        val senderPublic = body.copyOfRange(SALT_BYTES + RS_BYTES + IDLEN_BYTES, headerEnd)
        val ecdhSecret = ecdhSharedSecret(receiverPrivate, senderPublic)
        val ikm = hkdf(
            ikm = ecdhSecret,
            salt = authSecret,
            info = INFO_IKM.toByteArray() + byteArrayOf(0) + receiverPublicPoint + senderPublic,
            length = CEK_BYTES * 2,
        )
        val cek = hkdf(ikm, salt, INFO_CEK.toByteArray() + byteArrayOf(0), CEK_BYTES)
        val nonce = hkdf(ikm, salt, INFO_NONCE.toByteArray() + byteArrayOf(0), NONCE_BYTES)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val padded = cipher.doFinal(body.copyOfRange(headerEnd, body.size))
        // Padding framing: 0x02 delimiter, then zero or more 0x00 bytes. The
        // last non-zero byte must be the delimiter or the payload is corrupt.
        var end = padded.size
        while (end > 0 && padded[end - 1].toInt() == 0) end--
        require(end > 0 && padded[end - 1].toInt() == PAD_DELIMITER) { "invalid padding delimiter" }
        return padded.copyOfRange(0, end - 1)
    }

    // ── VAPID (RFC 8292) ──

    /**
     * The `Authorization` header value for one POST to [endpoint]:
     * `vapid t=<JWT>, k=<base64url uncompressed public key>`. [nowMs] stamps
     * the `exp` claim ([VAPID_TOKEN_TTL_MS] ahead); [subject] is the mailto:
     * contact the RFC requires.
     */
    fun vapidAuthorization(
        endpoint: String,
        keyPair: KeyPair,
        subject: String,
        nowMs: Long,
    ): String {
        val claims = "{\"aud\":${jsonString(audienceOf(endpoint))}," +
            "\"exp\":${nowMs / 1000L + VAPID_TOKEN_TTL_MS / 1000L}," +
            "\"sub\":${jsonString(subject)}}"
        val header = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
        val signingInput = base64Url(header.toByteArray()) + "." + base64Url(claims.toByteArray())
        val signature = base64Url(es256Sign(keyPair.private, signingInput))
        val key = base64Url(VapidKeys.uncompressedPoint(keyPair.public as ECPublicKey))
        return "vapid t=$signingInput.$signature, k=$key"
    }

    /**
     * The JWT's `aud` claim — the endpoint's origin (scheme ‖ host ‖ port),
     * eliding the port when it is the scheme's default, per RFC 8292 §2.
     */
    fun audienceOf(endpoint: String): String {
        val uri = URI(endpoint)
        val defaultPort = when (uri.scheme?.lowercase(Locale.US)) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = if (uri.port == defaultPort || uri.port == -1) -1 else uri.port
        return buildString {
            append(uri.scheme?.lowercase(Locale.US) ?: "").append("://")
            append(uri.host?.lowercase(Locale.US) ?: "")
            if (port != -1) append(':').append(port)
        }
    }

    /** ES256 over [input]: JCA's DER signature converted to raw 64-byte r‖s. */
    fun es256Sign(privateKey: PrivateKey, input: String): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(input.toByteArray())
        return derToRaw(signer.sign())
    }

    /** The pure DER(SEQUENCE(r INTEGER, s INTEGER)) → 64-byte r‖s conversion, pinned by tests. */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        require(der[0].toInt() == 0x30) { "not a DER sequence" }
        fun readInteger(): ByteArray {
            require(der[i].toInt() == 0x02) { "expected DER integer" }
            val length = der[i + 1].toInt() and 0xFF
            val value = der.copyOfRange(i + 2, i + 2 + length)
            i += 2 + length
            // Strip the sign byte, left-pad to 32: each half must land exactly
            // on the 32-byte boundary or the token is not a valid ES256 sig.
            val trimmed = if (value.size > COMPONENT_BYTES && value[0].toInt() == 0) {
                value.copyOfRange(1, value.size)
            } else {
                value
            }
            val out = ByteArray(COMPONENT_BYTES)
            trimmed.copyInto(out, COMPONENT_BYTES - trimmed.size)
            return out
        }
        val r = readInteger()
        val s = readInteger()
        return r + s
    }

    // ── primitives ──

    private fun ecdhSharedSecret(privateKey: PrivateKey, receiverPublicPoint: ByteArray): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val public = keyFactory.generatePublic(ecPublicKeySpec(receiverPublicPoint))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(public, true)
        return agreement.generateSecret()
    }

    /**
     * Rebuilds the named-curve ECPublicKey from the wire's uncompressed point:
     * reads X and Y at their fixed offsets against the P-256 parameters the
     * subscription is defined on.
     */
    private fun ecPublicKeySpec(point: ByteArray): java.security.spec.ECPublicKeySpec {
        val x = BigInteger(1, point.copyOfRange(1, 1 + P256_COORDINATE_BYTES))
        val y = BigInteger(1, point.copyOfRange(1 + P256_COORDINATE_BYTES, 1 + 2 * P256_COORDINATE_BYTES))
        val parameterSpec = java.security.spec.ECGenParameterSpec("secp256r1")
        // AlgorithmParameters resolves the named curve into explicit ECParameterSpec.
        val parameters = java.security.AlgorithmParameters.getInstance("EC")
        parameters.init(parameterSpec)
        val params = parameters.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        return java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), params)
    }

    /** RFC 5869 HKDF (extract-then-expand) over HMAC-SHA256. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC(salt, ikm); an empty salt zero-pads to the hash length.
        hmac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)
        // Expand: T(1)‖T(2)… truncated to length.
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        var okm = ByteArray(0)
        var counter = 1
        while (okm.size < length) {
            hmac.update(t)
            hmac.update(info)
            hmac.update(counter.toByte())
            t = hmac.doFinal()
            okm += t
            counter++
        }
        return okm.copyOf(length)
    }

    /** base64url without padding — the JOSE encoding (RFC 7515). */
    fun base64Url(bytes: ByteArray): String = Base64Codec.encodeUrlSafe(bytes).trimEnd('=')

    private fun rsBytes(rs: Int): ByteArray = byteArrayOf(
        (rs ushr 24).toByte(),
        (rs ushr 16).toByte(),
        (rs ushr 8).toByte(),
        rs.toByte(),
    )

    /** A minimal JSON string literal — the claims builder's only escaping need. */
    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private const val P256_COORDINATE_BYTES = 32
    private const val P256_POINT_BYTES = 65
    private const val COMPONENT_BYTES = 32
    private const val HASH_BYTES = 32
    private const val GCM_TAG_BYTES = 16
}

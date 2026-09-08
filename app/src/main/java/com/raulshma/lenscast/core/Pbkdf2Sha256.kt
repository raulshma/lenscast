package com.raulshma.lenscast.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure PBKDF2-HMAC-SHA256 on top of javax.crypto.Mac ("HmacSHA256", API 11+).
 *
 * SecretKeyFactory's "PBKDF2WithHmacSHA256" algorithm is API 26+, so this is
 * the minSdk-23 path — and a single path on every API level, which also
 * removes any risk of provider-specific divergence between stored hashes and
 * verification. Output is byte-identical to PBEKeySpec-derived keys (the JVM
 * tests cross-check against the platform implementation, including non-ASCII
 * passwords, since both sides use UTF-8 password bytes).
 */
object Pbkdf2Sha256 {

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val BLOCK_BYTES = 32

    fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "keyLengthBytes must be positive" }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), HMAC_SHA256))

        val blocks = (keyLengthBytes + BLOCK_BYTES - 1) / BLOCK_BYTES
        val out = ByteArray(blocks * BLOCK_BYTES)
        val blockIndex = ByteArray(4)
        for (block in 1..blocks) {
            blockIndex[0] = (block ushr 24).toByte()
            blockIndex[1] = (block ushr 16).toByte()
            blockIndex[2] = (block ushr 8).toByte()
            blockIndex[3] = block.toByte()

            mac.update(salt)
            mac.update(blockIndex)
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * BLOCK_BYTES, BLOCK_BYTES)
        }
        return if (out.size == keyLengthBytes) out else out.copyOf(keyLengthBytes)
    }
}

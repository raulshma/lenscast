package com.raulshma.lenscast.core

import java.security.MessageDigest

/**
 * Single home for the stream-auth crypto primitives shared by the RTSP
 * server, the web server, and the stored auth settings. The Digest realm is
 * the one symbol both the HA1 producer (at settings-save time) and the HA1
 * consumer (challenge/verify in the RTSP server) reference — two literals
 * would drift silently and break Digest auth at runtime.
 */
object StreamAuthCrypto {
    const val RTSP_DIGEST_REALM = "LensCast RTSP"

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison that does not leak string length via timing.
     * Uses MessageDigest.isEqual on SHA-256 hashes so both operands are always
     * the same length regardless of input.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashA = digest.digest(a.toByteArray(Charsets.UTF_8))
        val hashB = digest.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(hashA, hashB)
    }
}

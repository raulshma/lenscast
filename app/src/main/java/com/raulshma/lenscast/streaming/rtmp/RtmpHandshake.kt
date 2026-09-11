package com.raulshma.lenscast.streaming.rtmp

import java.util.Random

/**
 * The RTMP handshake bytes, pure over [ByteArray] so the C1 build and the
 * S0/S1/C2 shapes are JVM-tested. This is the plain handshake — time + zero
 * ping payload + random, no digest: servers that require the FP9 digest
 * handshake are rare on the receive side, and the simple form is accepted by
 * nginx-rtmp, SRS, and the big CDNs. C2 is the S1 echo (the spec's "peer
 * echoes what it received"), which every plain-mode server accepts.
 */
object RtmpHandshake {

    /** RTMP protocol version on the wire (C0/S0). */
    const val RTMP_VERSION = 0x03

    /** S1/C1/C2 body size. */
    const val HANDSHAKE_SIZE = 1536

    /** The server's 4-byte time field this side writes into C1. */
    const val C1_TIME_MS = 0

    /**
     * C0 + C1: version byte, 4-byte time, 4 zero bytes (the plain handshake's
     * zero ping payload), then 1528 random bytes from [random].
     */
    fun c0c1(random: Random, timeMs: Int = C1_TIME_MS): ByteArray {
        val out = ByteArray(1 + HANDSHAKE_SIZE)
        out[0] = RTMP_VERSION.toByte()
        writeUint32(out, 1, timeMs)
        // bytes 5..8 stay zero; random fills the rest
        val body = ByteArray(HANDSHAKE_SIZE - 8)
        random.nextBytes(body)
        System.arraycopy(body, 0, out, 9, body.size)
        return out
    }

    /** True when the S0 byte is a plausible RTMP version. */
    fun isPlausibleS0(versionByte: Int): Boolean = versionByte in 0..0x7F

    /**
     * S1 extracted out of the server's first `1 + 1536` handshake bytes (the
     * caller reads them); null when [s0s1] is short or the S0 version is not
     * plausible.
     */
    fun s1From(s0s1: ByteArray): ByteArray? {
        if (s0s1.size < 1 + HANDSHAKE_SIZE) return null
        if (!isPlausibleS0(s0s1[0].toInt() and 0xFF)) return null
        return s0s1.copyOfRange(1, 1 + HANDSHAKE_SIZE)
    }

    /** The server's S1 time field (bytes 0..3), for diagnostics. */
    fun s1TimeMs(s1: ByteArray): Int? =
        if (s1.size >= 4) readUint32(s1, 0) else null

    /** C2 — the S1 echo. */
    fun c2FromS1(s1: ByteArray): ByteArray = s1.copyOf()

    private fun writeUint32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value shr 24) and 0xFF).toByte()
        out[offset + 1] = ((value shr 16) and 0xFF).toByte()
        out[offset + 2] = ((value shr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readUint32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

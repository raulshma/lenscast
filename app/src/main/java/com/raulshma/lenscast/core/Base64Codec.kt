package com.raulshma.lenscast.core

/**
 * Pure-Kotlin RFC 4648 Base64 — the byte-compatible stand-in for
 * java.util.Base64 (API 26+) that keeps every caller usable down to
 * minSdk 23 and JVM-testable without Robolectric.
 *
 * Strictness matters as much as the bytes: [decodeOrNull] rejects the same
 * inputs java.util.Base64's basic decoder throws on (non-alphabet characters,
 * misplaced or wrong-length padding, impossible lengths) — android.util.Base64
 * before API 26 silently *skipped* invalid characters instead, which is why
 * that class is not used here. Padding on input is optional, exactly like
 * java.util.Base64.
 */
object Base64Codec {

    private const val STANDARD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Standard-alphabet encode with padding — [java.util.Base64.getEncoder]. */
    fun encode(bytes: ByteArray): String = encodeWith(bytes, STANDARD_ALPHABET)

    /** URL-safe-alphabet encode with padding — [java.util.Base64.getUrlEncoder]. */
    fun encodeUrlSafe(bytes: ByteArray): String = encodeWith(bytes, URL_SAFE_ALPHABET)

    /**
     * Strict standard-alphabet decode — [java.util.Base64.getDecoder]
     * semantics; null where that decoder would throw.
     */
    fun decodeOrNull(value: String): ByteArray? = decodeWith(value, STANDARD_ALPHABET)

    /**
     * Strict URL-safe-alphabet decode — [java.util.Base64.getUrlDecoder]
     * semantics (padding optional); null where that decoder would throw.
     * The WebPush surfaces (VAPID JWT segments, subscription `p256dh`/`auth`)
     * are base64url per RFC 7515/8291, so their readers use this.
     */
    fun decodeUrlSafeOrNull(value: String): ByteArray? = decodeWith(value, URL_SAFE_ALPHABET)

    private fun decodeWith(value: String, alphabet: String): ByteArray? {
        var dataEnd = value.length
        while (dataEnd > 0 && value[dataEnd - 1] == '=') dataEnd--
        val padding = value.length - dataEnd

        return when {
            padding > 2 -> null
            // Padding, when present, must complete a 4-character quantum.
            padding > 0 && value.length % 4 != 0 -> null
            padding > 0 && dataEnd % 4 == 0 -> null
            padding > 0 && padding != 4 - dataEnd % 4 -> null
            // A dangling 6-bit unit cannot encode whole bytes.
            padding == 0 && dataEnd % 4 == 1 -> null
            else -> decodeData(value, dataEnd, alphabet)
        }
    }

    private fun encodeWith(bytes: ByteArray, alphabet: String): String {
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val word =
                ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[i + 2].toInt() and 0xFF)
            out.append(alphabet[word ushr 18 and 0x3F])
            out.append(alphabet[word ushr 12 and 0x3F])
            out.append(alphabet[word ushr 6 and 0x3F])
            out.append(alphabet[word and 0x3F])
            i += 3
        }
        val remaining = bytes.size - i
        if (remaining == 1) {
            val b = bytes[i].toInt() and 0xFF
            out.append(alphabet[b ushr 2])
            out.append(alphabet[(b and 0x3) shl 4])
            out.append("==")
        } else if (remaining == 2) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 and 0x3) shl 4) or (b1 ushr 4)])
            out.append(alphabet[(b1 and 0xF) shl 2])
            out.append('=')
        }
        return out.toString()
    }

    private fun decodeData(value: String, dataEnd: Int, alphabet: String): ByteArray? {
        val out = ByteArray((dataEnd / 4) * 3 + when (dataEnd % 4) {
            2 -> 1
            3 -> 2
            else -> 0
        })
        var outIndex = 0
        var word = 0
        var sextets = 0
        for (i in 0 until dataEnd) {
            val sextet = decodeChar(value[i], alphabet)
            if (sextet < 0) {
                // Covers '=' in the middle and every non-alphabet character,
                // including whitespace that lenient decoders would skip.
                return null
            }
            word = (word shl 6) or sextet
            sextets++
            if (sextets == 4) {
                out[outIndex++] = (word ushr 16).toByte()
                out[outIndex++] = (word ushr 8).toByte()
                out[outIndex++] = word.toByte()
                word = 0
                sextets = 0
            }
        }
        when (sextets) {
            2 -> out[outIndex] = (word ushr 4).toByte()
            3 -> {
                out[outIndex++] = (word ushr 10).toByte()
                out[outIndex] = (word ushr 2).toByte()
            }
        }
        return out
    }

    private fun decodeChar(c: Char, alphabet: String): Int {
        val index = when (c) {
            '=' -> -1
            else -> alphabet.indexOf(c)
        }
        return index
    }
}

package com.raulshma.lenscast.streaming.rtmp

import java.io.ByteArrayOutputStream

/**
 * One AMF0 value — the shapes the RTMP command layer actually exchanges.
 * Objects keep their entries as an ordered pair list (wire order matters to
 * some servers, and Kotlin maps would silently dedupe keys).
 */
sealed class AmfValue {

    data class Number(val value: Double) : AmfValue()

    data class Bool(val value: Boolean) : AmfValue()

    data class Str(val value: String) : AmfValue()

    data class Obj(val entries: List<Pair<String, AmfValue>>) : AmfValue() {
        // Last occurrence wins: decoding an AMF0 object is sequential property
        // assignment (ActionScript semantics), so a duplicate key's effective
        // value is the later entry — the list still preserves wire order.
        operator fun get(key: String): AmfValue? = entries.lastOrNull { it.first == key }?.second
    }

    data object Null : AmfValue()

    data object Undefined : AmfValue()
}

/** Thrown when a payload stops parsing mid-value — the caller surfaces it as a readable connect/publish error. */
class AmfDecodeException(message: String) : Exception(message)

/**
 * Pure AMF0 encoder/decoder over plain byte arrays — no socket, no Android,
 * JVM-tested. Encode covers exactly the value shapes [AmfValue] models;
 * decode additionally folds the marker types servers actually send back
 * (ECMA arrays read as objects, long strings as strings) so an _result with a
 * verbose body never breaks the reply path.
 */
object Amf0 {

    private const val MARKER_NUMBER = 0x00
    private const val MARKER_BOOLEAN = 0x01
    private const val MARKER_STRING = 0x02
    private const val MARKER_OBJECT = 0x03
    private const val MARKER_NULL = 0x05
    private const val MARKER_UNDEFINED = 0x06
    private const val MARKER_ECMA_ARRAY = 0x08
    private const val MARKER_LONG_STRING = 0x0C
    private const val MARKER_OBJECT_END = 0x09

    /** The whole command payload: every value back to back. */
    fun encode(values: List<AmfValue>): ByteArray {
        val out = ByteArrayOutputStream(64)
        for (value in values) {
            encodeValue(out, value)
        }
        return out.toByteArray()
    }

    private fun encodeValue(out: ByteArrayOutputStream, value: AmfValue) {
        when (value) {
            is AmfValue.Number -> {
                out.write(MARKER_NUMBER)
                val bits = java.lang.Double.doubleToLongBits(value.value)
                writeUint32(out, bits ushr 32)
                writeUint32(out, bits and 0xFFFFFFFFL)
            }
            is AmfValue.Bool -> {
                out.write(MARKER_BOOLEAN)
                out.write(if (value.value) 1 else 0)
            }
            is AmfValue.Str -> encodeString(out, value.value)
            is AmfValue.Obj -> {
                out.write(MARKER_OBJECT)
                for ((key, entry) in value.entries) {
                    encodeStringBody(out, key)
                    encodeValue(out, entry)
                }
                out.write(0)
                out.write(0)
                out.write(MARKER_OBJECT_END)
            }
            AmfValue.Null -> out.write(MARKER_NULL)
            AmfValue.Undefined -> out.write(MARKER_UNDEFINED)
        }
    }

    private fun encodeString(out: ByteArrayOutputStream, value: String) {
        out.write(MARKER_STRING)
        encodeStringBody(out, value)
    }

    private fun encodeStringBody(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "AMF0 short string exceeds 65535 bytes" }
        writeUint16(out, bytes.size)
        out.write(bytes)
    }

    private fun writeUint16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeUint32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    /** Decodes every value in the payload; throws [AmfDecodeException] on truncation or an unsupported marker. */
    fun decode(bytes: ByteArray): List<AmfValue> {
        val values = mutableListOf<AmfValue>()
        var cursor = 0
        while (cursor < bytes.size) {
            val decoded = decodeValue(bytes, cursor)
            values.add(decoded.value)
            cursor = decoded.next
        }
        return values
    }

    private data class Decoded(val value: AmfValue, val next: Int)

    private fun decodeValue(bytes: ByteArray, start: Int): Decoded {
        requireRemaining(bytes, start, 1)
        val marker = bytes[start].toInt() and 0xFF
        var cursor = start + 1
        return when (marker) {
            MARKER_NUMBER -> {
                requireRemaining(bytes, cursor, 8)
                // Each half is an unsigned u32; the low word must be zero-extended
                // or a mantissa with its top bit set (e.g. 0.1) sign-extends into
                // an exponent-of-ones NaN.
                val bits = (readUint32(bytes, cursor).toLong() shl 32) or
                    (readUint32(bytes, cursor + 4).toLong() and 0xFFFFFFFFL)
                Decoded(AmfValue.Number(java.lang.Double.longBitsToDouble(bits)), cursor + 8)
            }
            MARKER_BOOLEAN -> {
                requireRemaining(bytes, cursor, 1)
                Decoded(AmfValue.Bool(bytes[cursor].toInt() != 0), cursor + 1)
            }
            MARKER_STRING -> {
                val (value, next) = decodeShortString(bytes, cursor)
                Decoded(AmfValue.Str(value), next)
            }
            MARKER_LONG_STRING -> {
                requireRemaining(bytes, cursor, 4)
                val length = readUint32(bytes, cursor)
                cursor += 4
                requireRemaining(bytes, cursor, length)
                Decoded(AmfValue.Str(String(bytes, cursor, length, Charsets.UTF_8)), cursor + length)
            }
            MARKER_OBJECT -> decodeObject(bytes, cursor)
            MARKER_ECMA_ARRAY -> {
                // u32 count, then object entries until the end marker — read as Obj.
                requireRemaining(bytes, cursor, 4)
                decodeObject(bytes, cursor + 4)
            }
            MARKER_NULL -> Decoded(AmfValue.Null, cursor)
            MARKER_UNDEFINED -> Decoded(AmfValue.Undefined, cursor)
            else -> throw AmfDecodeException("Unsupported AMF0 marker 0x%02x at offset %d".format(marker, start))
        }
    }

    private fun decodeObject(bytes: ByteArray, start: Int): Decoded {
        val entries = mutableListOf<Pair<String, AmfValue>>()
        var cursor = start
        while (true) {
            // The end marker is the full three-byte sequence 00 00 09 — an empty
            // key length followed by the object-end marker. A lone 0x00 byte is
            // NOT evidence of it: every short key (< 256 bytes) and every number
            // marker also start with 0x00.
            requireRemaining(bytes, cursor, 3)
            if (bytes[cursor].toInt() == 0 &&
                bytes[cursor + 1].toInt() == 0 &&
                bytes[cursor + 2].toInt() == MARKER_OBJECT_END
            ) {
                return Decoded(AmfValue.Obj(entries), cursor + 3)
            }
            val (key, afterKey) = decodeShortString(bytes, cursor)
            val decoded = decodeValue(bytes, afterKey)
            entries.add(key to decoded.value)
            cursor = decoded.next
        }
    }

    private fun decodeShortString(bytes: ByteArray, start: Int): Pair<String, Int> {
        requireRemaining(bytes, start, 2)
        val length = readUint16(bytes, start)
        val cursor = start + 2
        requireRemaining(bytes, cursor, length)
        return String(bytes, cursor, length, Charsets.UTF_8) to cursor + length
    }

    private fun readUint16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUint32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun requireRemaining(bytes: ByteArray, offset: Int, count: Int) {
        // count < 0 covers a u32 length that overflowed Int (a hostile
        // long-string/ecma count) — a decode error, not an index bomb.
        if (count < 0 || offset + count > bytes.size) {
            throw AmfDecodeException("Truncated AMF0 payload: need $count bytes at offset $offset of ${bytes.size}")
        }
    }
}

/** One decoded RTMP command message: `name`, transaction id, then the arguments. */
data class RtmpCommand(
    val name: String,
    val transactionId: Double,
    val args: List<AmfValue>,
) {
    companion object {
        /** Decodes a type-20 (AMF0 command) payload; null when the shape is not command-like. */
        fun parse(payload: ByteArray): RtmpCommand? = try {
            val values = Amf0.decode(payload)
            val name = values.getOrNull(0) as? AmfValue.Str ?: return null
            val txn = values.getOrNull(1) as? AmfValue.Number ?: return null
            RtmpCommand(name.value, txn.value, values.drop(2))
        } catch (_: AmfDecodeException) {
            null
        }
    }
}

/**
 * The command payloads the publisher sends, in one pure home so the wire shape
 * is byte-pinned by test: `connect` (app + tcUrl + the classic client
 * capabilities, plus URL-userinfo credentials when present), `releaseStream`,
 * `createStream`, `publish` ("live"), and the clean-close pair `FCUnpublish` /
 * `deleteStream`.
 */
object RtmpCommands {

    fun connect(url: RtmpUrl): ByteArray {
        val entries = mutableListOf<Pair<String, AmfValue>>(
            "app" to AmfValue.Str(url.app),
            "type" to AmfValue.Str("nonprivate"),
            "flashVer" to AmfValue.Str(FLASH_VER),
            "tcUrl" to AmfValue.Str(url.tcUrl),
            "fpad" to AmfValue.Bool(false),
            "capabilities" to AmfValue.Number(239.0),
            "audioCodecs" to AmfValue.Number(3575.0),
            "videoCodecs" to AmfValue.Number(252.0),
            "videoFunction" to AmfValue.Number(1.0),
        )
        if (url.username != null || url.password != null) {
            url.username?.let { entries.add("username" to AmfValue.Str(it)) }
            url.password?.let { entries.add("password" to AmfValue.Str(it)) }
        }
        return Amf0.encode(
            listOf(
                AmfValue.Str("connect"),
                AmfValue.Number(1.0),
                AmfValue.Obj(entries),
            )
        )
    }

    fun releaseStream(txn: Double, streamKey: String): ByteArray = Amf0.encode(
        listOf(
            AmfValue.Str("releaseStream"),
            AmfValue.Number(txn),
            AmfValue.Null,
            AmfValue.Str(streamKey),
        )
    )

    fun createStream(txn: Double): ByteArray = Amf0.encode(
        listOf(
            AmfValue.Str("createStream"),
            AmfValue.Number(txn),
            AmfValue.Null,
        )
    )

    fun publish(txn: Double, streamKey: String): ByteArray = Amf0.encode(
        listOf(
            AmfValue.Str("publish"),
            AmfValue.Number(txn),
            AmfValue.Null,
            AmfValue.Str(streamKey),
            AmfValue.Str("live"),
        )
    )

    fun fcUnpublish(streamKey: String): ByteArray = Amf0.encode(
        listOf(
            AmfValue.Str("FCUnpublish"),
            AmfValue.Number(0.0),
            AmfValue.Null,
            AmfValue.Str(streamKey),
        )
    )

    fun deleteStream(streamId: Double): ByteArray = Amf0.encode(
        listOf(
            AmfValue.Str("deleteStream"),
            AmfValue.Number(0.0),
            AmfValue.Null,
            AmfValue.Number(streamId),
        )
    )

    /** The human-readable error inside a `_error` or error-level `onStatus` reply, however verbose the server got. */
    fun errorMessage(args: List<AmfValue>): String {
        for (arg in args) {
            when (arg) {
                is AmfValue.Str -> if (arg.value.isNotBlank()) return arg.value
                is AmfValue.Obj -> {
                    val code = (arg["code"] as? AmfValue.Str)?.value
                    val description = (arg["description"] as? AmfValue.Str)?.value
                    val joined = listOfNotNull(code, description?.takeIf { it.isNotBlank() }).joinToString(" — ")
                    if (joined.isNotEmpty()) return joined
                }
                else -> Unit
            }
        }
        return "server rejected the command"
    }

    const val FLASH_VER = "FMLE/3.0 (compatible; LensCast)"
}

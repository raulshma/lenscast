package com.raulshma.lenscast.streaming.srt

/**
 * The SRT wire-format core: packet headers, the caller handshake
 * (induction + conclusion), and the control packets the publisher side
 * exchanges (keepalive, shutdown) or receives (full ACK, NAK). Pure over
 * byte arrays, big-endian throughout, per the SRT specification
 * (draft-sharabayko-srt / RFC draft form); every build/parse pair is pinned
 * by JVM tests including known-good recorded byte patterns and fuzz.
 *
 * Layouts implemented here:
 *  - every packet opens with a 16-byte SRT header: bit 15 of the first word
 *    selects control (1) vs data (0); control packets carry
 *    [SrtControlHeader] with type, subtype, type-specific info, timestamp
 *    (µs) and destination socket id; data packets carry sequence number,
 *    message position/order/crypto flags, message number, timestamp, dst.
 *  - the handshake body is the 52-byte UDT structure: version, encryption +
 *    extension fields, initial sequence number, MTU, flow window, handshake
 *    type (1 = induction/WAVEAHAND, 2 = conclusion), socket id, syn cookie,
 *    16-byte peer address. SRT extension blocks (HSREQ/HSRESP) trail the
 *    body on the conclusion handshakes of version-4 peers.
 *  - MPEG-TS rides data packets whose payload is a multiple of 188 bytes,
 *    conventionally 7 TS packets (1316) per UDP datagram.
 */
object SrtPacket {

    // ── header words ──

    const val FLAG_CONTROL = 0x8000
    const val FLAG_DATA = 0x0000

    /** SRT control packet types. */
    const val TYPE_HANDSHAKE = 0
    const val TYPE_KEEPALIVE = 1
    const val TYPE_ACK = 2
    const val TYPE_NAK = 3
    const val TYPE_SHUTDOWN = 5

    const val HEADER_BYTES = 16

    /** Handshake types: induction (WAVEAHAND) then conclusion. */
    const val HS_INDUCTION = 1
    const val HS_CONCLUSION = 2

    /** SRT handshake extension block types. */
    const val EXT_HSREQ = 1
    const val EXT_HSRESP = 2

    /** Extension Field bit announcing "the trailing blocks carry HSREQ/HSRESP". */
    const val HS_EXT_HSREQ = 0x8000

    /** The SRT magic the induction response's Extension Field must carry (version-5 peers). */
    const val HS_MAGIC = 0x4A17

    /** Handshake Version field values: the caller induces at UDT 4, both conclude at SRT 5. */
    const val HS_VERSION_UDT = 4
    const val HS_VERSION_SRT = 5

    /** The SRT library version carried in the HSREQ block (1.4.3, major*0x10000 + minor*0x100 + patch). */
    const val SRT_VERSION = 0x010403

    /**
     * The SRT config flags this publisher advertises (low bits, Table 6 of
     * the SRT draft): TSBPDSND (we stamp valid µs timestamps) and CRYPT
     * (mandatory legacy flag — we understand the data packet's KK field;
     * the stream itself is unencrypted, kk = 0).
     */
    const val SRT_FLAG_TSBPDSND = 0x00000001
    const val SRT_FLAG_CRYPT = 0x00000004
    const val SRT_ADVERTISED_FLAGS = SRT_FLAG_TSBPDSND or SRT_FLAG_CRYPT

    /** The latency advertised in HSREQ/HSRESP, in milliseconds (both delay halves). */
    const val LATENCY_MS = 120

    /** The datagram payload size the muxer aligns to (7 MPEG-TS packets). */
    const val DATA_PAYLOAD_BYTES = 7 * 188

    /** A data packet: header + payload, capped at the 1500-byte MTU the handshake declares. */
    const val MAX_DATAGRAM_BYTES = HEADER_BYTES + DATA_PAYLOAD_BYTES

    // ── builders ──

    /**
     * The caller's induction request, exactly as the SRT draft's §4.3.1.1
     * pins it: Version 4, Extension Field 2, dst socket id 0, zero cookie.
     * Randomness (ISN, socket id) arrives from the caller so tests can pin
     * the bytes.
     */
    fun inductionRequest(
        timestampUs: Long,
        socketId: Int,
        initialSequence: Int,
    ): ByteArray =
        handshakePacket(
            timestampUs = timestampUs,
            dstSocketId = 0,
            version = HS_VERSION_UDT,
            extensionField = 2,
            initialSequence = initialSequence,
            handshakeType = HS_INDUCTION,
            socketId = socketId,
            cookie = 0,
            extensions = emptyList(),
        )

    /**
     * The caller's conclusion request: Version 5, the listener's cookie, the
     * listener's socket id as destination, and the HSREQ extension block
     * announcing this side's version/flags/latency.
     */
    fun conclusionRequest(
        timestampUs: Long,
        socketId: Int,
        peerSocketId: Int,
        cookie: Int,
        initialSequence: Int,
    ): ByteArray =
        handshakePacket(
            timestampUs = timestampUs,
            dstSocketId = peerSocketId,
            version = HS_VERSION_SRT,
            extensionField = HS_EXT_HSREQ,
            initialSequence = initialSequence,
            handshakeType = HS_CONCLUSION,
            socketId = socketId,
            cookie = cookie,
            extensions = listOf(hsReqBlock()),
        )

    /**
     * The HSREQ extension block: type 1, four 32-bit words — SRT version,
     * config flags, then the receiver and sender TSBPD delays as two u16s in
     * milliseconds (both halves the same value).
     */
    private fun hsReqBlock(): ByteArray {
        val block = ByteArray(20)
        putU16(block, 0, EXT_HSREQ)
        putU16(block, 2, 4) // four 32-bit words follow
        putU32(block, 4, SRT_VERSION)
        putU32(block, 8, SRT_ADVERTISED_FLAGS)
        putU16(block, 12, LATENCY_MS)
        putU16(block, 14, LATENCY_MS)
        return block
    }

    private fun handshakePacket(
        timestampUs: Long,
        dstSocketId: Int,
        version: Int,
        extensionField: Int,
        initialSequence: Int,
        handshakeType: Int,
        socketId: Int,
        cookie: Int,
        extensions: List<ByteArray>,
    ): ByteArray {
        val body = ByteArray(52)
        putU32(body, 0, version)
        putU16(body, 4, 0) // encryption field: no encryption
        putU16(body, 6, extensionField)
        putU32(body, 8, initialSequence)
        putU32(body, 12, 1500) // MTU
        putU32(body, 16, 8192) // max flow window size
        putU32(body, 20, handshakeType)
        putU32(body, 24, socketId)
        putU32(body, 28, cookie)
        // Peer IP: 16 zero bytes (unspecified) — listeners bind by address,
        // not by what the caller guesses.
        val payload = body + extensions.fold(ByteArray(0)) { acc, block -> acc + block }
        return controlPacket(TYPE_HANDSHAKE, 0, 0, timestampUs, dstSocketId, payload)
    }

    /** A 16-byte keepalive control packet (header only). */
    fun keepalive(timestampUs: Long, dstSocketId: Int): ByteArray =
        controlPacket(TYPE_KEEPALIVE, 0, 0, timestampUs, dstSocketId, ByteArray(0))

    /** A 16-byte shutdown control packet — the clean-close notice on stop. */
    fun shutdown(timestampUs: Long, dstSocketId: Int): ByteArray =
        controlPacket(TYPE_SHUTDOWN, 0, 0, timestampUs, dstSocketId, ByteArray(0))

    /**
     * One data packet header + payload: MPEG-TS bytes as a single, ordered,
     * unencrypted message. Sequence numbers are u31 and wrap naturally.
     */
    fun dataPacket(
        sequence: Int,
        messageNumber: Int,
        timestampUs: Long,
        dstSocketId: Int,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size in 1..DATA_PAYLOAD_BYTES) {
            "SRT data payload must be 1..$DATA_PAYLOAD_BYTES bytes (got ${payload.size})"
        }
        val out = ByteArray(HEADER_BYTES + payload.size)
        putU32(out, 0, sequence and 0x7FFFFFFF)
        // FF=10 (single), O=1 (ordered), kk=00 (unencrypted), R=0 (not a rexmit).
        out[4] = (0b10 shl 6 or (1 shl 5) or (messageNumber shr 24 and 0b11)).toByte()
        putU24(out, 5, messageNumber)
        putU32(out, 8, timestampUs.toInt())
        putU32(out, 12, dstSocketId)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /** The generic 16-byte control header + payload frame. */
    fun controlPacket(
        type: Int,
        subtype: Int,
        typeSpecific: Int,
        timestampUs: Long,
        dstSocketId: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        putU16(out, 0, FLAG_CONTROL or (type and 0x7FFF))
        putU16(out, 2, subtype)
        putU32(out, 4, typeSpecific)
        putU32(out, 8, timestampUs.toInt())
        putU32(out, 12, dstSocketId)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    // ── parsers ──

    /**
     * The inbound packet classification: [Incoming.Control] (handshake
     * responses, ACKs, NAKs, keepalives) or [Incoming.Data] (ignored by a
     * publisher, parsed defensively so a malformed frame never wedges the
     * read loop); null when the bytes are too short to even carry the
     * header.
     */
    sealed interface Incoming {
        data class Control(
            val type: Int,
            val subtype: Int,
            val typeSpecific: Int,
            val timestampUs: Long,
            val dstSocketId: Int,
            val payload: ByteArray,
        ) : Incoming

        data class Data(val sequence: Int, val timestampUs: Long) : Incoming
    }

    fun parseIncoming(bytes: ByteArray, length: Int = bytes.size): Incoming? {
        if (length < HEADER_BYTES) return null
        val first = u16(bytes, 0)
        val timestamp = u32(bytes, 8).toLong() and 0xFFFFFFFFL
        val dst = u32(bytes, 12)
        return if (first and 0x8000 != 0) {
            Incoming.Control(
                type = first and 0x7FFF,
                subtype = u16(bytes, 2),
                typeSpecific = u32(bytes, 4),
                timestampUs = timestamp,
                dstSocketId = dst,
                payload = bytes.copyOfRange(HEADER_BYTES, length),
            )
        } else {
            Incoming.Data(sequence = u32(bytes, 0), timestampUs = timestamp)
        }
    }

    /**
     * The induction response's answer, parsed: the listener's cookie (the
     * conclusion must echo it) and socket id (every later packet's
     * destination). Null when this is not a version-4 handshake with an
     * induction/conclusion shape we can use.
     */
    /**
     * The handshake struct's fields this publisher consumes, parsed
     * tolerantly across the two versions it must handle: the induction
     * response arrives at Version 5 (with the [HS_MAGIC] in the Extension
     * Field) while a legacy peer may answer at Version 4 — the cookie and
     * socket id parse the same either way. Null for anything else.
     */
    fun handshakeFrom(payload: ByteArray): Handshake? {
        if (payload.size < 52) return null
        val version = u32(payload, 0)
        if (version != HS_VERSION_UDT && version != HS_VERSION_SRT) return null
        return Handshake(
            version = version,
            extensionField = u16(payload, 6),
            initialSequence = u32(payload, 8),
            handshakeType = u32(payload, 20),
            socketId = u32(payload, 24),
            cookie = u32(payload, 28),
        )
    }

    /** The parsed 52-byte handshake struct's fields this publisher consumes. */
    data class Handshake(
        val version: Int,
        val extensionField: Int,
        val initialSequence: Int,
        val handshakeType: Int,
        val socketId: Int,
        val cookie: Int,
    )

    /**
     * The HSRESP extension block from a conclusion response: the listener's
     * SRT version, config flags, and the two TSBPD delay halves (ms) —
     * parsed as stats context. Null when the peer answered conclusion
     * without extensions.
     */
    fun hsRespFrom(payload: ByteArray): PeerConfig? {
        var offset = 52
        while (offset + 4 <= payload.size) {
            val type = u16(payload, offset)
            val words = u16(payload, offset + 2)
            if (words <= 0 || offset + 4 + words * 4 > payload.size) return null
            if (type == EXT_HSRESP && words == 4) {
                return PeerConfig(
                    version = u32(payload, offset + 4),
                    flags = u32(payload, offset + 8),
                    latencyMs = u16(payload, offset + 12),
                )
            }
            offset += 4 + words * 4
        }
        return null
    }

    /** The listener's SRT configuration, as the conclusion response announced it. */
    data class PeerConfig(val version: Int, val flags: Int, val latencyMs: Int)

    /**
     * The full ACK's numbers: the last contiguously-received data sequence
     * (u31) and the measured round-trip time in microseconds. Null for a
     * light ACK or a truncated body.
     */
    fun ackFrom(payload: ByteArray): Ack? {
        if (payload.size < 12) return null
        val lastAck = u32(payload, 0) and 0x7FFFFFFF
        val rttUs = u32(payload, 4)
        val rttVarUs = u32(payload, 8)
        return Ack(lastAckSequence = lastAck, rttUs = rttUs, rttVarUs = rttVarUs)
    }

    data class Ack(val lastAckSequence: Int, val rttUs: Int, val rttVarUs: Int)

    /**
     * The NAK's loss list: single sequence numbers and first..last ranges,
     * as u31 pairs. Bounded at [MAX_NAK_SEQUENCES] so a hostile frame cannot
     * balloon the parse; an oversized list degrades to the first entries.
     */
    fun nakFrom(payload: ByteArray): List<IntRange> {
        val words = payload.size / 4
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < words && ranges.size < MAX_NAK_RANGES) {
            val first = u32(payload, index * 4)
            if (first and 0x80000000.toInt() != 0 && index + 1 < words) {
                // Range: (first | 0x80000000), last — both inclusive, u31.
                val rangeStart = first and 0x7FFFFFFF
                val rangeEnd = u32(payload, (index + 1) * 4) and 0x7FFFFFFF
                if (rangeStart <= rangeEnd) {
                    val cappedEnd = minOf(rangeEnd, rangeStart + MAX_NAK_RANGE_SPAN - 1)
                    ranges.add(rangeStart..cappedEnd)
                }
                index += 2
            } else {
                val single = first and 0x7FFFFFFF
                ranges.add(single..single)
                index += 1
            }
        }
        return ranges
    }

    const val MAX_NAK_RANGES = 256
    const val MAX_NAK_RANGE_SPAN = 8192

    // ── little big-endian helpers (internal: tests exercise the packet API) ──

    internal fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 8 and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    internal fun putU24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 16 and 0xFF).toByte()
        target[offset + 1] = (value shr 8 and 0xFF).toByte()
        target[offset + 2] = (value and 0xFF).toByte()
    }

    internal fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 24 and 0xFF).toByte()
        target[offset + 1] = (value shr 16 and 0xFF).toByte()
        target[offset + 2] = (value shr 8 and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    internal fun u16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) shl 8 or (source[offset + 1].toInt() and 0xFF)

    internal fun u24(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) shl 16 or
            (source[offset + 1].toInt() and 0xFF) shl 8 or
            (source[offset + 2].toInt() and 0xFF)

    internal fun u32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) shl 24 or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}

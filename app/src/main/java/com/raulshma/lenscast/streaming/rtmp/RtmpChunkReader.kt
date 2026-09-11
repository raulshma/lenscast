package com.raulshma.lenscast.streaming.rtmp

/**
 * The RTMP chunk reader — the read half of the chunk protocol, pure over fed
 * byte arrays so de/reassembly (basic-header formats, extended timestamps,
 * interleaved chunk streams, a chunk straddling two feeds) is JVM-tested
 * without a socket. The publisher feeds whatever it read; complete messages
 * surface on [onMessage].
 *
 * The incoming chunk size starts at 128 and moves when the publisher observes
 * the server's Set Chunk Size control message (it hands the value to
 * [setChunkSize]).
 *
 * At most ONE chunk can straddle feeds: parsing stops when the buffer cannot
 * satisfy the chunk's payload, and the open chunk is the single resume point —
 * chunks interleave only at chunk granularity, so no second header can appear
 * behind an unfinished chunk.
 */
class RtmpChunkReader(
    private val onMessage: (typeId: Int, streamId: Int, timestampMs: Int, payload: ByteArray) -> Unit,
) {

    private class MessageState {
        var timestamp = 0
        var delta = -1
        var length = 0
        var type = 0
        var streamId = 0
        var extended = false
        var payload = ByteArray(0)
        var payloadSoFar = 0
    }

    /** The peer's current chunk size. */
    var chunkSize: Int = RtmpChunkProtocol.DEFAULT_CHUNK_SIZE
        private set

    private val states = HashMap<Int, MessageState>()

    // The chunk whose payload is only partially buffered (null when none).
    private var openState: MessageState? = null
    private var openRemaining = 0

    // The pending read buffer: fed bytes accumulate here, parsing consumes
    // from the front, and the remainder compacts between feeds.
    private var buffer = ByteArray(INITIAL_BUFFER)
    private var bufferLen = 0

    fun setChunkSize(size: Int) {
        require(size in 1..RtmpChunkProtocol.MAX_CHUNK_SIZE) { "Chunk size out of range: $size" }
        chunkSize = size
    }

    fun feed(data: ByteArray, offset: Int, length: Int) {
        append(data, offset, length)
        var pos = 0
        while (pos < bufferLen) {
            openState?.let { state ->
                if (bufferLen - pos < openRemaining) break
                appendPayload(state, buffer, pos, openRemaining)
                pos += openRemaining
                openState = null
                openRemaining = 0
                if (state.payloadSoFar >= state.length) emit(state)
                continue
            }
            val header = parseHeader(pos) ?: break
            pos = header.next
            val state = header.state
            if (state.length == 0) {
                emit(state)
                continue
            }
            val need = minOf(chunkSize, state.length - state.payloadSoFar)
            if (bufferLen - pos < need) {
                openState = state
                openRemaining = need
                break
            }
            appendPayload(state, buffer, pos, need)
            pos += need
            if (state.payloadSoFar >= state.length) emit(state)
        }
        compact(pos)
    }

    private data class Header(val state: MessageState, val next: Int)

    /** Parses one chunk header at [pos]; null when more bytes are needed. */
    private fun parseHeader(pos: Int): Header? {
        if (bufferLen < pos + 1) return null
        val first = buffer[pos].toInt() and 0xFF
        val fmt = (first shr 6) and 0x03
        val rawCsid = first and 0x3F
        var cursor = pos + 1
        val csid: Int
        when (rawCsid) {
            0 -> {
                if (bufferLen < cursor + 1) return null
                csid = 64 + (buffer[cursor].toInt() and 0xFF)
                cursor += 1
            }
            1 -> {
                if (bufferLen < cursor + 2) return null
                csid = 64 + ((buffer[cursor].toInt() and 0xFF) or ((buffer[cursor + 1].toInt() and 0xFF) shl 8))
                cursor += 2
            }
            else -> csid = rawCsid
        }
        val state = if (states.containsKey(csid)) {
            states.getValue(csid)
        } else {
            // Hostile-input bound: a peer minting message state per fresh csid
            // (two header bytes each) gets dropped instead of growing the map.
            if (states.size >= RtmpChunkProtocol.MAX_CHUNK_STREAMS) {
                throw RtmpChunkProtocolException(
                    "Chunk stream $csid exceeds the ${RtmpChunkProtocol.MAX_CHUNK_STREAMS}-stream cap"
                )
            }
            states.getOrPut(csid) { MessageState() }
        }

        when (fmt) {
            0 -> {
                if (bufferLen < cursor + 11) return null
                var ts = readUint24(cursor)
                val length = readUint24(cursor + 3)
                val type = buffer[cursor + 6].toInt() and 0xFF
                val streamId = (buffer[cursor + 7].toInt() and 0xFF) or
                    ((buffer[cursor + 8].toInt() and 0xFF) shl 8) or
                    ((buffer[cursor + 9].toInt() and 0xFF) shl 16) or
                    ((buffer[cursor + 10].toInt() and 0xFF) shl 24)
                cursor += 11
                if (ts == RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL) {
                    if (bufferLen < cursor + 4) return null
                    ts = readUint32(cursor)
                    cursor += 4
                    state.extended = true
                } else {
                    state.extended = false
                }
                state.timestamp = ts
                state.delta = -1
                requireMessageLength(length)
                state.length = length
                state.type = type
                state.streamId = streamId
                state.payloadSoFar = 0
                // Payload memory is claimed as bytes actually arrive
                // ([appendPayload] grows geometrically), never up front — a
                // hostile header cannot mint a 16 MB allocation with 12 bytes.
                state.payload = ByteArray(0)
            }
            1 -> {
                if (bufferLen < cursor + 7) return null
                var delta = readUint24(cursor)
                val length = readUint24(cursor + 3)
                val type = buffer[cursor + 6].toInt() and 0xFF
                cursor += 7
                if (delta == RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL) {
                    if (bufferLen < cursor + 4) return null
                    delta = readUint32(cursor)
                    cursor += 4
                    state.extended = true
                } else {
                    state.extended = false
                }
                state.timestamp += delta
                state.delta = delta
                requireMessageLength(length)
                state.length = length
                state.type = type
                state.payloadSoFar = 0
                state.payload = ByteArray(0)
            }
            2 -> {
                if (bufferLen < cursor + 3) return null
                var delta = readUint24(cursor)
                cursor += 3
                if (delta == RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL) {
                    if (bufferLen < cursor + 4) return null
                    delta = readUint32(cursor)
                    cursor += 4
                    state.extended = true
                } else {
                    state.extended = false
                }
                state.timestamp += delta
                state.delta = delta
                state.payloadSoFar = 0
                state.payload = ByteArray(0)
            }
            3 -> {
                // A fmt3 chunk with no preceding header for its csid is a
                // protocol violation; treat the message as empty-length so the
                // stream cannot wedge (the payload bytes would be
                // misinterpreted otherwise — better to fail loud upstream).
                if (state.length < 0) return null
                // A fmt3 chunk is either the CONTINUATION of the csid's
                // partially-received message (accumulate into it) or a NEW
                // message with the same size/type/delta (fresh accumulator).
                // Only the new-message case resets the accumulator — resetting
                // on a continuation would discard every earlier chunk.
                val continuing = state.payloadSoFar in 1 until state.length
                if (state.extended) {
                    if (bufferLen < cursor + 4) return null
                    val repeated = readUint32(cursor)
                    cursor += 4
                    // A new message's fmt3 repeats its delta on the wire; a
                    // continuation merely echoes it with no timestamp effect.
                    if (!continuing && state.delta >= 0) state.timestamp += repeated
                } else if (!continuing && state.delta >= 0) {
                    // Without the ext field the fmt3 delta is implicit: the
                    // previous message's delta on this chunk stream.
                    state.timestamp += state.delta
                }
                if (!continuing) {
                    state.payloadSoFar = 0
                    state.payload = ByteArray(0)
                }
            }
        }
        return Header(state, cursor)
    }

    private fun emit(state: MessageState) {
        // Geometric growth can overshoot [length] by up to 2×; consumers decode
        // the whole array, so hand over exactly the message's declared bytes.
        val payload = if (state.payload.size == state.length) {
            state.payload
        } else {
            state.payload.copyOf(state.length)
        }
        onMessage(state.type, state.streamId, state.timestamp, payload)
        state.payload = ByteArray(0)
        state.payloadSoFar = 0
    }

    /** Rejects a declared message length past [RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES] — fail loud, don't pre-allocate. */
    private fun requireMessageLength(length: Int) {
        if (length > RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES) {
            throw RtmpChunkProtocolException(
                "Message length $length exceeds the ${RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES}-byte cap"
            )
        }
    }

    private fun appendPayload(state: MessageState, src: ByteArray, offset: Int, count: Int) {
        if (state.payload.size < state.payloadSoFar + count) {
            // Geometric growth capped at the message-length bound: buffered
            // memory stays within 2× the bytes actually received, so a declared
            // length alone can never drive the allocation.
            val grown = minOf(
                maxOf(state.payloadSoFar + count, state.payload.size * 2),
                RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES,
            )
            state.payload = state.payload.copyOf(grown)
        }
        System.arraycopy(src, offset, state.payload, state.payloadSoFar, count)
        state.payloadSoFar += count
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (buffer.size - bufferLen < length) {
            val grown = ByteArray(maxOf(buffer.size * 2, bufferLen + length))
            System.arraycopy(buffer, 0, grown, 0, bufferLen)
            buffer = grown
        }
        System.arraycopy(data, offset, buffer, bufferLen, length)
        bufferLen += length
    }

    private fun compact(pos: Int) {
        if (pos <= 0) return
        val remaining = bufferLen - pos
        if (remaining > 0) {
            System.arraycopy(buffer, pos, buffer, 0, remaining)
        }
        bufferLen = remaining
    }

    private fun readUint24(offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            (buffer[offset + 2].toInt() and 0xFF)

    private fun readUint32(offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private companion object {
        private const val INITIAL_BUFFER = 8192
    }
}

package com.raulshma.lenscast.streaming.rtmp

import java.io.OutputStream

/**
 * The RTMP chunk writer — the write half of the chunk protocol, pure over an
 * [OutputStream] so the framing (basic-header formats, continuation chunks,
 * extended timestamps) is JVM-tested against bytes.
 *
 * Per chunk stream it keeps the last timestamp/length/type and the last delta,
 * and picks the smallest legal header format: fmt0 for a stream's first
 * message, fmt3 when nothing moved, fmt2 when only the delta moved, fmt1 when
 * length or type moved. A message larger than the chunk size continues as
 * fmt3 chunks carrying [chunkSize] payload bytes each (each repeating the
 * extended-timestamp field when the active header carried one).
 *
 * [write] returns the byte count it pushed, so the caller's acknowledgement
 * window accounting counts exactly what went on the wire.
 */
class RtmpChunkWriter(
    private val out: OutputStream,
    private var chunkSize: Int = RtmpChunkProtocol.DEFAULT_CHUNK_SIZE,
) {

    private class ChunkState {
        var timestamp = 0
        var delta = -1
        var length = -1
        var type = -1
        /** Whether the most recent fmt0/1/2 header on this chunk stream carried an extended timestamp. */
        var extended = false
    }

    private val states = HashMap<Int, ChunkState>()

    /** Raises the outgoing chunk size (after the Set Chunk Size message announcing it went out). */
    fun setChunkSize(size: Int) {
        require(size in 1..0xFFFFFF) { "Chunk size out of range: $size" }
        chunkSize = size
    }

    fun currentChunkSize(): Int = chunkSize

    /**
     * Writes one message as chunk(s) on [csid]. [timestampMs] is the absolute
     * message timestamp; the delta is derived per chunk stream.
     */
    fun write(csid: Int, timestampMs: Int, typeId: Int, streamId: Int, payload: ByteArray): Int {
        require(csid in 2..63) { "csid $csid outside the single-byte basic-header range" }
        var written = 0
        val state = states.getOrPut(csid) { ChunkState() }
        val firstOnStream = state.length == -1
        val delta = if (firstOnStream) 0 else (timestampMs - state.timestamp).coerceAtLeast(0)
        val absExtended = firstOnStream && timestampMs >= RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL
        val deltaExtended = !firstOnStream && delta >= RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL
        val extended = absExtended || deltaExtended
        // A continuation repeats the extended-timestamp value its first header
        // carried: the absolute timestamp after fmt0, the delta after fmt1/2.
        val repeatedExtValue = if (firstOnStream) timestampMs else delta

        var offset = 0
        var firstChunk = true
        while (offset < payload.size || firstChunk) {
            val take = minOf(payload.size - offset, chunkSize)
            if (firstChunk) {
                written += writeFirstHeader(state, firstOnStream, csid, timestampMs, delta, extended, typeId, streamId, payload.size)
            } else {
                out.write(fmtByte(3, csid))
                written += 1
                // A continuation repeats the extended-timestamp field whenever
                // the header it follows carried one.
                if (state.extended) {
                    writeUint32(out, repeatedExtValue)
                    written += 4
                }
            }
            out.write(payload, offset, take)
            written += take
            offset += take
            firstChunk = false
        }
        out.flush()
        return written
    }

    /** The first chunk's fmt0/1/2/3 header plus the state update. */
    private fun writeFirstHeader(
        state: ChunkState,
        firstOnStream: Boolean,
        csid: Int,
        timestampMs: Int,
        delta: Int,
        extended: Boolean,
        typeId: Int,
        streamId: Int,
        length: Int,
    ): Int {
        var written = 0
        when {
            firstOnStream -> {
                out.write(fmtByte(0, csid))
                writeUint24(out, maskTimestamp(timestampMs))
                writeUint24(out, length)
                out.write(typeId)
                // Message stream id is the one RTMP field that is little-endian.
                out.write(streamId and 0xFF)
                out.write((streamId shr 8) and 0xFF)
                out.write((streamId shr 16) and 0xFF)
                out.write((streamId shr 24) and 0xFF)
                if (extended) writeUint32(out, timestampMs)
                written += 12 + if (extended) 4 else 0
                state.timestamp = timestampMs
                state.delta = -1
            }
            state.length == length && state.type == typeId && state.delta == delta && !extended -> {
                // Nothing moved: the bare fmt3 header (plus the repeated ext
                // field when the previous header carried one).
                out.write(fmtByte(3, csid))
                written += 1
                if (state.extended) {
                    writeUint32(out, delta)
                    written += 4
                }
                state.timestamp += delta
            }
            state.length == length && state.type == typeId -> {
                out.write(fmtByte(2, csid))
                writeUint24(out, maskTimestamp(delta))
                if (extended) writeUint32(out, delta)
                written += 3 + if (extended) 4 else 0
                state.timestamp += delta
                state.delta = delta
            }
            else -> {
                out.write(fmtByte(1, csid))
                writeUint24(out, maskTimestamp(delta))
                writeUint24(out, length)
                out.write(typeId)
                if (extended) writeUint32(out, delta)
                written += 7 + if (extended) 4 else 0
                state.timestamp += delta
                state.delta = delta
            }
        }
        state.length = length
        state.type = typeId
        state.extended = extended
        return written
    }

    private fun fmtByte(fmt: Int, csid: Int): Int = (fmt shl 6) or csid

    private fun maskTimestamp(value: Int): Int =
        if (value >= RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL) {
            RtmpChunkProtocol.EXTENDED_TIMESTAMP_SENTINEL
        } else {
            value
        }

    private fun writeUint24(out: OutputStream, value: Int) {
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeUint32(out: OutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

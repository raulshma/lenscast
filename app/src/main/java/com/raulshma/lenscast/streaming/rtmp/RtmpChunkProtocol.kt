package com.raulshma.lenscast.streaming.rtmp

/**
 * The RTMP chunk-protocol constants shared by the writer and the reader:
 * message type ids, the chunk-stream ids the publisher separates traffic onto
 * (protocol control, commands, audio, video — one stream each, so a large
 * video message never delays a control message), the extended-timestamp
 * sentinel, and the default chunk size both directions start at before any
 * Set Chunk Size exchange.
 */
object RtmpChunkProtocol {

    // ── message type ids ──

    const val TYPE_SET_CHUNK_SIZE = 0x01
    const val TYPE_ABORT = 0x02
    const val TYPE_ACKNOWLEDGEMENT = 0x03
    const val TYPE_USER_CONTROL = 0x04
    const val TYPE_WINDOW_ACK_SIZE = 0x05
    const val TYPE_SET_PEER_BANDWIDTH = 0x06
    const val TYPE_AUDIO = 0x08
    const val TYPE_VIDEO = 0x09
    const val TYPE_DATA_AMF0 = 0x12
    const val TYPE_COMMAND_AMF0 = 0x14

    // ── chunk stream ids (csid 0/1 are reserved for the extended encodings) ──

    /** Protocol control messages (Set Chunk Size, Acknowledgement, …). */
    const val CS_PROTOCOL_CONTROL = 2

    /** Command messages (connect, createStream, publish, onStatus, …). */
    const val CS_COMMAND = 3

    /** Audio messages. */
    const val CS_AUDIO = 4

    /** Video messages. */
    const val CS_VIDEO = 6

    // ── user control event types (payload of type-0x04 messages) ──

    const val EVENT_PING_REQUEST = 6
    const val EVENT_PING_RESPONSE = 7

    /** Timestamp/delta value that means "the real value follows as 4 bytes". */
    const val EXTENDED_TIMESTAMP_SENTINEL = 0xFFFFFF

    /** Both directions start here; the publisher raises its outgoing size after announcing it. */
    const val DEFAULT_CHUNK_SIZE = 128

    /** The outgoing size the publisher announces — one video AU spans a bounded number of chunks. */
    const val PUBLISHER_OUT_CHUNK_SIZE = 4096

    // ── hostile-input bounds (the read side's fail-loud limits) ──

    /**
     * The largest message length the reader accepts from the wire. The u24
     * length field already caps a single message at 16 MB; a phone has no
     * reason to receive command/media messages anywhere near that, and one
     * 16 MB header per chunk stream × 65 599 chunk streams was a remote
     * OOM dial. Generous against every legitimate peer message, small
     * against a heap.
     */
    const val MAX_MESSAGE_LENGTH_BYTES = 0x100000 // 1 MiB

    /**
     * How many distinct chunk streams the reader tracks at once. The protocol
     * allows 65 599, this publisher's peers use 2-6; a hostile peer minting
     * per-csid message state by the tens of thousands gets dropped instead.
     */
    const val MAX_CHUNK_STREAMS = 32

    /** The chunk-size values [RtmpChunkReader.setChunkSize] accepts (the u24 field's range). */
    const val MAX_CHUNK_SIZE = 0xFFFFFF
}

/**
 * The reader's declared failure: a wire stream that violates the chunk
 * protocol's bounds (oversized message length, chunk-stream exhaustion).
 * Callers treat it exactly like [RtmpPublishException] — the attempt fails,
 * the connection drops — never an index bomb, negative-size allocation, or
 * an unchecked throw from deep inside the parse loop.
 */
class RtmpChunkProtocolException(message: String) : Exception(message)

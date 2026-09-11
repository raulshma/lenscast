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
}

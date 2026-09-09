package com.raulshma.lenscast.core.mqtt

import java.io.ByteArrayOutputStream

/**
 * The MQTT 3.1.1 wire codec behind [MqttClient]: pure byte-array encoders for
 * the packet set the alert publisher needs (CONNECT with will/credentials,
 * PUBLISH, PUBACK, PINGREQ, DISCONNECT) and a minimal frame splitter/reader
 * for the inbound set (CONNACK, PUBACK, PINGRESP, anything else skipped by
 * length). Everything here is a pure function over byte arrays, so the wire
 * format is JVM-tested without a socket.
 */
object MqttPacket {

    /** Control packet types (first fixed-header byte, high nibble). */
    const val TYPE_CONNECT = 1
    const val TYPE_CONNACK = 2
    const val TYPE_PUBLISH = 3
    const val TYPE_PUBACK = 4
    const val TYPE_PINGREQ = 12
    const val TYPE_PINGRESP = 13
    const val TYPE_DISCONNECT = 14

    /** CONNECT acknowledgement codes a client accepts as "connected". */
    const val CONNACK_ACCEPTED = 0

    /** The one protocol level this client speaks (3.1.1). */
    private const val PROTOCOL_LEVEL = 4
    private const val MAX_REMAINING_LENGTH = 268_435_455

    // ── Encoders ──

    /** CONNECT with a clean session, credentials, and a last-will message. */
    fun connect(
        clientId: String,
        keepAliveSeconds: Int,
        username: String?,
        password: String?,
        willTopic: String?,
        willMessage: ByteArray?,
        willRetain: Boolean = false,
    ): ByteArray {
        val flags = 0x02 // clean session — this client keeps no broker-side state
            .or(if (willTopic != null) 0x04 else 0)
            .or(if (willRetain) 0x20 else 0)
            .or(if (username != null) 0x80 else 0)
            .or(if (password != null) 0x40 else 0)
        val variable = ByteArrayOutputStream().apply {
            writeString("MQTT")
            write(PROTOCOL_LEVEL)
            write(flags)
            writeU16(keepAliveSeconds)
        }
        val payload = ByteArrayOutputStream().apply {
            writeString(clientId)
            if (willTopic != null) {
                writeString(willTopic)
                // The will payload is binary with a 2-byte length prefix;
                // the will goes out at QoS 0, retained when willRetain is set.
                writeU16(willMessage?.size ?: 0)
                if (willMessage != null) write(willMessage)
            }
            if (username != null) writeString(username)
            if (password != null) writeString(password)
        }
        return frame(TYPE_CONNECT shl 4, variable.toByteArray() + payload.toByteArray())
    }

    /** PUBLISH; QoS 1 carries a packet id and expects a PUBACK. */
    fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, packetId: Int): ByteArray {
        require(qos in 0..1) { "Unsupported QoS $qos" }
        var header = (TYPE_PUBLISH shl 4) or (qos shl 1)
        if (retain) header = header or 0x01
        val variable = ByteArrayOutputStream().apply {
            writeString(topic)
            if (qos > 0) writeU16(packetId)
        }
        return frame(header, variable.toByteArray() + payload)
    }

    fun pingReq(): ByteArray = frame(TYPE_PINGREQ shl 4, ByteArray(0))

    fun disconnect(): ByteArray = frame(TYPE_DISCONNECT shl 4, ByteArray(0))

    /** Fixed header + remaining-length varint + body. */
    private fun frame(firstByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(firstByte)
        writeRemainingLength(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeRemainingLength(out: ByteArrayOutputStream, length: Int) {
        require(length <= MAX_REMAINING_LENGTH) { "Packet too large: $length" }
        var value = length
        do {
            var digit = value % 128
            value /= 128
            if (value > 0) digit = digit or 0x80
            out.write(digit)
        } while (value > 0)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU16(bytes.size)
        write(bytes)
    }

    // ── Inbound frame reading ──

    /**
     * Reads one inbound frame from the head of [buffer]. Returns the frame and
     * the number of bytes consumed, or null when [buffer] holds less than one
     * complete frame (the caller reads more from the socket and retries).
     * Throws [MqttProtocolException] on an invalid remaining-length encoding
     * (more than the spec's four length bytes).
     */
    fun readFrame(buffer: ByteArray): Pair<MqttFrame, Int>? {
        if (buffer.isEmpty()) return null
        var multiplier = 1
        var length = 0
        var index = 1
        while (true) {
            if (index >= buffer.size) return null
            if (index > 4) throw MqttProtocolException("Malformed remaining length")
            val digit = buffer[index].toInt() and 0xFF
            length += (digit and 0x7F) * multiplier
            index++
            if (digit and 0x80 == 0) break
            multiplier *= 128
        }
        val bodyEnd = index + length
        if (buffer.size < bodyEnd) return null
        return MqttFrame(
            type = (buffer[0].toInt() and 0xF0) shr 4,
            flags = buffer[0].toInt() and 0x0F,
            body = buffer.copyOfRange(index, bodyEnd),
        ) to bodyEnd
    }

    data class MqttFrame(val type: Int, val flags: Int, val body: ByteArray)

    /** CONNACK body: session-present flag + return code. */
    fun connackReturnCode(body: ByteArray): Int {
        if (body.size < 2) throw MqttProtocolException("CONNACK too short")
        return body[1].toInt() and 0xFF
    }

    /** PUBACK body: the acknowledged packet id. */
    fun pubackPacketId(body: ByteArray): Int {
        if (body.size < 2) throw MqttProtocolException("PUBACK too short")
        return ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
    }
}

/** A received frame violates the protocol grammar this client speaks. */
class MqttProtocolException(message: String) : Exception(message)

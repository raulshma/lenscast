package com.raulshma.lenscast.streaming.rtsp


/**
 * The codec-neutral video RTP packetizer surface [RtspServer] fans access
 * units through: [RtpPacketizer] for H.264 (RFC 6184), [H265RtpPacketizer]
 * for H.265 (RFC 7798). The server picks the implementation at start from
 * the configured codec, so the fan-out and the RTCP counters stay
 * codec-blind.
 */
internal interface VideoPacketizer {
    val currentSeq: Int

    val wireSsrc: Int

    val sentPacketCount: Long

    val sentOctetCount: Long

    /** Packetizes one NAL unit: single NAL packets or fragmentation units, codec-specific. */
    fun packetizeNalUnit(nalUnit: ByteArray, timestamp: Long, marker: Boolean): List<ByteArray>

    /**
     * Packetizes one access unit — the codec-neutral AU discipline both
     * RFCs share: every NAL unit of the frame carries the same [timestamp],
     * and the marker bit lands on exactly one packet, the last of the AU.
     * Per-NAL markers (the old behavior) announce frame ends mid-AU, which
     * strict depacketizers surface as corrupted frame boundaries.
     */
    fun packetizeAccessUnit(nalUnits: List<ByteArray>, timestamp: Long): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        for ((index, nalUnit) in nalUnits.withIndex()) {
            packets.addAll(packetizeNalUnit(nalUnit, timestamp, marker = index == nalUnits.lastIndex))
        }
        return packets
    }
}

/**
 * RTP packetizer for H.264 NAL units (RFC 6184 FU-A + single NAL). Instance
 * state — sequence number, SSRC, counters — lives in the per-session
 * [RtpStreamState]; a fresh instance per start replaces the old global
 * reset() ritual.
 */
class RtpPacketizer : VideoPacketizer {

    private companion object {
        const val RTP_HEADER_SIZE = 12
        const val MAX_PACKET_SIZE = 1400
        const val PAYLOAD_TYPE = 96

    }

    private val streamState = RtpStreamState()

    override val currentSeq: Int get() = streamState.currentSeq

    /** Low 32 bits of the random SSRC, as written on the wire. */
    override val wireSsrc: Int get() = streamState.wireSsrc

    override val sentPacketCount: Long get() = streamState.sentPacketCount

    override val sentOctetCount: Long get() = streamState.sentOctetCount

    override fun packetizeNalUnit(nalUnit: ByteArray, timestamp: Long, marker: Boolean): List<ByteArray> {
        if (nalUnit.isEmpty()) return emptyList()
        if (nalUnit.size <= MAX_PACKET_SIZE) {
            return listOf(createSingleNalPacket(nalUnit, timestamp, marker))
        }
        return createFragmentedPackets(nalUnit, timestamp, marker)
    }

    private fun createSingleNalPacket(nalUnit: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val packet = ByteArray(RTP_HEADER_SIZE + nalUnit.size)
        writeRtpHeader(packet, timestamp, marker = marker, payloadSize = nalUnit.size)
        System.arraycopy(nalUnit, 0, packet, RTP_HEADER_SIZE, nalUnit.size)
        return packet
    }

    private fun createFragmentedPackets(nalUnit: ByteArray, timestamp: Long, marker: Boolean): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val nalHeader = nalUnit[0]
        val fBit = nalHeader.toInt() and 0x80
        val nri = nalHeader.toInt() and 0x60
        val type = nalHeader.toInt() and 0x1F

        val maxPayload = MAX_PACKET_SIZE - RTP_HEADER_SIZE - 2
        var offset = 1

        var first = true
        while (offset < nalUnit.size) {
            val chunkSize = minOf(maxPayload, nalUnit.size - offset)
            val isLast = offset + chunkSize >= nalUnit.size

            val packet = ByteArray(RTP_HEADER_SIZE + 2 + chunkSize)

            writeRtpHeader(packet, timestamp, marker = isLast && marker, payloadSize = 2 + chunkSize)

            packet[RTP_HEADER_SIZE] = (fBit or nri or 0x1C).toByte()

            var fuHeader = type
            if (first) fuHeader = fuHeader or 0x80
            if (isLast) fuHeader = fuHeader or 0x40
            packet[RTP_HEADER_SIZE + 1] = fuHeader.toByte()

            System.arraycopy(nalUnit, offset, packet, RTP_HEADER_SIZE + 2, chunkSize)

            packets.add(packet)

            offset += chunkSize
            first = false
        }

        return packets
    }

    private fun writeRtpHeader(packet: ByteArray, timestamp: Long, marker: Boolean, payloadSize: Int) {
        val header = streamState.nextHeader(
            timestamp = timestamp,
            marker = marker,
            payloadType = PAYLOAD_TYPE,
            payloadSize = payloadSize,
        )
        System.arraycopy(header, 0, packet, 0, RTP_HEADER_SIZE)
    }
}

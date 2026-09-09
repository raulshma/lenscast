package com.raulshma.lenscast.streaming.rtsp

/**
 * RTP packetizer for H.265 NAL units (RFC 7798 single NAL + Fragmentation
 * Units), the HEVC sibling of [RtpPacketizer]. Same RTP header writer
 * ([RtpStreamState]) and the same AU discipline: every NAL of an access unit
 * shares the [packetizeAccessUnit] timestamp and the marker bit lands on
 * exactly the last packet of the AU.
 *
 * Wire layouts this class emits, byte-exact:
 *
 * Single NAL unit mode (NAL ≤ [MAX_PACKET_SIZE] total): the RTP payload is
 * the raw NAL unit verbatim — its 2-byte header included, no extra payload
 * header (RFC 7798 §4.4.1).
 *
 * ```
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | RTP header (12 bytes, PT=96, marker per AU) | NAL unit ...    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * Fragmentation Units (NAL > [MAX_PACKET_SIZE] total), RFC 7798 §4.4.3, the
 * common one-byte FU header form (no DonL):
 *
 * ```
 * 0                   1                   2
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  RTP header (12 bytes)        |                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | PayloadHdr (Type=49)          | FU header     | FU payload  ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * - PayloadHdr byte 0: `F(1) | 49(6) | layerIdHigh(1)` — i.e.
 *   `(originalByte0 and 0x80) or (49 shl 1) or (originalByte0 and 0x01)`.
 * - PayloadHdr byte 1: the original NAL's byte 1 verbatim (layer-id-low |
 *   temporal-id-plus1).
 * - FU header (1 byte): `S(1) | E(1) | FuType(6)` — 0x80 on the first
 *   fragment, 0x40 on the last, neither in between; the low 6 bits carry the
 *   original NAL type. A receiver reconstructs the original first header
 *   byte as `(PayloadHdr0 and 0x81) or ((fuHeader and 0x3F) shl 1)`.
 *
 * Deliberately NOT handled: aggregation packets (types 48/50), the 2-byte FU
 * header variant with DonL, and PACI — MediaCodec HEVC output is a plain NAL
 * stream and none of our receivers need them.
 */
class H265RtpPacketizer : VideoPacketizer {

    private companion object {
        const val RTP_HEADER_SIZE = 12
        const val MAX_PACKET_SIZE = 1400

        /** RFC 7798 FU payload type — lives in the 6-bit Type field, so on the wire << 1. */
        const val FU_TYPE = 49

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
        // Original 2-byte NAL header: F|type|layerIdHigh | layerIdLow|TID.
        val originalHeader0 = nalUnit[0].toInt() and 0xFF
        val originalHeader1 = nalUnit[1].toInt() and 0xFF
        val originalType = (originalHeader0 shr 1) and 0x3F

        // PayloadHdr: Type=49, F and layerIdHigh preserved from the original header.
        val payloadHdr0 = ((originalHeader0 and 0x80) or (FU_TYPE shl 1) or (originalHeader0 and 0x01)).toByte()
        val payloadHdr1 = originalHeader1.toByte()

        val maxPayload = MAX_PACKET_SIZE - RTP_HEADER_SIZE - 3 // PayloadHdr (2) + FU header (1)
        var offset = 2

        var first = true
        while (offset < nalUnit.size) {
            val chunkSize = minOf(maxPayload, nalUnit.size - offset)
            val isLast = offset + chunkSize >= nalUnit.size

            val packet = ByteArray(RTP_HEADER_SIZE + 3 + chunkSize)

            writeRtpHeader(packet, timestamp, marker = isLast && marker, payloadSize = 3 + chunkSize)

            packet[RTP_HEADER_SIZE] = payloadHdr0
            packet[RTP_HEADER_SIZE + 1] = payloadHdr1

            var fuHeader = originalType
            if (first) fuHeader = fuHeader or 0x80
            if (isLast) fuHeader = fuHeader or 0x40
            packet[RTP_HEADER_SIZE + 2] = fuHeader.toByte()

            System.arraycopy(nalUnit, offset, packet, RTP_HEADER_SIZE + 3, chunkSize)

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

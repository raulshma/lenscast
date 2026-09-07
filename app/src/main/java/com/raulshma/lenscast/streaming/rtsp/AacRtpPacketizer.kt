package com.raulshma.lenscast.streaming.rtsp

/**
 * RFC 3640 RTP packetizer for AAC audio (mpeg4-generic).
 *
 * Each RTP packet carries exactly one AAC access unit using AAC-hbr mode.
 * Payload structure after RTP header:
 *   - AU-headers-length (16 bits): 0x0010 = one 16-bit AU-header
 *   - AU-header (16 bits): upper 13 bits = AU-size, lower 3 bits = AU-index (0)
 *   - Access Unit Data: raw AAC bytes
 *
 * Sequence/SSRC state lives in a per-session [RtpStreamState].
 */
class AacRtpPacketizer {

    private companion object {
        const val RTP_HEADER_SIZE = 12
        const val PAYLOAD_TYPE = 97

        // AU header section: 2 bytes length + 2 bytes AU-header = 4 bytes
        const val AU_HEADER_SECTION_SIZE = 4
    }

    private val streamState = RtpStreamState()

    val currentSeq: Int get() = streamState.currentSeq

    fun packetize(aacAccessUnit: ByteArray, timestamp: Long): ByteArray {
        val auSize = aacAccessUnit.size
        val packetSize = RTP_HEADER_SIZE + AU_HEADER_SECTION_SIZE + auSize
        val packet = ByteArray(packetSize)

        // RTP header: marker bit always set (each packet = one complete AAC frame)
        val header = streamState.nextHeader(
            timestamp = timestamp,
            marker = true,
            payloadType = PAYLOAD_TYPE,
            payloadSize = AU_HEADER_SECTION_SIZE + auSize,
        )
        System.arraycopy(header, 0, packet, 0, RTP_HEADER_SIZE)

        // AU-headers-length: 16 bits, value 16 (= one AU-header of 16 bits)
        packet[RTP_HEADER_SIZE] = 0x00
        packet[RTP_HEADER_SIZE + 1] = 0x10

        // AU-header: 13 bits AU-size, 3 bits AU-index (0)
        packet[RTP_HEADER_SIZE + 2] = ((auSize shr 5) and 0xFF).toByte()
        packet[RTP_HEADER_SIZE + 3] = ((auSize shl 3) and 0xFF).toByte()

        // Access Unit Data
        System.arraycopy(aacAccessUnit, 0, packet, RTP_HEADER_SIZE + AU_HEADER_SECTION_SIZE, auSize)

        return packet
    }
}

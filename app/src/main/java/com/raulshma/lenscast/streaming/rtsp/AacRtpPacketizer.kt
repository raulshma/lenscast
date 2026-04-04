package com.raulshma.lenscast.streaming.rtsp

/**
 * RFC 3640 RTP packetizer for AAC audio (mpeg4-generic).
 *
 * Each RTP packet carries exactly one AAC access unit using AAC-hbr mode.
 * Payload structure after RTP header:
 *   - AU-headers-length (16 bits): 0x0010 = one 16-bit AU-header
 *   - AU-header (16 bits): upper 13 bits = AU-size, lower 3 bits = AU-index (0)
 *   - Access Unit Data: raw AAC bytes
 */
object AacRtpPacketizer {

    private const val RTP_HEADER_SIZE = 12
    private const val PAYLOAD_TYPE = 97

    // AU header section: 2 bytes length + 2 bytes AU-header = 4 bytes
    private const val AU_HEADER_SECTION_SIZE = 4

    private var sequenceNumber = 0L
    private val ssrc: Long = java.util.Random().nextLong()

    @Volatile
    var currentSeq: Int = 0
        private set

    fun reset() {
        sequenceNumber = 0L
    }

    fun packetize(aacAccessUnit: ByteArray, timestamp: Long): ByteArray {
        val auSize = aacAccessUnit.size
        val packetSize = RTP_HEADER_SIZE + AU_HEADER_SECTION_SIZE + auSize
        val packet = ByteArray(packetSize)

        // RTP header: marker bit always set (each packet = one complete AAC frame)
        writeRtpHeader(packet, timestamp, marker = true)

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

    private fun writeRtpHeader(packet: ByteArray, timestamp: Long, marker: Boolean) {
        packet[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0

        val mBit = if (marker) 0x80 else 0
        packet[1] = (mBit or PAYLOAD_TYPE).toByte()

        val seq = (sequenceNumber++ and 0xFFFF).toInt()
        currentSeq = seq
        packet[2] = (seq shr 8).toByte()
        packet[3] = seq.toByte()

        val ts = (timestamp and 0xFFFFFFFFL).toInt()
        packet[4] = (ts ushr 24).toByte()
        packet[5] = (ts ushr 16).toByte()
        packet[6] = (ts ushr 8).toByte()
        packet[7] = ts.toByte()

        packet[8] = (ssrc ushr 24).toByte()
        packet[9] = (ssrc ushr 16).toByte()
        packet[10] = (ssrc ushr 8).toByte()
        packet[11] = ssrc.toByte()
    }
}

package com.raulshma.lenscast.streaming.rtsp

object RtpPacketizer {

    private const val RTP_HEADER_SIZE = 12
    private const val MAX_PACKET_SIZE = 1400
    private const val PAYLOAD_TYPE = 96

    private var sequenceNumber = 0L
    private val ssrc: Long = java.util.Random().nextLong()

    @Volatile
    var currentSeq: Int = 0
        private set

    fun reset() {
        sequenceNumber = 0L
    }

    fun packetizeNalUnit(nalUnit: ByteArray, timestamp: Long, marker: Boolean): List<ByteArray> {
        if (nalUnit.isEmpty()) return emptyList()
        if (nalUnit.size <= MAX_PACKET_SIZE) {
            return listOf(createSingleNalPacket(nalUnit, timestamp, marker))
        }
        return createFragmentedPackets(nalUnit, timestamp, marker)
    }

    private fun createSingleNalPacket(nalUnit: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val packet = ByteArray(RTP_HEADER_SIZE + nalUnit.size)
        writeRtpHeader(packet, timestamp, marker = marker)
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

            writeRtpHeader(packet, timestamp, marker = isLast && marker)

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

    private fun writeRtpHeader(packet: ByteArray, timestamp: Long, marker: Boolean) {
        packet[0] = 0x80.toByte()

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

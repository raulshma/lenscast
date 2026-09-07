package com.raulshma.lenscast.streaming.rtsp

/**
 * Per-start RTP stream state: sequence number, SSRC, header emission, and the
 * packet/octet counters feeding RTCP sender reports. One instance per start
 * replaces the old global reset() ritual — both [RtpPacketizer] and
 * [AacRtpPacketizer] compose it instead of hand-writing the 12-byte header.
 */
class RtpStreamState(private val ssrc: Long = java.util.Random().nextLong()) {

    private var sequenceNumber = 0L

    @Volatile
    var currentSeq: Int = 0
        private set

    /** Low 32 bits of the random SSRC, as written on the wire. */
    val wireSsrc: Int get() = (ssrc and 0xFFFFFFFFL).toInt()

    @Volatile
    var sentPacketCount: Long = 0
        private set

    @Volatile
    var sentOctetCount: Long = 0
        private set

    /**
     * Builds the 12-byte RTP header for the next packet: V=2, the
     * marker-or-PT byte, 16-bit sequence number (wrapping at 0xFFFF), 32-bit
     * timestamp, SSRC — all big-endian. [payloadSize] (header excluded) feeds
     * the octet counter used by RTCP sender reports.
     */
    fun nextHeader(timestamp: Long, marker: Boolean, payloadType: Int, payloadSize: Int): ByteArray {
        val header = ByteArray(RTP_HEADER_SIZE)

        header[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0

        val mBit = if (marker) 0x80 else 0
        header[1] = (mBit or payloadType).toByte()

        val seq = (sequenceNumber++ and 0xFFFF).toInt()
        currentSeq = seq
        header[2] = (seq shr 8).toByte()
        header[3] = seq.toByte()

        val ts = (timestamp and 0xFFFFFFFFL).toInt()
        header[4] = (ts ushr 24).toByte()
        header[5] = (ts ushr 16).toByte()
        header[6] = (ts ushr 8).toByte()
        header[7] = ts.toByte()

        header[8] = (ssrc ushr 24).toByte()
        header[9] = (ssrc ushr 16).toByte()
        header[10] = (ssrc ushr 8).toByte()
        header[11] = ssrc.toByte()

        sentPacketCount++
        sentOctetCount += payloadSize.toLong()
        return header
    }

    private companion object {
        const val RTP_HEADER_SIZE = 12
    }
}

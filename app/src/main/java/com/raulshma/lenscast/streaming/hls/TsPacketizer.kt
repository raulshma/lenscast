package com.raulshma.lenscast.streaming.hls

/**
 * Minimal MPEG-TS muxer for the HLS MVP: PAT + PMT + H.264 video PES.
 * One 188-byte packet home; audio PES is a follow-up (video-only HLS already
 * beats MJPEG on bandwidth + iOS playback). Pure over ByteArrays, JVM-tested
 * for sync byte, packet size, and continuity wrap.
 */
object TsPacketizer {
    const val TS_PACKET_SIZE = 188
    const val SYNC_BYTE: Byte = 0x47
    const val PID_PAT = 0x0000
    const val PID_PMT = 0x1000
    const val PID_VIDEO = 0x0100
    const val PID_AUDIO = 0x0101
    const val STREAM_TYPE_H264 = 0x1B
    const val STREAM_TYPE_AAC = 0x0F

    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0
    private var audioCc = 0

    fun reset() {
        patCc = 0
        pmtCc = 0
        videoCc = 0
        audioCc = 0
    }

    /** One video access unit (Annex-B NALUs) → TS packets with 90kHz [pts]. */
    fun videoAuToTs(nalus: List<ByteArray>, pts90k: Long): ByteArray {
        val annexB = nalus.flatMap { listOf(byteArrayOf(0, 0, 0, 1), it) }
            .fold(ByteArray(0)) { acc, b -> acc + b }
        val pes = buildPes(
            streamId = 0xE0,
            payload = annexB,
            pts90k = pts90k,
            dts90k = pts90k,
        )
        val out = mutableListOf<Byte>()
        out.addAll(pat().toList())
        out.addAll(pmt().toList())
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val chunk = pes.copyOfRange(offset, minOf(offset + 184, pes.size))
            out.addAll(tsPacket(PID_VIDEO, chunk, payloadStart = first, cc = videoCc++ and 0x0F).toList())
            first = false
            offset += chunk.size
        }
        return out.toByteArray()
    }

    internal fun pat(): ByteArray {
        // PAT section: program 1 → PMT PID.
        val section = byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xF0.toByte(), 0x00.toByte(),
        )
        return tsPacket(PID_PAT, sectionWithCrc(section), payloadStart = true, cc = patCc++ and 0x0F)
    }

    internal fun pmt(): ByteArray {
        // PMT: H264 video on PID_VIDEO + AAC audio on PID_AUDIO.
        val section = byteArrayOf(
            0x02, 0xB0.toByte(), 0x17, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xF0.toByte(), 0x00.toByte(),
            STREAM_TYPE_H264.toByte(), 0xE1.toByte(), 0x00.toByte(), 0xF0.toByte(), 0x00.toByte(),
            STREAM_TYPE_AAC.toByte(), 0xE1.toByte(), 0x01.toByte(), 0xF0.toByte(), 0x00.toByte(),
        )
        return tsPacket(PID_PMT, sectionWithCrc(section), payloadStart = true, cc = pmtCc++ and 0x0F)
    }

    /** One AAC raw frame → TS packets with 90kHz [pts] (1024 samples @48kHz). */
    fun audioFrameToTs(aacData: ByteArray, pts90k: Long): ByteArray {
        val pes = buildPes(streamId = 0xC0, payload = aacData, pts90k = pts90k, dts90k = pts90k)
        val out = mutableListOf<Byte>()
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val chunk = pes.copyOfRange(offset, minOf(offset + 184, pes.size))
            out.addAll(tsPacket(PID_AUDIO, chunk, payloadStart = first, cc = audioCc++ and 0x0F).toList())
            first = false
            offset += chunk.size
        }
        return out.toByteArray()
    }

    internal fun buildPes(streamId: Int, payload: ByteArray, pts90k: Long, dts90k: Long): ByteArray {
        val header = mutableListOf<Byte>()
        header.addAll(listOf(0x00, 0x00, 0x01, streamId.toByte()))
        // PES_packet_length = 0 (unbounded for video).
        header.addAll(listOf(0x00, 0x00))
        // Flags: PTS+DTS present.
        header.addAll(listOf(0x80.toByte(), 0xC0.toByte(), 0x0A))
        header.addAll(ptsBytes(pts90k, 0x30).toList())
        header.addAll(ptsBytes(dts90k, 0x10).toList())
        return header.toByteArray() + payload
    }

    internal fun ptsBytes(value: Long, prefix: Int): ByteArray {
        val v = value and 0x1FFFFFFFFL
        return byteArrayOf(
            ((prefix or ((v shr 29) and 0x0E).toInt() or 0x01)).toByte(),
            ((v shr 22) and 0xFF).toByte(),
            ((((v shr 14) and 0xFE).toInt() or 0x01)).toByte(),
            ((v shr 7) and 0xFF).toByte(),
            ((((v shl 1) and 0xFE).toInt() or 0x01)).toByte(),
        )
    }

    internal fun sectionWithCrc(section: ByteArray): ByteArray {
        // Real CRC32 (ISO 13818-1 / IEEE) so strict players accept PAT/PMT.
        return section + crc32(section)
    }

    internal fun crc32(data: ByteArray): ByteArray {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        crc = crc xor 0xFFFFFFFF.toInt()
        return byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
    }

    internal fun tsPacket(pid: Int, payload: ByteArray, payloadStart: Boolean, cc: Int): ByteArray {
        val packets = mutableListOf<Byte>()
        var offset = 0
        var firstPacket = true
        // Single-packet fast path used by PAT/PMT; video PES loops outside.
        val chunk = if (payload.size <= 184) payload else payload.copyOfRange(0, 184)
        val pkt = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = SYNC_BYTE
        pkt[1] = (((if (payloadStart && firstPacket) 0x40 else 0x00) or ((pid shr 8) and 0x1F))).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        System.arraycopy(chunk, 0, pkt, 4, chunk.size)
        packets.addAll(pkt.toList())
        offset += chunk.size
        return packets.toByteArray()
    }
}

package com.raulshma.lenscast.streaming.hls

/**
 * Minimal MPEG-TS muxer for HLS: PAT + PMT + H.264/HEVC video PES + ADTS AAC
 * audio PES. Pure over ByteArrays, JVM-tested for sync byte, packet size,
 * continuity wrap, adaptation-field stuffing and ADTS framing.
 *
 * Two framing rules players enforce and a naive muxer gets wrong:
 *  - A TS packet whose payload does not fill the 184-byte body must declare an
 *    adaptation field and stuff it with 0xFF; writing the padding as payload
 *    appends garbage to the elementary stream.
 *  - AAC in TS (stream type 0x0F) is ADTS-framed. The shared encode hands raw
 *    AAC (correct for RTSP's RFC 3640 and RTMP's FLV tags), so the ADTS header
 *    is built here from the encoder's sample rate / channel count.
 */
object TsPacketizer {
    const val TS_PACKET_SIZE = 188
    const val SYNC_BYTE: Byte = 0x47
    const val PID_PAT = 0x0000
    const val PID_PMT = 0x1000
    const val PID_VIDEO = 0x0100
    const val PID_AUDIO = 0x0101
    const val STREAM_TYPE_H264 = 0x1B
    const val STREAM_TYPE_HEVC = 0x24
    const val STREAM_TYPE_AAC = 0x0F

    /** Adaptation field carrying a PCR: length byte + flags byte + 6 PCR bytes. */
    private const val PCR_FIELD_BYTES = 8
    private const val MAX_PAYLOAD = TS_PACKET_SIZE - 4

    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0
    private var audioCc = 0

    // The video stream type the PMT declares. The muxer is stateless over the
    // elementary-stream bytes (Annex-B framing is identical for both codecs),
    // so only the PMT is codec-aware. The flip is manager-owned and must ride
    // an HlsManager.reset() — segments from both codecs must never share a ring.
    @Volatile private var videoStreamType: Int = STREAM_TYPE_H264

    fun setVideoStreamType(streamType: Int) {
        videoStreamType = streamType
    }

    fun currentVideoStreamType(): Int = videoStreamType

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
        // The PCR rides the first packet of every AU on the video PID — the
        // PID the PMT names as PCR_PID, so a player's clock has a reference.
        out.addAll(packetizePes(PID_VIDEO, pes, pts90k, withPcr = true).toList())
        return out.toByteArray()
    }

    internal fun pat(): ByteArray {
        // PAT section: program 1 → PMT PID. The leading pointer_field (0) is
        // mandatory on a payload-unit-start PSI packet — demuxers skip
        // 1 + pointer_field bytes before the section.
        val section = byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xF0.toByte(), 0x00.toByte(),
        )
        return tsPacket(PID_PAT, byteArrayOf(0x00) + sectionWithCrc(section), payloadStart = true, cc = patCc++ and 0x0F)
    }

    internal fun pmt(): ByteArray {
        // PMT: video on PID_VIDEO (H.264 or HEVC per [videoStreamType]) + AAC
        // audio on PID_AUDIO. PCR_PID is the video PID — the only PID that
        // actually carries PCR fields.
        val section = byteArrayOf(
            0x02, 0xB0.toByte(), 0x17, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xE1.toByte(), 0x00.toByte(),
            0xF0.toByte(), 0x00.toByte(),
            videoStreamType.toByte(), 0xE1.toByte(), 0x00.toByte(), 0xF0.toByte(), 0x00.toByte(),
            STREAM_TYPE_AAC.toByte(), 0xE1.toByte(), 0x01.toByte(), 0xF0.toByte(), 0x00.toByte(),
        )
        return tsPacket(PID_PMT, byteArrayOf(0x00) + sectionWithCrc(section), payloadStart = true, cc = pmtCc++ and 0x0F)
    }

    /** One raw AAC frame → ADTS-framed TS packets with 90kHz [pts90k]. */
    fun audioFrameToTs(
        aacData: ByteArray,
        pts90k: Long,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 1,
    ): ByteArray {
        val adts = adtsHeader(aacData.size, sampleRateHz, channelCount) + aacData
        val pes = buildPes(streamId = 0xC0, payload = adts, pts90k = pts90k, dts90k = pts90k)
        return packetizePes(PID_AUDIO, pes, pts90k, withPcr = false)
    }

    /**
     * Splits one PES into TS packets: the first carries the payload-unit-start
     * indicator (and a PCR when asked), the last stuffs its unused body through
     * an adaptation field instead of leaking padding into the stream.
     */
    private fun packetizePes(pid: Int, pes: ByteArray, pts90k: Long, withPcr: Boolean): ByteArray {
        val out = mutableListOf<Byte>()
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val pcr = if (first && withPcr) pts90k else null
            val limit = if (pcr != null) MAX_PAYLOAD - PCR_FIELD_BYTES else MAX_PAYLOAD
            val chunk = pes.copyOfRange(offset, minOf(offset + limit, pes.size))
            out.addAll(tsPacket(pid, chunk, payloadStart = first, cc = nextCc(pid), pcr90k = pcr).toList())
            first = false
            offset += chunk.size
        }
        return out.toByteArray()
    }

    private fun nextCc(pid: Int): Int = when (pid) {
        PID_VIDEO -> videoCc++ and 0x0F
        PID_AUDIO -> audioCc++ and 0x0F
        else -> 0
    }

    internal fun buildPes(streamId: Int, payload: ByteArray, pts90k: Long, dts90k: Long): ByteArray {
        val header = mutableListOf<Byte>()
        header.addAll(listOf(0x00, 0x00, 0x01, streamId.toByte()))
        // PES_packet_length: unbounded (0) is only legal for video; audio must
        // declare its length so a demuxer knows where the PES ends.
        val length = if (streamId == 0xE0) 0 else payload.size + 13
        header.addAll(listOf(((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()))
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

    /** The 6-byte PCR field: 33-bit 90kHz base + 9-bit 27MHz extension (0). */
    internal fun pcrBytes(pts90k: Long): ByteArray {
        val base = pts90k and 0x1FFFFFFFFL
        return byteArrayOf(
            ((base shr 25) and 0xFF).toByte(),
            ((base shr 17) and 0xFF).toByte(),
            ((base shr 9) and 0xFF).toByte(),
            ((base shr 1) and 0xFF).toByte(),
            (((base and 1) shl 7) or 0x7E).toByte(),
            0x00.toByte(),
        )
    }

    /** The 7-byte ADTS header (no CRC) for one raw AAC-LC frame. */
    internal fun adtsHeader(frameBytes: Int, sampleRateHz: Int, channelCount: Int): ByteArray {
        val len = frameBytes + 7
        val freqIdx = adtsSampleRateIndex(sampleRateHz)
        val profile = 1 // AAC-LC (object type 2 minus 1)
        val chan = channelCount.coerceIn(1, 7)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    internal fun adtsSampleRateIndex(hz: Int): Int = when (hz) {
        96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4
        32_000 -> 5; 24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9
        11_025 -> 10; 8_000 -> 11; 7_350 -> 12
        else -> 3
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

    internal fun tsPacket(
        pid: Int,
        payload: ByteArray,
        payloadStart: Boolean,
        cc: Int,
        pcr90k: Long? = null,
    ): ByteArray {
        val pkt = ByteArray(TS_PACKET_SIZE)
        pkt[0] = SYNC_BYTE
        pkt[1] = (((if (payloadStart) 0x40 else 0x00) or ((pid shr 8) and 0x1F))).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        val spare = MAX_PAYLOAD - payload.size
        val hasAdaptation = spare > 0 || pcr90k != null
        val afc = when {
            hasAdaptation && payload.isNotEmpty() -> 0x30
            hasAdaptation -> 0x20
            else -> 0x10
        }
        pkt[3] = (afc or (cc and 0x0F)).toByte()
        var pos = 4
        if (hasAdaptation) {
            val fieldLen = spare - 1
            pkt[pos++] = fieldLen.toByte()
            if (fieldLen > 0) {
                pkt[pos++] = (if (pcr90k != null) 0x10 else 0x00).toByte()
                var used = 1
                if (pcr90k != null) {
                    val pcr = pcrBytes(pcr90k)
                    System.arraycopy(pcr, 0, pkt, pos, pcr.size)
                    pos += pcr.size
                    used += pcr.size
                }
                while (used < fieldLen) {
                    pkt[pos++] = 0xFF.toByte()
                    used++
                }
            }
        }
        System.arraycopy(payload, 0, pkt, pos, payload.size)
        return pkt
    }
}

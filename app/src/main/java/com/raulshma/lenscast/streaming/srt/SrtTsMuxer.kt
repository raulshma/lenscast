package com.raulshma.lenscast.streaming.srt

import com.raulshma.lenscast.streaming.hls.TsPacketizer

/**
 * The per-session MPEG-TS muxer behind the SRT push: encoded H.264 access
 * units (Annex-B) and AAC frames in, SRT datagram payloads out — 188-byte TS
 * packets aligned to 7-per-datagram so a TS packet never straddles a UDP
 * datagram (the SRT/TS convention).
 *
 * Unlike the HLS ring's muxer this is a per-instance state machine: its own
 * continuity counters (a push session is one continuous transport stream)
 * and a PAT/PMT cadence — always ahead of the first AU and before every
 * keyframe, so a late listener decodes from the next GOP without waiting a
 * full PSI interval. The pure TS primitives (PES building, CRC, single
 * packet frames) stay the HLS muxer's shared core — this class owns only
 * the session state and the datagram slicing.
 *
 * PTS arrives from the publisher's session clock (the encoder's AUs carry no
 * capture stamps; a live push needs monotonic 90 kHz pacing, not absolute
 * time), and because the encoder ladder is progressive the DTS is the PTS.
 */
internal class SrtTsMuxer {

    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0
    private var audioCc = 0

    /** Whether PSI (PAT+PMT) must precede the next write: on start and before keyframes. */
    private var psiDue = true

    /** Restarts a session's stream state (continuity counters + PSI cadence). */
    fun reset() {
        patCc = 0
        pmtCc = 0
        videoCc = 0
        audioCc = 0
        psiDue = true
    }

    /**
     * One video access unit → the datagram payloads carrying its TS packets
     * (and a PAT/PMT prefix when due). Empty NAL lists yield no bytes.
     */
    fun videoAccessUnit(nalUnits: List<ByteArray>, pts90k: Long, isKeyFrame: Boolean): List<ByteArray> {
        if (nalUnits.isEmpty()) return emptyList()
        if (isKeyFrame) psiDue = true
        val prefix = if (psiDue) psi() else ByteArray(0)
        val pes = TsPacketizer.buildPes(
            streamId = 0xE0,
            payload = buildAnnexB(nalUnits),
            pts90k = pts90k,
            dts90k = pts90k,
        )
        psiDue = false
        return sliceDatagrams(prefix + tsPackets(TsPacketizer.PID_VIDEO, pes, ::nextVideoCc))
    }

    /** One AAC raw frame → the datagram payloads carrying its TS packets. */
    fun audioFrame(aacData: ByteArray, pts90k: Long): List<ByteArray> {
        if (aacData.isEmpty()) return emptyList()
        val pes = TsPacketizer.buildPes(
            streamId = 0xC0,
            payload = aacData,
            pts90k = pts90k,
            dts90k = pts90k,
        )
        return sliceDatagrams(tsPackets(TsPacketizer.PID_AUDIO, pes, ::nextAudioCc))
    }

    /** PAT + PMT packets (the PMT declares H.264 video + AAC audio, the only push codec). */
    private fun psi(): ByteArray =
        tsPackets(TsPacketizer.PID_PAT, TsPacketizer.sectionWithCrc(patSection()), ::nextPatCc) +
            tsPackets(TsPacketizer.PID_PMT, TsPacketizer.sectionWithCrc(pmtSection()), ::nextPmtCc)

    /**
     * The PAT section body (before CRC) — program 1 → PMT PID. Same bytes
     * the HLS muxer writes, rebuilt here so the CC counters stay per-session
     * (the HLS singleton's CC state belongs to the ring, not to a push).
     */
    private fun patSection(): ByteArray = byteArrayOf(
        0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        0x00, 0x01, 0xF0.toByte(), 0x00.toByte(),
    )

    /** The PMT section body: video PID (H.264) + audio PID (AAC). */
    private fun pmtSection(): ByteArray = byteArrayOf(
        0x02, 0xB0.toByte(), 0x17, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        0xF0.toByte(), 0x00.toByte(),
        TsPacketizer.STREAM_TYPE_H264.toByte(), 0xE1.toByte(), 0x00.toByte(), 0xF0.toByte(), 0x00.toByte(),
        TsPacketizer.STREAM_TYPE_AAC.toByte(), 0xE1.toByte(), 0x01.toByte(), 0xF0.toByte(), 0x00.toByte(),
    )

    /** One payload (section or PES) → its 188-byte TS packets, CC advancing. */
    private fun tsPackets(pid: Int, payload: ByteArray, nextCc: () -> Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(payload.size + 2 * 188)
        var offset = 0
        var first = true
        while (offset < payload.size || first) {
            val chunk = payload.copyOfRange(offset, minOf(offset + 184, payload.size))
            out.write(TsPacketizer.tsPacket(pid, chunk, payloadStart = first, cc = nextCc() and 0x0F))
            offset += chunk.size
            first = false
        }
        return out.toByteArray()
    }

    private fun buildAnnexB(nalUnits: List<ByteArray>): ByteArray {
        var size = 0
        for (nal in nalUnits) size += 4 + nal.size
        val out = ByteArray(size)
        var offset = 0
        for (nal in nalUnits) {
            out[offset] = 0
            out[offset + 1] = 0
            out[offset + 2] = 0
            out[offset + 3] = 1
            nal.copyInto(out, offset + 4)
            offset += 4 + nal.size
        }
        return out
    }

    private fun sliceDatagrams(tsBytes: ByteArray): List<ByteArray> {
        if (tsBytes.isEmpty()) return emptyList()
        // tsBytes is always a whole number of 188-byte TS packets, so every
        // datagram (and the final partial one) carries whole TS packets — no
        // TS packet ever straddles a UDP datagram boundary.
        val count = (tsBytes.size + SrtPacket.DATA_PAYLOAD_BYTES - 1) / SrtPacket.DATA_PAYLOAD_BYTES
        val out = ArrayList<ByteArray>(count)
        var offset = 0
        while (offset < tsBytes.size) {
            val end = minOf(offset + SrtPacket.DATA_PAYLOAD_BYTES, tsBytes.size)
            out.add(tsBytes.copyOfRange(offset, end))
            offset = end
        }
        return out
    }

    private fun nextPatCc(): Int = patCc++
    private fun nextPmtCc(): Int = pmtCc++
    private fun nextVideoCc(): Int = videoCc++
    private fun nextAudioCc(): Int = audioCc++
}

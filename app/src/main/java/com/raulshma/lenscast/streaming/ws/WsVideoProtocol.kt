package com.raulshma.lenscast.streaming.ws

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit

/**
 * Pure wire-protocol math for the WebSocket H.264 path (JVM-tested):
 *
 *  - AVCC (length-prefixed) conversion: WebCodecs `VideoDecoder` configured
 *    with an `avcC` description expects length-prefixed NAL units, not the
 *    Annex-B start codes the encoders emit.
 *  - avcC record construction from the cached SPS/PPS so a browser joining
 *    mid-stream can configure its decoder before the next keyframe.
 *
 * The socket fan-out lives in [WsMediaServer]; this object never touches it.
 */
object WsVideoProtocol {

    /** NAL unit types (H.264 RBSP header, first byte after the start code). */
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_IDR = 5

    // HEVC NAL unit types (2-byte header: 6-bit type in the first byte's high
    // bits). The server uses these to recognize HEVC AUs and to pick the VPS/
    // SPS/PPS out of a keyframe for the hvcC config message.
    const val NAL_HEVC_VPS = 32
    const val NAL_HEVC_SPS = 33
    const val NAL_HEVC_PPS = 34

    /** HEVC NAL unit type from a start-code-free NAL (the 6-bit type field across bytes 0-1). */
    fun hevcNalType(nal: ByteArray): Int =
        if (nal.size < 2) -1 else ((nal[0].toInt() and 0x7E) shr 1)

    /** The three HEVC parameter sets from one AU's NAL units, when all are present. */
    data class HevcParameterSets(val vps: ByteArray, val sps: ByteArray, val pps: ByteArray)

    /** Scan start-code-free NAL units for the HEVC VPS/SPS/PPS triple; null until all three appear. */
    fun extractHevcParameterSets(nalUnits: List<ByteArray>): HevcParameterSets? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nalUnits) {
            when (hevcNalType(nal)) {
                NAL_HEVC_VPS -> vps = nal
                NAL_HEVC_SPS -> sps = nal
                NAL_HEVC_PPS -> pps = nal
            }
        }
        return if (vps != null && sps != null && pps != null) HevcParameterSets(vps, sps, pps) else null
    }

    /** Annex-B start code. */
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Split an Annex-B AU into its NAL units (start codes stripped). */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var i = 0
        var start = -1
        while (i <= data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                if (start >= 0) units.add(data.copyOfRange(start, i))
                start = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (start in 1 until data.size) units.add(data.copyOfRange(start, data.size))
        return units
    }

    /**
     * Annex-B → AVCC for one AU: every NAL becomes a 4-byte big-endian length
     * prefix followed by its bytes.
     */
    fun annexBToAvcc(data: ByteArray): ByteArray = nalUnitsToAvcc(splitAnnexB(data))

    /**
     * The avcC (DecoderConfigurationRecord) bytes for a given SPS/PPS. The
     * SPS header bytes are indexed defensively: a start-code-free NAL of
     * fewer than 4 bytes typed SPS by its first byte would otherwise be an
     * ArrayIndexOutOfBounds inside the WS fan-out.
     */
    fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 + sps.size + pps.size)
        out.write(1) // configurationVersion
        out.write(sps.getOrElse(1) { 66.toByte() }.toInt()) // AVCProfileIndication (66 = baseline fallback)
        out.write(sps.getOrElse(2) { 0.toByte() }.toInt()) // profile_compatibility
        out.write(sps.getOrElse(3) { 30.toByte() }.toInt()) // AVCLevelIndication (30 = level 3.0 fallback)
        out.write(0xFF) // 111111 + lengthSizeMinusOne=3 (4-byte lengths)
        out.write(0xE1) // 111 + numOfSequenceParameterSets=1
        writeNal(out, sps)
        out.write(1) // numOfPictureParameterSets=1
        writeNal(out, pps)
        return out.toByteArray()
    }

    private fun writeNal(out: java.io.ByteArrayOutputStream, nal: ByteArray) {
        out.write((nal.size shr 8) and 0xFF)
        out.write(nal.size and 0xFF)
        out.write(nal)
    }

    /** Scan an Annex-B AU for its SPS and PPS, when present. */
    fun extractParameterSets(data: ByteArray): Pair<ByteArray, ByteArray>? =
        extractParameterSets(splitAnnexB(data))

    /** NAL units (already start-code-free) → AVCC AU: 4-byte BE length prefix each. */
    fun nalUnitsToAvcc(nalUnits: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream(nalUnits.sumOf { it.size + 4 })
        for (nal in nalUnits) {
            if (nal.isEmpty()) continue
            val len = nal.size
            out.write(byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Scan start-code-free NAL units for their SPS and PPS, when present. */
    fun extractParameterSets(nalUnits: List<ByteArray>): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nalUnits) {
            when (nalType(nal)) {
                NAL_SPS -> sps = nal
                NAL_PPS -> pps = nal
            }
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    /** True when the AU contains a keyframe (IDR) — a safe join point. */
    fun containsKeyframe(nalUnits: List<ByteArray>): Boolean =
        nalUnits.any { nalType(it) == NAL_IDR }

    /** Same verdict for encoder-emitted NAL units, keyed off the encoder's own flag. */
    @JvmName("containsKeyframeEncoded")
    fun containsKeyframe(nalUnits: List<EncodedNalUnit>): Boolean =
        nalUnits.any { it.isKeyFrame }

    fun envelope(magic: String, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(8 + payload.size)
        out.write(magic.toByteArray(Charsets.US_ASCII))
        out.write((payload.size shr 24) and 0xFF)
        out.write((payload.size shr 16) and 0xFF)
        out.write((payload.size shr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** Frame message: 'LCV1' (delta) or 'LCK1' (keyframe) + AVCC AU. */
    fun videoFrameAvcc(avccAu: ByteArray, isKeyFrame: Boolean): ByteArray =
        envelope(if (isKeyFrame) "LCK1" else "LCV1", avccAu)

    /** Config message: 'LCCF' + avcC bytes. */
    fun videoConfig(sps: ByteArray, pps: ByteArray): ByteArray = envelope("LCCF", avcC(sps, pps))

    /**
     * The hvcC (HEVCDecoderConfigurationRecord, ISO 14496-15 §8.3) bytes for a
     * VPS/SPS/PPS triple. The dynamic profile/tier/level bytes come from the
     * SPS's profile_tier_level (payload bytes 2..13, after the 2-byte NAL
     * header); the static structure fields assume the encoder's actual output
     * shape — 8-bit 4:2:0, one NAL per array. Browsers: Safari's WebCodecs
     * parses this natively ('hvc1'/'hev1'); a Chromium-based player needs its
     * own hvcC handling in the WS client before HEVC frames decode — the
     * config message is self-describing so the client can branch on the
     * 'LCHC' magic and fail cleanly instead of misconfiguring an AVC decoder.
     */
    fun hevcC(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(32 + vps.size + sps.size + pps.size)
        out.write(1) // configurationVersion
        // general_profile_space/tier_flag/profile_idc + compatibility flags +
        // constraint flags + level_idc, copied from the SPS's
        // profile_tier_level; defensive indexing like the avcC side.
        out.write(sps.getOrElse(2) { 1.toByte() }.toInt() and 0xFF) // profile_idc 1 = Main fallback
        for (i in 3..6) out.write(sps.getOrElse(i) { 0.toByte() }.toInt() and 0xFF) // general_profile_compatibility_flags
        for (i in 7..12) out.write(sps.getOrElse(i) { 0.toByte() }.toInt() and 0xFF) // general_constraint_indicator_flags
        out.write(sps.getOrElse(13) { 93.toByte() }.toInt() and 0xFF) // general_level_idc (93 = level 3.1 fallback)
        out.write(0xF0) // 1111 + min_spatial_segmentation_idc=0
        out.write(0xFC) // 111111 + parallelismType=0
        out.write(0xFC or 1) // 111111 + chroma_format_idc=1 (4:2:0)
        out.write(0xF8) // 11111 + bit_depth_luma_minus8=0 (8-bit)
        out.write(0xF8) // 11111 + bit_depth_chroma_minus8=0 (8-bit)
        out.write(3) // numOfArrays
        writeHevcArray(out, NAL_HEVC_VPS, vps)
        writeHevcArray(out, NAL_HEVC_SPS, sps)
        writeHevcArray(out, NAL_HEVC_PPS, pps)
        return out.toByteArray()
    }

    /** One hvcC array entry: array_completeness + NAL type, count 1, the length-prefixed NAL. */
    private fun writeHevcArray(out: java.io.ByteArrayOutputStream, nalType: Int, nal: ByteArray) {
        out.write(0x80 or nalType) // array_completeness=1, reserved=0, NAL_unit_type
        out.write(0) // numNalus high byte
        out.write(1) // numNalus low byte
        writeNal(out, nal)
    }

    /** Config message: 'LCHC' + hvcC bytes — self-describing HEVC twin of [videoConfig]. */
    fun hevcVideoConfig(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray =
        envelope("LCHC", hevcC(vps, sps, pps))
}

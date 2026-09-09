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

    /** The avcC (DecoderConfigurationRecord) bytes for a given SPS/PPS. */
    fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 + sps.size + pps.size)
        out.write(1) // configurationVersion
        out.write(sps[1].toInt()) // AVCProfileIndication
        out.write(sps[2].toInt()) // profile_compatibility
        out.write(sps[3].toInt()) // AVCLevelIndication
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
}

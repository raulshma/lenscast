package com.raulshma.lenscast.streaming.rtsp

import java.nio.ByteBuffer

/**
 * The H.264 stream assembler: holds the latest SPS/PPS parameter sets and
 * decides the wire NAL list for each encoded output buffer. The two CSD
 * sources MediaCodec offers — codec-config output buffers and the output
 * format's csd-0/csd-1 — both land in [updateFromConfigBuffer] /
 * [updateFromFormat], and [assemble] owns the prepend-SPS/PPS-if-available
 * decision on keyframes lacking them. Pure Kotlin over plain [ByteBuffer]s
 * (no Android types), so both CSD paths and the assembly are JVM-tested;
 * [MediaCodecVideoEncoder] keeps the shared MediaCodec body and delegates here.
 */
class H264StreamAssembler {

    /** The latest SPS (type 7) as sent on the wire — no start code. Null until a CSD source provides one. */
    @Volatile
    var sps: ByteArray? = null
        private set

    /** The latest PPS (type 8) as sent on the wire — no start code. Null until a CSD source provides one. */
    @Volatile
    var pps: ByteArray? = null
        private set

    /**
     * Learns SPS/PPS from a `BUFFER_FLAG_CODEC_CONFIG` output buffer: the
     * codec-config bytes at [offset]/[size] (Annex-B or AVCC, whichever the
     * device emits) split into NAL units, type 7 → SPS, type 8 → PPS.
     */
    fun updateFromConfigBuffer(buffer: ByteBuffer, offset: Int, size: Int) {
        val data = ByteArray(size)
        buffer.position(offset)
        buffer.get(data)

        for (nalUnit in H264NalParser.extractNalUnits(data)) {
            if (nalUnit.isEmpty()) continue
            when (H264NalParser.nalType(nalUnit)) {
                NAL_TYPE_SPS -> sps = nalUnit
                NAL_TYPE_PPS -> pps = nalUnit
            }
        }
    }

    /**
     * Learns SPS/PPS from an `INFO_OUTPUT_FORMAT_CHANGED` media format:
     * csd-0 is the SPS, csd-1 the PPS, each with any leading Annex-B start
     * code stripped. A null buffer leaves its parameter set unchanged.
     */
    fun updateFromFormat(csd0: ByteBuffer?, csd1: ByteBuffer?) {
        csd0?.let { sps = extractNalFromCsd(it) }
        csd1?.let { pps = extractNalFromCsd(it) }
    }

    /**
     * The wire NAL list for one encoded output buffer's [nalUnits]:
     * keyframes are prepended with the cached SPS/PPS (as non-key NAL units)
     * when both are available — otherwise, and for every non-keyframe, the
     * NALs pass through tagged with the frame's keyframe flag. Parameter
     * sets already in the keyframe's band are NOT deduplicated — the wire
     * output matches what the encoder's inline logic produced.
     */
    fun assemble(nalUnits: List<ByteArray>, isKeyFrame: Boolean): List<EncodedNalUnit> {
        if (nalUnits.isEmpty()) return emptyList()
        if (!isKeyFrame) {
            return nalUnits.map { EncodedNalUnit(it, false) }
        }

        val spsData = sps
        val ppsData = pps
        if (spsData == null || ppsData == null) {
            return nalUnits.map { EncodedNalUnit(it, true) }
        }

        val allNals = mutableListOf(
            EncodedNalUnit(spsData, false),
            EncodedNalUnit(ppsData, false),
        )
        allNals.addAll(nalUnits.map { EncodedNalUnit(it, true) })
        return allNals
    }

    private fun extractNalFromCsd(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return H264NalParser.stripStartCode(bytes)
    }

    companion object {
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
    }
}

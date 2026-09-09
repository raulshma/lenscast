package com.raulshma.lenscast.streaming.rtsp

import java.nio.ByteBuffer

/**
 * The H.265 stream assembler: holds the latest VPS/SPS/PPS parameter sets and
 * decides the wire NAL list for each encoded output buffer — the HEVC sibling
 * of [H264StreamAssembler]. H.265 adds a third parameter set (the VPS), and
 * MediaCodec's HEVC encoders emit the CSD in either shape: one csd-0 blob
 * carrying all three Annex-B NAL units, or csd-0/csd-1/csd-2 split per set.
 * [updateFromConfigBuffer] / [updateFromFormat] handle both, and [assemble]
 * owns the prepend-VPS+SPS+PPS-if-available decision on keyframes lacking
 * them. Pure Kotlin over plain [ByteBuffer]s (no Android types), so both CSD
 * paths and the assembly are JVM-tested; [MediaCodecVideoEncoder] keeps the
 * MediaCodec lifecycle and delegates here.
 */
class H265StreamAssembler {

    /** The latest VPS (type 32) as sent on the wire — no start code. Null until a CSD source provides one. */
    @Volatile
    var vps: ByteArray? = null
        private set

    /** The latest SPS (type 33) as sent on the wire — no start code. Null until a CSD source provides one. */
    @Volatile
    var sps: ByteArray? = null
        private set

    /** The latest PPS (type 34) as sent on the wire — no start code. Null until a CSD source provides one. */
    @Volatile
    var pps: ByteArray? = null
        private set

    /**
     * Learns VPS/SPS/PPS from a `BUFFER_FLAG_CODEC_CONFIG` output buffer: the
     * codec-config bytes at [offset]/[size] — the common single-blob case —
     * are Annex-B split (or AVCC, whichever the device emits) and each NAL
     * classified by its 2-byte header: 32 → VPS, 33 → SPS, 34 → PPS.
     */
    fun updateFromConfigBuffer(buffer: ByteBuffer, offset: Int, size: Int) {
        val data = ByteArray(size)
        buffer.position(offset)
        buffer.get(data)

        for (nalUnit in H264NalParser.extractNalUnits(data)) {
            learn(nalUnit)
        }
    }

    /**
     * Learns VPS/SPS/PPS from an `INFO_OUTPUT_FORMAT_CHANGED` media format.
     * Handles both device shapes: csd-0 alone containing all three (the
     * common HEVC blob, start codes and all), or the fully split
     * csd-0=VPS / csd-1=SPS / csd-2=PPS triple. Each buffer is Annex-B split
     * when it carries start codes, else start-code-stripped and classified by
     * its 2-byte header. A null buffer leaves its parameter set unchanged.
     */
    fun updateFromFormat(csd0: ByteBuffer?, csd1: ByteBuffer?, csd2: ByteBuffer? = null) {
        csd0?.let { learnFromCsd(it) }
        csd1?.let { learnFromCsd(it) }
        csd2?.let { learnFromCsd(it) }
    }

    /**
     * The wire NAL list for one encoded output buffer's [nalUnits]:
     * keyframes are prepended with the cached VPS/SPS/PPS (as non-key NAL
     * units, in that order) when all three are available — otherwise, and for
     * every non-keyframe, the NALs pass through tagged with the frame's
     * keyframe flag. Parameter sets already in the keyframe's band are NOT
     * deduplicated — the wire output matches what the encoder's inline logic
     * produced, exactly like the H.264 assembler.
     */
    fun assemble(nalUnits: List<ByteArray>, isKeyFrame: Boolean): List<EncodedNalUnit> {
        if (nalUnits.isEmpty()) return emptyList()
        if (!isKeyFrame) {
            return nalUnits.map { EncodedNalUnit(it, false) }
        }

        val vpsData = vps
        val spsData = sps
        val ppsData = pps
        if (vpsData == null || spsData == null || ppsData == null) {
            return nalUnits.map { EncodedNalUnit(it, true) }
        }

        val allNals = mutableListOf(
            EncodedNalUnit(vpsData, false),
            EncodedNalUnit(spsData, false),
            EncodedNalUnit(ppsData, false),
        )
        allNals.addAll(nalUnits.map { EncodedNalUnit(it, true) })
        return allNals
    }

    /** One csd buffer from the output format: split-or-strip, then classify. */
    private fun learnFromCsd(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val nals = H264NalParser.extractAnnexBNalUnits(bytes)
        if (nals.isNotEmpty()) {
            // Blob case: one csd buffer carrying several Annex-B NAL units.
            nals.forEach { learn(it) }
            return
        }
        // Single-NAL case: no start codes — strip a leading one if present,
        // then classify by the 2-byte header.
        learn(H264NalParser.stripStartCode(bytes))
    }

    /** Classifies one header-stripped NAL unit into the parameter-set slots. */
    private fun learn(nalUnit: ByteArray) {
        if (nalUnit.isEmpty()) return
        when {
            H265NalParser.isVps(nalUnit) -> vps = nalUnit
            H265NalParser.isSps(nalUnit) -> sps = nalUnit
            H265NalParser.isPps(nalUnit) -> pps = nalUnit
        }
    }
}

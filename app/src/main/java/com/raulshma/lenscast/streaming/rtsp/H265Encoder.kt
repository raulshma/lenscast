package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * The HEVC video encoder: [MediaCodecVideoEncoder]'s shared MediaCodec body
 * (lifecycle, lag gate, bitrate/key-frame parameters, black-frame CSD kick,
 * input feeding, MediaFormat construction) plus the HEVC-specific knowledge
 * — VPS/SPS/PPS interpretation through [H265StreamAssembler] (HEVC's third
 * parameter set) and the NAL-level keyframe verdict
 * ([H265NalParser.containsIdr]).
 *
 * The hub instantiates this lazily and only for the active codec; consumers
 * must not assume an H265Encoder exists (or that its output reaches HLS/WS —
 * those sinks stay H.264-only at the fan-out).
 */
internal class H265Encoder : MediaCodecVideoEncoder(ENCODER_NAME, MediaFormat.MIMETYPE_VIDEO_HEVC) {

    private val streamAssembler = H265StreamAssembler()

    override val sps: ByteArray?
        get() = streamAssembler.sps

    override val pps: ByteArray?
        get() = streamAssembler.pps

    /** The HEVC-only third parameter set; the H.264 sibling has no answer. */
    override val vps: ByteArray?
        get() = streamAssembler.vps

    override fun updateCsdFromConfigBuffer(buffer: ByteBuffer, offset: Int, size: Int) {
        streamAssembler.updateFromConfigBuffer(buffer, offset, size)
    }

    override fun assembleFrame(nalUnits: List<ByteArray>, isKeyFrame: Boolean): List<EncodedNalUnit> =
        streamAssembler.assemble(nalUnits, isKeyFrame)

    override fun learnCsdFromFormat(format: MediaFormat) {
        try {
            // HEVC CSD comes in either shape: all three sets in csd-0 (the
            // common blob) or split across csd-0/csd-1/csd-2. The assembler
            // handles both; null buffers leave their parameter set untouched.
            streamAssembler.updateFromFormat(
                csd0 = format.getByteBuffer("csd-0"),
                csd1 = format.getByteBuffer("csd-1"),
                csd2 = format.getByteBuffer("csd-2"),
            )
            Log.d(TAG, "VPS/SPS/PPS extracted from format: vps=${vps?.size} sps=${sps?.size} pps=${pps?.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract VPS/SPS/PPS from format", e)
        }
    }

    override fun containsKeyframeNal(nalUnits: List<ByteArray>): Boolean =
        H265NalParser.containsIdr(nalUnits)

    private companion object {
        private const val TAG = "H265Encoder"
        private const val ENCODER_NAME = "H265"
    }
}

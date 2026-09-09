package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * The AVC video encoder: [MediaCodecVideoEncoder]'s shared MediaCodec body
 * (lifecycle, lag gate, bitrate/key-frame parameters, black-frame CSD kick,
 * input feeding, MediaFormat construction) plus the H.264-specific knowledge
 * — SPS/PPS interpretation through [H264StreamAssembler] and the NAL-level
 * keyframe verdict ([H264NalParser.containsIdr], the authoritative signal
 * that MediaCodec's BUFFER_FLAG_KEY_FRAME flag only fast-paths).
 */
internal class H264Encoder : MediaCodecVideoEncoder(ENCODER_NAME, MediaFormat.MIMETYPE_VIDEO_AVC) {

    private val streamAssembler = H264StreamAssembler()

    override val sps: ByteArray?
        get() = streamAssembler.sps

    override val pps: ByteArray?
        get() = streamAssembler.pps

    override fun updateCsdFromConfigBuffer(buffer: ByteBuffer, offset: Int, size: Int) {
        streamAssembler.updateFromConfigBuffer(buffer, offset, size)
    }

    override fun assembleFrame(nalUnits: List<ByteArray>, isKeyFrame: Boolean): List<EncodedNalUnit> =
        streamAssembler.assemble(nalUnits, isKeyFrame)

    override fun learnCsdFromFormat(format: MediaFormat) {
        try {
            streamAssembler.updateFromFormat(
                csd0 = format.getByteBuffer("csd-0"),
                csd1 = format.getByteBuffer("csd-1"),
            )
            Log.d(TAG, "SPS/PPS extracted from format: sps=${sps?.size} pps=${pps?.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract SPS/PPS from format", e)
        }
    }

    override fun containsKeyframeNal(nalUnits: List<ByteArray>): Boolean =
        H264NalParser.containsIdr(nalUnits)

    private companion object {
        private const val TAG = "H264Encoder"
        private const val ENCODER_NAME = "H264"
    }
}

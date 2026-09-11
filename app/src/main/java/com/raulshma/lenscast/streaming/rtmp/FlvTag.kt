package com.raulshma.lenscast.streaming.rtmp

import com.raulshma.lenscast.streaming.ws.WsVideoProtocol
import java.io.ByteArrayOutputStream

/**
 * The FLV-style RTMP message bodies, pure over byte arrays so the wire shapes
 * are JVM-tested against a known SPS/PPS and AudioSpecificConfig:
 *
 *  - Video: the AVC sequence header (FLV tag header + AVCPacketType 0 + the
 *    AVCC `avcC` record built from the encoder's SPS/PPS) and the NALU
 *    packets (AVCPacketType 1 + composition time + length-prefixed NALs) —
 *    the same AVCC conversion the WS/WebCodecs path uses
 *    ([WsVideoProtocol]), one home for the record format.
 *  - Audio: the AAC sequence header (AudioSpecificConfig) and raw AAC frames,
 *    each behind the FLV audio tag header (AAC, 44 kHz marker bits, 16-bit,
 *    stereo — the rates the ASC overrides anyway).
 *
 * Composition time is a signed 24-bit field; the MediaCodec encoders this app
 * configures run without B-frames, so the publisher sends 0 and viewers see
 * ct = dts.
 */
object FlvTag {

    // FLV video tag: frame type (top nibble) + codec id.
    private const val FRAME_TYPE_KEY = 0x10
    private const val FRAME_TYPE_INTER = 0x20
    private const val CODEC_ID_AVC = 0x07

    // AVC packet types inside a video tag body.
    private const val AVC_PACKET_SEQUENCE_HEADER = 0x00
    private const val AVC_PACKET_NALU = 0x01

    // FLV audio tag header: sound format AAC (10) << 4 | rate 3 (44 kHz) << 2 | 16-bit << 1 | stereo.
    private const val AAC_AUDIO_HEADER = 0xAF

    // AAC packet types inside an audio tag body.
    private const val AAC_PACKET_SEQUENCE_HEADER = 0x00
    private const val AAC_PACKET_RAW = 0x01

    /** The AVC sequence header message: the FLV-tag body carrying the avcC record. */
    fun avcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(5 + 16 + sps.size + pps.size)
        out.write(FRAME_TYPE_KEY or CODEC_ID_AVC)
        out.write(AVC_PACKET_SEQUENCE_HEADER)
        writeUint24(out, 0)
        out.write(WsVideoProtocol.avcC(sps, pps))
        return out.toByteArray()
    }

    /** One video AU as an AVC NALU packet: length-prefixed NALs behind the 5-byte tag body. */
    fun avcPacket(nalUnits: List<ByteArray>, isKeyFrame: Boolean, compositionTimeMs: Int = 0): ByteArray {
        val body = WsVideoProtocol.nalUnitsToAvcc(nalUnits)
        val out = ByteArrayOutputStream(5 + body.size)
        out.write((if (isKeyFrame) FRAME_TYPE_KEY else FRAME_TYPE_INTER) or CODEC_ID_AVC)
        out.write(AVC_PACKET_NALU)
        writeUint24(out, compositionTimeMs)
        out.write(body)
        return out.toByteArray()
    }

    /** The AAC sequence header message: the FLV-tag body carrying the AudioSpecificConfig. */
    fun aacSequenceHeader(audioSpecificConfig: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(2 + audioSpecificConfig.size)
        out.write(AAC_AUDIO_HEADER)
        out.write(AAC_PACKET_SEQUENCE_HEADER)
        out.write(audioSpecificConfig)
        return out.toByteArray()
    }

    /** One raw AAC frame behind the FLV audio tag header. */
    fun aacFrame(aacData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(2 + aacData.size)
        out.write(AAC_AUDIO_HEADER)
        out.write(AAC_PACKET_RAW)
        out.write(aacData)
        return out.toByteArray()
    }

    /** Signed 24-bit big-endian, the composition-time field's width. */
    private fun writeUint24(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.parseWireNameOrNull

/**
 * The RTSP output's video codec choices: the wire name the Web API DTO
 * carries and the encoder/RTP/SDP stack it selects behind
 * [com.raulshma.lenscast.streaming.EncodedStreamHub] and [RtspServer]. This
 * enum is the one pure mapper — wire name → codec — so the web handler, the
 * manager, and the hub can never disagree about what "h265" means.
 *
 * Deliberately NOT handled here: HLS and WS video stay H.264-only — on H265
 * those sinks are gated off at the hub's fan-out. The codec persists through
 * its `rtsp_video_codec` SettingsDataStore descriptor; the Settings Applier
 * applies it to the manager through the same restart ladder as every other
 * store-backed RTSP setting.
 */
enum class RtspVideoCodec(
    val wireName: String,
) {
    /** H.264/AVC — the default and the only codec HLS/WS understand. */
    H264("h264"),

    /** H.265/HEVC — RTSP-only until the HLS muxer and WS path learn HEVC. */
    H265("h265"),
    ;

    companion object {
        const val DEFAULT_WIRE_NAME = "h264"

        val DEFAULT: RtspVideoCodec = H264

        private val byWireName: Map<String, RtspVideoCodec> = entries.associateBy { it.wireName }

        /**
         * The tolerant decode: a null, blank, or unknown wire name falls back
         * to [DEFAULT] — [com.raulshma.lenscast.core.parseEnum]'s convention
         * and [RtspResolution.fromWireName]'s, case-sensitive like both.
         */
        fun fromWireName(name: String?): RtspVideoCodec =
            fromWireNameOrNull(name) ?: DEFAULT

        /** The skip-apply variant: null, blank, or unknown yields null. */
        fun fromWireNameOrNull(name: String?): RtspVideoCodec? =
            parseWireNameOrNull(name, byWireName)
    }
}

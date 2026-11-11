package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.camera.model.StreamAspect
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.parseWireNameOrNull

/**
 * The RTSP output's video resolution choices: the persisted wire name and the
 * encoder dimensions it maps to. The setting is stored as the wire name (the
 * same string the Web API DTO carries); this enum is the one pure mapper —
 * wire name → (width, height) — so the store, the Settings Applier, and the
 * DTO mapping can never disagree on the dimensions behind a name.
 *
 * The entries deliberately do not use the camera `Resolution` enum: the RTSP
 * output encodes a fixed-size stream of its own choosing, independent of the
 * camera preview's capture resolution. The [aspect] does flow to the camera,
 * though: the Settings Applier steers the streaming analysis window with it,
 * so a 4:3 tier encodes the full sensor width instead of cropping 16:9
 * analysis frames.
 */
enum class RtspResolution(
    val wireName: String,
    val width: Int,
    val height: Int,
    val aspect: StreamAspect = StreamAspect.WIDE_16_9,
) {
    /** 480p — the low-bandwidth choice (already 4:3). */
    P480("480p", 640, 480, StreamAspect.STANDARD_4_3),

    /** 720p — the default, matching [StreamDefaults.RTSP_VIDEO_WIDTH]/[StreamDefaults.RTSP_VIDEO_HEIGHT]. */
    P720("720p", StreamDefaults.RTSP_VIDEO_WIDTH, StreamDefaults.RTSP_VIDEO_HEIGHT),

    /** 720p 4:3 — the native-sensor aspect at the 720-line tier. */
    P720_43("720p43", 960, 720, StreamAspect.STANDARD_4_3),

    /** 1080p — the full-HD ceiling. */
    P1080("1080p", 1920, 1080),

    /** 1080p 4:3 — the native-sensor aspect at the 1080-line tier. */
    P1080_43("1080p43", 1440, 1080, StreamAspect.STANDARD_4_3),
    ;

    companion object {
        const val DEFAULT_WIRE_NAME = "720p"

        val DEFAULT: RtspResolution = P720

        private val byWireName: Map<String, RtspResolution> = entries.associateBy { it.wireName }

        /**
         * The tolerant decode: a null, blank, or unknown wire name falls back
         * to [DEFAULT] — [com.raulshma.lenscast.core.parseEnum]'s convention,
         * with the fallback explicit at the type.
         */
        fun fromWireName(name: String?): RtspResolution =
            fromWireNameOrNull(name) ?: DEFAULT

        /** The skip-save variant: null, blank, or unknown yields null. */
        fun fromWireNameOrNull(name: String?): RtspResolution? =
            parseWireNameOrNull(name, byWireName)
    }
}

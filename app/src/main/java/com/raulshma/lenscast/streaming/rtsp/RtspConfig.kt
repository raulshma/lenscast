package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamDefaults
import java.util.Locale

/**
 * Everything the RTSP output needs, as one immutable value. Passing this to
 * [RtspServer.start] replaces the old order-sensitive setter bag — "fully
 * configure, then start" is enforced by construction — and [RtspServer.apply]
 * owns the per-setting live-update semantics in one place.
 */
data class RtspConfig(
    val videoWidth: Int = StreamDefaults.RTSP_VIDEO_WIDTH,
    val videoHeight: Int = StreamDefaults.RTSP_VIDEO_HEIGHT,
    val videoBitrate: Int = StreamDefaults.RTSP_VIDEO_BITRATE,
    val videoFrameRate: Int = StreamDefaults.STREAM_FPS,
    val inputFormat: RtspInputFormat = RtspInputFormat.AUTO,
    val audioEnabled: Boolean = false,
    val audioSampleRateHz: Int = StreamDefaults.AUDIO_SAMPLE_RATE_HZ,
    val audioChannelCount: Int = StreamDefaults.AUDIO_CHANNELS,
    val audioBitrateKbps: Int = StreamDefaults.AUDIO_BITRATE_KBPS,
    val auth: RtspAuthSpec? = null,
)

/**
 * RTSP auth identity: a null spec means auth is off, presence means enabled.
 * The Digest HA1 is normalized to lowercase here, matching the server's
 * storage contract.
 */
class RtspAuthSpec(username: String, passwordHash: String, digestHa1: String) {
    val username: String = username
    val passwordHash: String = passwordHash
    val digestHa1: String = digestHa1.lowercase(Locale.US)
}

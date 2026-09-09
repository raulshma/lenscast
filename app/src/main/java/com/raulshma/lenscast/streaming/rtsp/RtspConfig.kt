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

/** How a changed [RtspConfig] field reaches the running server. */
enum class RtspChangeScope {
    /** [RtspServer.apply] takes the change in place — no restart owed. */
    HOT_SWAP,

    /**
     * Only a server restart (stop + start with the retained config) makes the
     * change effective; applying it live would silently do nothing.
     */
    NEEDS_RESTART,
}

/**
 * One RTSP config field, tagged with the scope that makes its change real.
 * Ground truth: [AacEncoder.setBitrate] only stores an audio-bitrate change
 * until its next `start()` — hence the audio-bitrate NEEDS_RESTART entry.
 * The H.264 encode bitrate is the encoded-stream hub's own (equal by
 * default): a video-bitrate write fans out to
 * [com.raulshma.lenscast.streaming.EncodedStreamHub.setVideoBitrate] — a
 * live MediaCodec `setParameters`, the way frame rate and input format do —
 * while the config's [RtspConfig.videoBitrate] feeds the SDP's `b=AS` line
 * through the live config [RtspServer.apply] retains. Dimensions ride the
 * frame-path reconfigure, input-format changes reconfigure in the hub, the
 * frame rate flows into the RTP timestamp increment through the live config
 * getter, and the authorizer reads the auth spec live — all HOT_SWAP,
 * all restart-free.
 */
enum class RtspField(val scope: RtspChangeScope) {
    VIDEO_WIDTH(RtspChangeScope.HOT_SWAP),
    VIDEO_HEIGHT(RtspChangeScope.HOT_SWAP),
    VIDEO_BITRATE(RtspChangeScope.HOT_SWAP),
    VIDEO_FRAME_RATE(RtspChangeScope.HOT_SWAP),
    INPUT_FORMAT(RtspChangeScope.HOT_SWAP),
    AUDIO_ENABLED(RtspChangeScope.NEEDS_RESTART),
    AUDIO_SAMPLE_RATE_HZ(RtspChangeScope.NEEDS_RESTART),
    AUDIO_CHANNEL_COUNT(RtspChangeScope.NEEDS_RESTART),
    AUDIO_BITRATE_KBPS(RtspChangeScope.NEEDS_RESTART),
    AUTH(RtspChangeScope.HOT_SWAP),
}

/**
 * The pure restart-vs-hot-swap verdict for two [RtspConfig] values. Callers
 * diff old vs new and let the changed fields' scopes decide: any
 * NEEDS_RESTART field means restart the server; otherwise [RtspServer.apply]
 * takes the update in place.
 */
object RtspConfigDiff {

    /** The set of fields whose values differ between [old] and [new]. */
    fun of(old: RtspConfig, new: RtspConfig): Set<RtspField> {
        val changed = mutableSetOf<RtspField>()
        if (old.videoWidth != new.videoWidth) changed += RtspField.VIDEO_WIDTH
        if (old.videoHeight != new.videoHeight) changed += RtspField.VIDEO_HEIGHT
        if (old.videoBitrate != new.videoBitrate) changed += RtspField.VIDEO_BITRATE
        if (old.videoFrameRate != new.videoFrameRate) changed += RtspField.VIDEO_FRAME_RATE
        if (old.inputFormat != new.inputFormat) changed += RtspField.INPUT_FORMAT
        if (old.audioEnabled != new.audioEnabled) changed += RtspField.AUDIO_ENABLED
        if (old.audioSampleRateHz != new.audioSampleRateHz) changed += RtspField.AUDIO_SAMPLE_RATE_HZ
        if (old.audioChannelCount != new.audioChannelCount) changed += RtspField.AUDIO_CHANNEL_COUNT
        if (old.audioBitrateKbps != new.audioBitrateKbps) changed += RtspField.AUDIO_BITRATE_KBPS
        // RtspAuthSpec is identity-equals: a fresh spec instance counts as a change.
        if (old.auth !== new.auth) changed += RtspField.AUTH
        return changed
    }

    /** True when at least one changed field cannot be applied live. */
    fun needsRestart(changed: Set<RtspField>): Boolean =
        changed.any { it.scope == RtspChangeScope.NEEDS_RESTART }
}

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

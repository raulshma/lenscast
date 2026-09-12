package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalState

/**
 * The pure encoded-video bitrate ladder, the MJPEG half's twin
 * ([com.raulshma.lenscast.core.NetworkAdaptationPolicy]): measured encoded-sink
 * throughput (RTSP RTP / WS video / HLS writes — the aggregate lane in
 * [com.raulshma.lenscast.core.NetworkQualityMonitor]) and the thermal tier
 * scale the CONFIGURED bitrate down under pressure and back up when the
 * network allows, with every verdict clamped to
 * [StreamDefaults.VIDEO_BITRATE_MIN]..configured — adaptation may lower the
 * configured ceiling, never raise above it, and never below the sane floor.
 *
 * The verdict is a step, not a chase: the target only moves when it differs
 * from the current value by more than [STEP_FRACTION] of the configured
 * bitrate (hysteresis, so a noisy throughput lane cannot sawtooth the encoder
 * per poll). A null answer means "keep the current target".
 *
 * Pure over its inputs; the owner ([com.raulshma.lenscast.streaming.StreamingManager]'s
 * adaptive monitor) supplies the measured level and applies the target through
 * the encoded-stream hub's live `setVideoBitrate` hot-swap.
 */
object EncodedBitratePolicy {

    /** Relative change below which the current target is kept (anti-sawtooth). */
    const val STEP_FRACTION = 0.1f

    /** The throughput-level factor, mirroring the MJPEG quality ladder's rungs. */
    fun networkFactor(level: NetworkQualityMonitor.NetworkQualityLevel): Float = when (level) {
        NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT -> 1.0f
        NetworkQualityMonitor.NetworkQualityLevel.GOOD -> 0.9f
        NetworkQualityMonitor.NetworkQualityLevel.FAIR -> 0.75f
        NetworkQualityMonitor.NetworkQualityLevel.POOR -> 0.55f
        NetworkQualityMonitor.NetworkQualityLevel.CRITICAL -> 0.35f
    }

    /**
     * The thermal factor, mirroring [com.raulshma.lenscast.core.ThermalThrottlePolicy]'s
     * frame-rate multiplier shape (this policy's own ladder — each policy owns
     * its constants). CRITICAL already pauses the stream upstream; the 0.35f
     * rung only expresses the tier if it is ever consulted mid-teardown.
     */
    fun thermalFactor(state: ThermalState): Float = when (state) {
        ThermalState.NORMAL -> 1.0f
        ThermalState.LIGHT -> 0.9f
        ThermalState.MODERATE -> 0.7f
        ThermalState.SEVERE -> 0.5f
        ThermalState.CRITICAL -> 0.35f
    }

    /**
     * The next encoded-video target bitrate, or null when the current one
     * stands. [configuredBitrate] is the (persisted-default) ceiling the
     * adaptive ladder starts from; [currentBitrate] is what the encoder runs
     * at right now ([com.raulshma.lenscast.streaming.EncodedStreamHub.currentVideoBitrate]).
     */
    fun targetBitrate(
        enabled: Boolean,
        level: NetworkQualityMonitor.NetworkQualityLevel,
        thermal: ThermalState,
        configuredBitrate: Int,
        currentBitrate: Int,
    ): Int? {
        if (!enabled) return null
        val configured = configuredBitrate
            .coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX)
        val desired = (configured * networkFactor(level) * thermalFactor(thermal)).toInt()
            .coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, configured)
        val step = (configured * STEP_FRACTION).toInt().coerceAtLeast(1)
        return when {
            desired > currentBitrate + step -> desired
            desired < currentBitrate - step -> desired
            else -> null
        }
    }
}

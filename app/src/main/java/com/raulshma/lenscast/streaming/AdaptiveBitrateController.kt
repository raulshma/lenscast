package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single adaptive-bitrate brain. Applies NetworkQualityMonitor's live
 * quality ladders onto the frame path via [getAdaptiveQuality] and
 * [getAdaptiveFrameInterval], and publishes the driven state for dashboards:
 * what the UI and the Web API read is what the frame path actually applied.
 *
 * There is no separate planning step — the frame path is the driver, and the
 * published state is updated from the values it produces.
 */
class AdaptiveBitrateController(
    private val networkMonitor: NetworkQualityMonitor,
    private val config: AdaptiveBitrateConfig = AdaptiveBitrateConfig(),
) : NetworkAdjustmentSource {

    private val isEnabled = AtomicBoolean(config.enabledByDefault)
    private val adjustmentCount = AtomicInteger(0)

    // Mutable because settings (stream fps) update it at runtime; written from
    // the settings applier coroutine, read on frame threads.
    @Volatile
    private var defaultIntervalMs = config.defaultFrameIntervalMs

    // Last values actually applied on the frame path.
    @Volatile
    private var appliedQuality = config.defaultQuality
    @Volatile
    private var appliedIntervalMs = defaultIntervalMs
    @Volatile
    private var lastPublishMs = 0L

    // Values as of the last published state — the adjustment counter ticks
    // once per published change, not once per frame.
    @Volatile
    private var publishedQuality = config.defaultQuality
    @Volatile
    private var publishedIntervalMs = defaultIntervalMs

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<AdaptiveState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (isEnabled.getAndSet(enabled) == enabled) return
        if (!enabled) {
            appliedQuality = config.defaultQuality
            appliedIntervalMs = defaultIntervalMs
        }
        publishState(force = true)
        Log.d(TAG, "Adaptive bitrate ${if (enabled) "enabled" else "disabled"}")
    }

    fun setDefaultFrameRate(fps: Int) {
        val clampedFps = fps.coerceIn(config.minFps, config.maxFps)
        val interval = 1000L / clampedFps
        if (defaultIntervalMs == interval) return
        defaultIntervalMs = interval
        if (!isEnabled.get()) {
            appliedIntervalMs = interval
        }
        publishState(force = true)
    }

    override fun getAdaptiveQuality(baseQuality: Int, thermalAdjustedQuality: Int): Int {
        val applied = if (!isEnabled.get()) {
            thermalAdjustedQuality
        } else {
            networkMonitor.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)
        }
        appliedQuality = applied
        publishState()
        return applied
    }

    override fun getAdaptiveFrameInterval(baseIntervalMs: Long, thermalAdjustedIntervalMs: Long): Long {
        val applied = if (!isEnabled.get()) {
            thermalAdjustedIntervalMs
        } else {
            networkMonitor.getAdaptiveFrameInterval(baseIntervalMs, thermalAdjustedIntervalMs)
        }
        appliedIntervalMs = applied
        publishState()
        return applied
    }

    private fun publishState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < STATE_PUBLISH_INTERVAL_MS) return
        lastPublishMs = now
        if (appliedQuality != publishedQuality || appliedIntervalMs != publishedIntervalMs) {
            adjustmentCount.incrementAndGet()
            publishedQuality = appliedQuality
            publishedIntervalMs = appliedIntervalMs
        }
        _state.value = buildState()
    }

    private fun buildState(): AdaptiveState {
        val activeClients = networkMonitor.activeClients
        return AdaptiveState(
            enabled = isEnabled.get(),
            qualityLevel = networkMonitor.getNetworkQualityLevel(),
            currentQuality = appliedQuality,
            targetQuality = config.defaultQuality,
            currentFps = if (appliedIntervalMs > 0) (1000f / appliedIntervalMs).toInt() else 0,
            targetFps = if (defaultIntervalMs > 0) (1000f / defaultIntervalMs).toInt() else 0,
            // Measured aggregate client throughput — 0 until a client is
            // actually sending frames. Never invent a bandwidth number.
            estimatedBandwidthKbps = networkMonitor.getMeasuredBandwidthKbps(),
            minClientThroughputKbps = if (activeClients > 0) networkMonitor.getMinClientThroughputKbps() else 0,
            activeClients = activeClients,
            adjustmentCount = adjustmentCount.get(),
        )
    }

    data class AdaptiveState(
        val enabled: Boolean,
        val qualityLevel: NetworkQualityMonitor.NetworkQualityLevel,
        val currentQuality: Int,
        val targetQuality: Int,
        val currentFps: Int,
        val targetFps: Int,
        val estimatedBandwidthKbps: Int,
        val minClientThroughputKbps: Int,
        val activeClients: Int,
        val adjustmentCount: Int = 0,
    )

    data class AdaptiveBitrateConfig(
        val enabledByDefault: Boolean = false,
        val defaultQuality: Int = StreamDefaults.JPEG_QUALITY,
        val minQuality: Int = StreamDefaults.ADAPTIVE_JPEG_QUALITY_MIN,
        val maxQuality: Int = StreamDefaults.ADAPTIVE_JPEG_QUALITY_MAX,
        val defaultFrameIntervalMs: Long = 1000L / StreamDefaults.STREAM_FPS,
        val minFps: Int = StreamDefaults.ADAPTIVE_FPS_MIN,
        val maxFps: Int = StreamDefaults.ADAPTIVE_FPS_MAX,
    )

    companion object {
        private const val TAG = "AdaptiveBitrate"
        private const val STATE_PUBLISH_INTERVAL_MS = 250L
    }
}

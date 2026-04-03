package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AdaptiveBitrateController(
    private val networkMonitor: NetworkQualityMonitor,
    private val config: AdaptiveBitrateConfig = AdaptiveBitrateConfig(),
) {

    private val _isEnabled = AtomicBoolean(config.enabledByDefault)
    val isEnabled: Boolean get() = _isEnabled.get()

    private val _currentQuality = AtomicInteger(config.defaultQuality)
    val currentQuality: Int get() = _currentQuality.get()

    private val _currentFrameIntervalMs = AtomicLong(config.defaultFrameIntervalMs)
    val currentFrameIntervalMs: Long get() = _currentFrameIntervalMs.get()

    private val _adjustmentCount = AtomicInteger(0)
    val adjustmentCount: Int get() = _adjustmentCount.get()

    private val _qualityLevel = MutableStateFlow(NetworkQualityLevel.GOOD)
    val qualityLevel: StateFlow<NetworkQualityLevel> = _qualityLevel.asStateFlow()

    private var lastAdjustmentTime = 0L
    private var lastAppliedQuality = config.defaultQuality
    private var lastAppliedInterval = config.defaultFrameIntervalMs

    private val _state = MutableStateFlow(AdaptiveState(
        enabled = config.enabledByDefault,
        qualityLevel = NetworkQualityLevel.GOOD,
        currentQuality = config.defaultQuality,
        targetQuality = config.defaultQuality,
        currentFps = (1000f / config.defaultFrameIntervalMs).toInt(),
        targetFps = (1000f / config.defaultFrameIntervalMs).toInt(),
        estimatedBandwidthKbps = networkMonitor.estimatedBandwidthKbps,
        minClientThroughputKbps = networkMonitor.getMinClientThroughputKbps(),
        activeClients = networkMonitor.activeClients,
    ))
    val state: StateFlow<AdaptiveState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _isEnabled.set(enabled)
        if (!enabled) {
            _currentQuality.set(config.defaultQuality)
            _currentFrameIntervalMs.set(config.defaultFrameIntervalMs)
            lastAppliedQuality = config.defaultQuality
            lastAppliedInterval = config.defaultFrameIntervalMs
            Log.d(TAG, "Adaptive bitrate disabled, restored defaults")
        } else {
            evaluate()
        }
        updateState()
    }

    fun setDefaultQuality(quality: Int) {
        val clamped = quality.coerceIn(config.minQuality, config.maxQuality)
        _currentQuality.set(clamped)
        lastAppliedQuality = clamped
    }

    fun setDefaultFrameRate(fps: Int) {
        val clampedFps = fps.coerceIn(config.minFps, config.maxFps)
        val interval = (1000L / clampedFps)
        _currentFrameIntervalMs.set(interval)
        lastAppliedInterval = interval
    }

    fun evaluate(): AdaptiveResult {
        if (!_isEnabled.get()) {
            return AdaptiveResult(
                quality = config.defaultQuality,
                frameIntervalMs = config.defaultFrameIntervalMs,
                adjusted = false,
                reason = "disabled",
            )
        }

        val now = System.currentTimeMillis()
        val elapsedSinceLastAdjustment = now - lastAdjustmentTime

        if (elapsedSinceLastAdjustment < config.minAdjustmentIntervalMs) {
            return AdaptiveResult(
                quality = lastAppliedQuality,
                frameIntervalMs = lastAppliedInterval,
                adjusted = false,
                reason = "cooldown",
            )
        }

        val qualityLevel = networkMonitor.getNetworkQualityLevel()
        _qualityLevel.value = qualityLevel

        val activeClients = networkMonitor.activeClients

        if (activeClients == 0) {
            return AdaptiveResult(
                quality = config.defaultQuality,
                frameIntervalMs = config.defaultFrameIntervalMs,
                adjusted = false,
                reason = "no_clients",
            )
        }

        val baseQuality = _currentQuality.get()
        val baseInterval = _currentFrameIntervalMs.get()

        val thermalAdjustedQuality = baseQuality
        val thermalAdjustedInterval = baseInterval

        val adaptedQuality = networkMonitor.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)
        val adaptedInterval = networkMonitor.getAdaptiveFrameInterval(baseInterval, thermalAdjustedInterval)

        val qualityDelta = adaptedQuality - lastAppliedQuality
        val intervalDelta = adaptedInterval - lastAppliedInterval

        val qualityChanged = kotlin.math.abs(qualityDelta) >= config.minQualityChangeThreshold
        val intervalChanged = kotlin.math.abs(intervalDelta) >= config.minIntervalChangeThresholdMs

        if (!qualityChanged && !intervalChanged) {
            return AdaptiveResult(
                quality = lastAppliedQuality,
                frameIntervalMs = lastAppliedInterval,
                adjusted = false,
                reason = "stable",
            )
        }

        val finalQuality = if (qualityChanged) adaptedQuality else lastAppliedQuality
        val finalInterval = if (intervalChanged) adaptedInterval else lastAppliedInterval

        lastAppliedQuality = finalQuality
        lastAppliedInterval = finalInterval
        lastAdjustmentTime = now
        _adjustmentCount.incrementAndGet()

        val reason = buildString {
            if (qualityChanged) append("quality=${lastAppliedQuality}->$finalQuality")
            if (qualityChanged && intervalChanged) append(", ")
            if (intervalChanged) {
                val oldFps = 1000 / lastAppliedInterval
                val newFps = 1000 / finalInterval
                append("fps=$oldFps->$newFps")
            }
            append(" [${qualityLevel.name}]")
        }

        Log.d(TAG, "Adaptive: $reason")

        updateState()

        return AdaptiveResult(
            quality = finalQuality,
            frameIntervalMs = finalInterval,
            adjusted = true,
            reason = reason,
        )
    }

    fun getAdaptiveQuality(baseQuality: Int, thermalAdjustedQuality: Int): Int {
        if (!_isEnabled.get()) return thermalAdjustedQuality
        return networkMonitor.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)
    }

    fun getAdaptiveFrameInterval(baseIntervalMs: Long, thermalAdjustedIntervalMs: Long): Long {
        if (!_isEnabled.get()) return thermalAdjustedIntervalMs
        return networkMonitor.getAdaptiveFrameInterval(baseIntervalMs, thermalAdjustedIntervalMs)
    }

    fun reset() {
        _currentQuality.set(config.defaultQuality)
        _currentFrameIntervalMs.set(config.defaultFrameIntervalMs)
        lastAppliedQuality = config.defaultQuality
        lastAppliedInterval = config.defaultFrameIntervalMs
        lastAdjustmentTime = 0L
        _adjustmentCount.set(0)
        _qualityLevel.value = NetworkQualityLevel.GOOD
        updateState()
    }

    private fun updateState() {
        val quality = if (_isEnabled.get()) lastAppliedQuality else config.defaultQuality
        val interval = if (_isEnabled.get()) lastAppliedInterval else config.defaultFrameIntervalMs
        _state.value = AdaptiveState(
            enabled = _isEnabled.get(),
            qualityLevel = _qualityLevel.value,
            currentQuality = quality,
            targetQuality = config.defaultQuality,
            currentFps = if (interval > 0) (1000f / interval).toInt() else 0,
            targetFps = if (config.defaultFrameIntervalMs > 0) (1000f / config.defaultFrameIntervalMs).toInt() else 0,
            estimatedBandwidthKbps = networkMonitor.estimatedBandwidthKbps,
            minClientThroughputKbps = networkMonitor.getMinClientThroughputKbps(),
            activeClients = networkMonitor.activeClients,
            adjustmentCount = _adjustmentCount.get(),
        )
    }

    data class AdaptiveResult(
        val quality: Int,
        val frameIntervalMs: Long,
        val adjusted: Boolean,
        val reason: String,
    )

    data class AdaptiveState(
        val enabled: Boolean,
        val qualityLevel: NetworkQualityLevel,
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
        val defaultQuality: Int = 70,
        val minQuality: Int = 15,
        val maxQuality: Int = 95,
        val defaultFrameIntervalMs: Long = 1000L / 24,
        val minFps: Int = 3,
        val maxFps: Int = 30,
        val minAdjustmentIntervalMs: Long = 3000L,
        val minQualityChangeThreshold: Int = 3,
        val minIntervalChangeThresholdMs: Long = 10L,
    )

    companion object {
        private const val TAG = "AdaptiveBitrate"
    }
}

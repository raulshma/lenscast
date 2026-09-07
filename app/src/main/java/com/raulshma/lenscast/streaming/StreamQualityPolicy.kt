package com.raulshma.lenscast.streaming

/**
 * Sensor reading seams for the quality policy. The production adapters are
 * [com.raulshma.lenscast.core.ThermalMonitor] and
 * [AdaptiveBitrateController] — both already carry these exact methods —
 * and tests provide fakes, which is what makes the seams real.
 */
interface ThermalAdjustmentSource {
    fun getAdjustedQuality(baseQuality: Int): Int
    fun getAdjustedFrameDelay(baseIntervalMs: Long): Long
}

interface NetworkAdjustmentSource {
    fun getAdaptiveQuality(baseQuality: Int, thermalAdjustedQuality: Int): Int
    fun getAdaptiveFrameInterval(baseIntervalMs: Long, thermalAdjustedIntervalMs: Long): Long
}

/**
 * The single quality-resolution order for the frame path: the battery
 * suggestion arrives as the base (see `applyBatteryOptimization`), thermal
 * clamps it, and the network ladder scales it. The frame path resolves the
 * interval first and the quality only for frames that pass the throttle —
 * the adaptive module's driven-state publishing must observe exactly the
 * frames that flow, never dropped ones. Call order inside each function
 * matches the historical sensor sequence.
 */
class StreamQualityPolicy(
    private val thermal: ThermalAdjustmentSource,
    private val network: NetworkAdjustmentSource,
) {

    fun resolveInterval(baseIntervalMs: Long): Long {
        val thermalInterval = thermal.getAdjustedFrameDelay(baseIntervalMs)
        return network.getAdaptiveFrameInterval(baseIntervalMs, thermalInterval)
    }

    fun resolveQuality(baseQuality: Int): Int {
        val thermalQuality = thermal.getAdjustedQuality(baseQuality)
        return network.getAdaptiveQuality(baseQuality, thermalQuality)
    }
}

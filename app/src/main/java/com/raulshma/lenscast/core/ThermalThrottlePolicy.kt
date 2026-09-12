package com.raulshma.lenscast.core

/**
 * Pure thermal-state → streaming-quality ladder: each thermal tier trades
 * frame rate and JPEG quality for heat, and only the CRITICAL tier pauses
 * the stream. [ThermalMonitor] keeps the PowerManager listener and status
 * reads and delegates every decision here; the tiers are JVM-tested.
 */
object ThermalThrottlePolicy {

    // The ladder's quality tiers. They are this policy's own knowledge, not
    // cross-module config — kept here instead of StreamDefaults, mirroring
    // BatteryQualityPolicy. The NORMAL tier restores the StreamDefaults
    // baseline rather than a policy-local constant, so a settings change to
    // the default JPEG quality follows the ladder back down.
    const val LIGHT_FRAME_RATE_MULTIPLIER = 0.9f
    const val LIGHT_JPEG_QUALITY = 60
    const val MODERATE_FRAME_RATE_MULTIPLIER = 0.7f
    const val MODERATE_JPEG_QUALITY = 55
    const val SEVERE_FRAME_RATE_MULTIPLIER = 0.5f
    const val SEVERE_JPEG_QUALITY = 40
    const val CRITICAL_FRAME_RATE_MULTIPLIER = 0.0f
    const val CRITICAL_JPEG_QUALITY = 20

    fun resolve(state: ThermalState): ThermalThrottlingResult {
        return when (state) {
            ThermalState.NORMAL -> ThermalThrottlingResult(
                frameRateMultiplier = 1.0f,
                jpegQuality = StreamDefaults.JPEG_QUALITY,
                shouldPause = false,
            )
            ThermalState.LIGHT -> ThermalThrottlingResult(
                frameRateMultiplier = LIGHT_FRAME_RATE_MULTIPLIER,
                jpegQuality = LIGHT_JPEG_QUALITY,
                shouldPause = false,
            )
            ThermalState.MODERATE -> ThermalThrottlingResult(
                frameRateMultiplier = MODERATE_FRAME_RATE_MULTIPLIER,
                jpegQuality = MODERATE_JPEG_QUALITY,
                shouldPause = false,
            )
            ThermalState.SEVERE -> ThermalThrottlingResult(
                frameRateMultiplier = SEVERE_FRAME_RATE_MULTIPLIER,
                jpegQuality = SEVERE_JPEG_QUALITY,
                shouldPause = false,
            )
            ThermalState.CRITICAL -> ThermalThrottlingResult(
                frameRateMultiplier = CRITICAL_FRAME_RATE_MULTIPLIER,
                jpegQuality = CRITICAL_JPEG_QUALITY,
                shouldPause = true,
            )
        }
    }
}

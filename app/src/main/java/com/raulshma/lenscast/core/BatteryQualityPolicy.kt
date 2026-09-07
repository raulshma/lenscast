package com.raulshma.lenscast.core

/**
 * Pure battery → streaming-quality ladder: charging wins over doze, doze
 * wins over low battery, power save shares the balanced tier. [PowerManager]
 * keeps the receivers, wake lock, and battery reads and delegates every
 * decision here; the thresholds and user-facing messages are JVM-tested.
 */
object BatteryQualityPolicy {

    // The ladder's quality tiers. They are this policy's own knowledge, not
    // cross-module config — kept here instead of StreamDefaults.
    const val DOZE_QUALITY = 40
    const val CRITICAL_BATTERY_QUALITY = 50
    const val LOW_BATTERY_QUALITY = 60
    const val BATTERY_SAVER_QUALITY = 65
    const val CRITICAL_BATTERY_LEVEL_PCT = 15
    const val LOW_BATTERY_LEVEL_PCT = 30
    const val BATTERY_SAVER_LEVEL_PCT = 50

    fun resolve(
        batteryLevel: Int,
        isPowerSave: Boolean,
        isCharging: Boolean,
        inDoze: Boolean,
    ): BatteryOptimizationResult {
        return when {
            isCharging -> BatteryOptimizationResult(
                suggestedJpegQuality = StreamDefaults.JPEG_QUALITY,
                batteryLevel = batteryLevel,
                isPowerSaveMode = isPowerSave,
                message = "Charging - full quality"
            )
            inDoze -> BatteryOptimizationResult(
                suggestedJpegQuality = DOZE_QUALITY,
                batteryLevel = batteryLevel,
                isPowerSaveMode = true,
                message = "Doze mode - minimal quality"
            )
            batteryLevel < CRITICAL_BATTERY_LEVEL_PCT -> BatteryOptimizationResult(
                suggestedJpegQuality = CRITICAL_BATTERY_QUALITY,
                batteryLevel = batteryLevel,
                isPowerSaveMode = isPowerSave,
                message = "Critical battery - minimal quality"
            )
            batteryLevel < LOW_BATTERY_LEVEL_PCT -> BatteryOptimizationResult(
                suggestedJpegQuality = LOW_BATTERY_QUALITY,
                batteryLevel = batteryLevel,
                isPowerSaveMode = isPowerSave,
                message = "Low battery - reduced quality"
            )
            batteryLevel < BATTERY_SAVER_LEVEL_PCT || isPowerSave -> BatteryOptimizationResult(
                suggestedJpegQuality = BATTERY_SAVER_QUALITY,
                batteryLevel = batteryLevel,
                isPowerSaveMode = isPowerSave,
                message = "Battery saver - balanced quality"
            )
            else -> BatteryOptimizationResult(
                suggestedJpegQuality = StreamDefaults.JPEG_QUALITY,
                batteryLevel = batteryLevel,
                isPowerSaveMode = isPowerSave,
                message = "Normal operation"
            )
        }
    }
}

package com.raulshma.lenscast.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager as AndroidPowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryOptimizationResult(
    val suggestedBitrate: Int,
    val suggestedFrameRate: Int,
    val suggestedResolution: String,
    val suggestedJpegQuality: Int,
    val batteryLevel: Int,
    val isPowerSaveMode: Boolean,
    val message: String,
)

class PowerManager(private val context: Context) {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as AndroidPowerManager
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager

    private var wakeLock: AndroidPowerManager.WakeLock? = null

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isPowerSaveMode = MutableStateFlow(false)
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _optimizationResult = MutableStateFlow(
        BatteryOptimizationResult(
            suggestedBitrate = 2_000_000,
            suggestedFrameRate = 24,
            suggestedResolution = "FHD_1080P",
            suggestedJpegQuality = 70,
            batteryLevel = 100,
            isPowerSaveMode = false,
            message = "Normal operation"
        )
    )
    val optimizationResult: StateFlow<BatteryOptimizationResult> = _optimizationResult.asStateFlow()

    @SuppressLint("WakelockTimeout")
    fun acquireWakeLock(tag: String = "LensCast") {
        try {
            releaseWakeLock()
            wakeLock = powerManager.newWakeLock(
                AndroidPowerManager.PARTIAL_WAKE_LOCK, "$tag::Partial"
            ).apply { acquire() }
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun requestIgnoreBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot request battery optimization exemption", e)
                    try {
                        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun refreshBatteryState() {
        val level = batteryManager.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        )
        val powerSave = powerManager.isPowerSaveMode
        val charging = batteryManager.isCharging

        _batteryLevel.value = level
        _isPowerSaveMode.value = powerSave
        _isCharging.value = charging

        _optimizationResult.value = when {
            charging -> BatteryOptimizationResult(
                suggestedBitrate = 2_000_000,
                suggestedFrameRate = 24,
                suggestedResolution = "FHD_1080P",
                suggestedJpegQuality = 70,
                batteryLevel = level,
                isPowerSaveMode = powerSave,
                message = "Charging - full quality"
            )
            level < 15 -> BatteryOptimizationResult(
                suggestedBitrate = 500_000,
                suggestedFrameRate = 15,
                suggestedResolution = "SD_480P",
                suggestedJpegQuality = 50,
                batteryLevel = level,
                isPowerSaveMode = powerSave,
                message = "Critical battery - minimal quality"
            )
            level < 30 -> BatteryOptimizationResult(
                suggestedBitrate = 800_000,
                suggestedFrameRate = 20,
                suggestedResolution = "HD_720P",
                suggestedJpegQuality = 60,
                batteryLevel = level,
                isPowerSaveMode = powerSave,
                message = "Low battery - reduced quality"
            )
            level < 50 || powerSave -> BatteryOptimizationResult(
                suggestedBitrate = 1_000_000,
                suggestedFrameRate = 20,
                suggestedResolution = "HD_720P",
                suggestedJpegQuality = 65,
                batteryLevel = level,
                isPowerSaveMode = powerSave,
                message = "Battery saver - balanced quality"
            )
            else -> BatteryOptimizationResult(
                suggestedBitrate = 2_000_000,
                suggestedFrameRate = 24,
                suggestedResolution = "FHD_1080P",
                suggestedJpegQuality = 70,
                batteryLevel = level,
                isPowerSaveMode = powerSave,
                message = "Normal operation"
            )
        }
    }

    companion object {
        private const val TAG = "PowerManager"
    }
}

package com.raulshma.lenscast.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    @Volatile private var wakeLockAcquired = false

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

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    _isCharging.value = true
                    refreshOptimizationResult()
                    Log.d(TAG, "Power connected - restoring full quality")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    _isCharging.value = false
                    refreshOptimizationResult()
                    Log.d(TAG, "Power disconnected - applying battery optimization")
                }
                Intent.ACTION_BATTERY_LOW -> {
                    refreshBatteryState()
                    Log.d(TAG, "Battery low warning received")
                }
                Intent.ACTION_BATTERY_OKAY -> {
                    refreshBatteryState()
                    Log.d(TAG, "Battery okay received")
                }
            }
        }
    }

    private var receiverRegistered = false

    init {
        registerReceivers()
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(powerSaveReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register power receivers", e)
        }
    }

    @SuppressLint("WakelockTimeout")
    fun acquireWakeLock(tag: String = "LensCast") {
        if (wakeLockAcquired) return
        try {
            wakeLock = powerManager.newWakeLock(
                AndroidPowerManager.PARTIAL_WAKE_LOCK, "$tag::Partial"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            wakeLockAcquired = true
            Log.d(TAG, "Wake lock acquired with timeout ${WAKELOCK_TIMEOUT_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    fun releaseWakeLock() {
        if (!wakeLockAcquired) return
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wake lock already released by timeout", e)
            }
        }
        wakeLock = null
        wakeLockAcquired = false
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

    fun isDeviceInDozeMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
    }

    fun requestDozeModeWhitelist(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request doze mode whitelist", e)
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

        refreshOptimizationResult()
    }

    private fun refreshOptimizationResult() {
        val level = _batteryLevel.value
        val powerSave = _isPowerSaveMode.value
        val charging = _isCharging.value
        val inDoze = isDeviceInDozeMode()

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
            inDoze -> BatteryOptimizationResult(
                suggestedBitrate = 500_000,
                suggestedFrameRate = 10,
                suggestedResolution = "SD_480P",
                suggestedJpegQuality = 40,
                batteryLevel = level,
                isPowerSaveMode = true,
                message = "Doze mode - minimal quality"
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

    fun release() {
        releaseWakeLock()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(powerSaveReceiver)
                receiverRegistered = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister power receiver", e)
            }
        }
    }

    companion object {
        private const val TAG = "PowerManager"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}

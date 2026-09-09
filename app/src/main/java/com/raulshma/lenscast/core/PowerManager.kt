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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryOptimizationResult(
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

    // Null until the first battery read — the event payload omits the field
    // for an unknown level rather than reporting a fabricated percent.
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _isPowerSaveMode = MutableStateFlow(false)
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    // A buffered SharedFlow, not a StateFlow: StateFlow conflates, so a
    // rapid connect→disconnect flip (flappy connector) would collapse to no
    // net change and drop the tamper edge TamperMonitor exists to catch.
    // Replay hands late collectors the current state; DROP_OLDEST keeps
    // tryEmit (unusable-suspending from onReceive) infallible — a failed
    // emit would drop the edge for every collector AND the replay cache.
    private val _isCharging = MutableSharedFlow<Boolean>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val isCharging: SharedFlow<Boolean> = _isCharging.asSharedFlow()

    /** Synchronous charging read for status snapshots; collectors use [isCharging]. */
    fun isChargingNow(): Boolean = _isCharging.replayCache.firstOrNull() ?: false

    private val _optimizationResult = MutableStateFlow(
        BatteryOptimizationResult(
            suggestedJpegQuality = StreamDefaults.JPEG_QUALITY,
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
                    _isCharging.tryEmit(true)
                    refreshOptimizationResult()
                    Log.d(TAG, "Power connected - restoring full quality")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    _isCharging.tryEmit(false)
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
        refreshBatteryState()
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
        // getIntProperty reports Integer.MIN_VALUE when the level is unknown
        // (no battery / not yet read) — surfaced as null, not a fake percent.
        val level = batteryManager.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        ).takeIf { it != Integer.MIN_VALUE }
        val powerSave = powerManager.isPowerSaveMode
        val charging = batteryManager.isCharging

        _batteryLevel.value = level
        _isPowerSaveMode.value = powerSave
        _isCharging.tryEmit(charging)

        refreshOptimizationResult()
    }

    private fun refreshOptimizationResult() {
        _optimizationResult.value = BatteryQualityPolicy.resolve(
            batteryLevel = _batteryLevel.value ?: UNKNOWN_BATTERY_FALLBACK_PERCENT,
            isPowerSave = _isPowerSaveMode.value,
            isCharging = isChargingNow(),
            inDoze = isDeviceInDozeMode(),
        )
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

        /**
         * The percent a non-null surface reads while the real level is still
         * unknown: the battery-quality policy and the status DTO keep their
         * healthy default — a missing reading must not read as a critical
         * battery. The event payload is not a non-null surface; it omits the
         * field instead.
         */
        const val UNKNOWN_BATTERY_FALLBACK_PERCENT = 100
    }
}

package com.raulshma.lenscast.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThermalMonitor(private val context: Context) {

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _throttlingResult = MutableStateFlow(ThermalThrottlingResult(
        bitrateMultiplier = 1.0f,
        frameRateMultiplier = 1.0f,
        jpegQuality = 70,
        shouldPause = false,
    ))
    val throttlingResult: StateFlow<ThermalThrottlingResult> = _throttlingResult.asStateFlow()

    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private var consecutiveElevatedReadings = 0
    private var lastThermalCheckTime = 0L

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 30) {
                val initialStatus = getThermalStatus(pm)
                val initialState = thermalStatusToState(initialStatus)
                _thermalState.value = initialState
                _throttlingResult.value = applyThermalThrottling(initialState)
            }

            listener = PowerManager.OnThermalStatusChangedListener { status ->
                val state = thermalStatusToState(status)
                _thermalState.value = state
                _throttlingResult.value = applyThermalThrottling(state)
                Log.d(TAG, "Thermal state changed: $state")
            }
            pm.addThermalStatusListener(listener!!)
        }
        Log.d(TAG, "Thermal monitoring started")
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.removeThermalStatusListener(listener!!)
            listener = null
        }
        consecutiveElevatedReadings = 0
        _thermalState.value = ThermalState.NORMAL
        _throttlingResult.value = ThermalThrottlingResult(
            bitrateMultiplier = 1.0f,
            frameRateMultiplier = 1.0f,
            jpegQuality = 70,
            shouldPause = false,
        )
        Log.d(TAG, "Thermal monitoring stopped")
    }

    fun isDeviceOverheating(): Boolean {
        return _thermalState.value == ThermalState.SEVERE || _thermalState.value == ThermalState.CRITICAL
    }

    fun shouldReduceQualityProactively(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastThermalCheckTime < PROACTIVE_CHECK_INTERVAL_MS) {
            return _throttlingResult.value.bitrateMultiplier < 1.0f
        }
        lastThermalCheckTime = now

        if (Build.VERSION.SDK_INT >= 30) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = getThermalStatus(pm)
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                consecutiveElevatedReadings++
            } else {
                consecutiveElevatedReadings = 0
            }
            return consecutiveElevatedReadings >= PROACTIVE_THRESHOLD
        }
        return false
    }

    @Suppress("DiscouragedPrivateApi")
    private fun getThermalStatus(pm: PowerManager): Int {
        return try {
            val method = pm.javaClass.getMethod("getThermalStatus")
            method.invoke(pm) as Int
        } catch (e: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    private fun thermalStatusToState(status: Int): ThermalState {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalState.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
            else -> ThermalState.NORMAL
        }
    }

    private fun applyThermalThrottling(state: ThermalState): ThermalThrottlingResult {
        return when (state) {
            ThermalState.NORMAL -> ThermalThrottlingResult(
                bitrateMultiplier = 1.0f,
                frameRateMultiplier = 1.0f,
                jpegQuality = 70,
                shouldPause = false,
            )
            ThermalState.LIGHT -> ThermalThrottlingResult(
                bitrateMultiplier = 0.9f,
                frameRateMultiplier = 0.9f,
                jpegQuality = 60,
                shouldPause = false,
            )
            ThermalState.MODERATE -> ThermalThrottlingResult(
                bitrateMultiplier = 0.7f,
                frameRateMultiplier = 0.7f,
                jpegQuality = 55,
                shouldPause = false,
            )
            ThermalState.SEVERE -> ThermalThrottlingResult(
                bitrateMultiplier = 0.5f,
                frameRateMultiplier = 0.5f,
                jpegQuality = 40,
                shouldPause = false,
            )
            ThermalState.CRITICAL -> ThermalThrottlingResult(
                bitrateMultiplier = 0.0f,
                frameRateMultiplier = 0.0f,
                jpegQuality = 20,
                shouldPause = true,
            )
        }
    }

    fun getAdjustedQuality(baseQuality: Int): Int {
        return _throttlingResult.value.jpegQuality
            .coerceIn(10, baseQuality)
    }

    fun getAdjustedFrameDelay(baseIntervalMs: Long): Long {
        val multiplier = _throttlingResult.value.frameRateMultiplier
        return if (multiplier <= 0f) Long.MAX_VALUE
        else (baseIntervalMs / multiplier).toLong()
    }

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val PROACTIVE_CHECK_INTERVAL_MS = 10_000L
        private const val PROACTIVE_THRESHOLD = 2
    }
}

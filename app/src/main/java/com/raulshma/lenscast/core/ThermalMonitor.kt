package com.raulshma.lenscast.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.raulshma.lenscast.streaming.ThermalAdjustmentSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class ThermalMonitor(private val context: Context) : ThermalAdjustmentSource {

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _throttlingResult = MutableStateFlow(ThermalThrottlePolicy.resolve(ThermalState.NORMAL))
    val throttlingResult: StateFlow<ThermalThrottlingResult> = _throttlingResult.asStateFlow()

    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private val monitoring = AtomicBoolean(false)

    fun startMonitoring() {
        if (!monitoring.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            // Pre-API-30 there is no getCurrentThermalStatus() query — the
            // initial state stays NORMAL until the first listener callback
            // arrives (listener-only startup). Deliberate, unchanged
            // behavior; this class only reads status and delegates the
            // tier mapping to ThermalThrottlePolicy.
            if (Build.VERSION.SDK_INT >= 30) {
                val initialStatus = getThermalStatus(pm)
                val initialState = thermalStatusToState(initialStatus)
                _thermalState.value = initialState
                _throttlingResult.value = ThermalThrottlePolicy.resolve(initialState)
            }

            listener = PowerManager.OnThermalStatusChangedListener { status ->
                val state = thermalStatusToState(status)
                _thermalState.value = state
                _throttlingResult.value = ThermalThrottlePolicy.resolve(state)
                Log.d(TAG, "Thermal state changed: $state")
            }
            pm.addThermalStatusListener(listener!!)
        }
        Log.d(TAG, "Thermal monitoring started")
    }

    fun stopMonitoring() {
        if (!monitoring.compareAndSet(true, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.removeThermalStatusListener(listener!!)
            listener = null
        }
        _thermalState.value = ThermalState.NORMAL
        _throttlingResult.value = ThermalThrottlePolicy.resolve(ThermalState.NORMAL)
        Log.d(TAG, "Thermal monitoring stopped")
    }

    private fun getThermalStatus(pm: PowerManager): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pm.currentThermalStatus
            } else {
                PowerManager.THERMAL_STATUS_NONE
            }
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

    // The thermal status → quality-tier mapping lives in the pure, JVM-tested
    // ThermalThrottlePolicy (mirroring BatteryQualityPolicy's split); this
    // class only translates framework status codes into ThermalState.

    override fun getAdjustedQuality(baseQuality: Int): Int {
        return _throttlingResult.value.jpegQuality
            .coerceIn(StreamDefaults.JPEG_QUALITY_MIN, baseQuality)
    }

    override fun getAdjustedFrameDelay(baseIntervalMs: Long): Long {
        val multiplier = _throttlingResult.value.frameRateMultiplier
        return if (multiplier <= 0f) Long.MAX_VALUE
        else (baseIntervalMs / multiplier).toLong()
    }

    companion object {
        private const val TAG = "ThermalMonitor"
    }
}

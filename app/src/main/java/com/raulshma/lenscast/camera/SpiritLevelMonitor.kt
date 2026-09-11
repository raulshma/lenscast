package com.raulshma.lenscast.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.raulshma.lenscast.camera.model.LevelIndicatorPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The spirit level's runtime: registers the accelerometer on the sensor
 * manager while BOTH the setting is enabled and the camera screen's preview
 * is active, and publishes the policy's rendering verdict per reading. All
 * thresholds and the level math live in the pure [LevelIndicatorPolicy];
 * this owns only the lifecycle-aware listener plumbing.
 */
class SpiritLevelMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _lineState =
        MutableStateFlow<LevelIndicatorPolicy.LevelLineState?>(null)

    /** The overlay's current verdict; null while unregistered (nothing to draw). */
    val lineState: StateFlow<LevelIndicatorPolicy.LevelLineState?> = _lineState.asStateFlow()

    private var settingEnabled = false
    private var previewActive = false
    private var registered = false

    /** The persisted toggle — (de)registers as the combined gate flips. */
    fun setEnabled(enabled: Boolean) {
        settingEnabled = enabled
        syncRegistration()
    }

    /** The preview lifecycle gate — sensors run only while the viewfinder is shown. */
    fun setPreviewActive(active: Boolean) {
        previewActive = active
        syncRegistration()
    }

    private fun syncRegistration() {
        val shouldRegister = settingEnabled && previewActive
        val manager = sensorManager ?: return
        if (shouldRegister == registered) return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (shouldRegister) {
            manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            registered = true
        } else {
            manager.unregisterListener(this)
            registered = false
            // No stale level line after the sensors stop.
            _lineState.value = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        _lineState.value = LevelIndicatorPolicy.lineState(
            LevelIndicatorPolicy.Acceleration(event.values[0], event.values[1], event.values[2])
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

package com.raulshma.lenscast.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
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
 *
 * There is no stored "registered" mirror of the two gates — the only
 * registration fact is [registration], the listener handle itself; every
 * input change recomputes the gate verdict fresh and registers or unregisters
 * against that handle accordingly.
 */
class SpiritLevelMonitor(context: Context) : SensorEventListener {

    /**
     * The one registration fact. [Registration.Attached] holds the sensor the
     * listener is on; [Registration.NoSensor] is the stable no-sensor verdict
     * (see [syncRegistration]); [Registration.Detached] is nothing listening.
     */
    private sealed interface Registration {
        data class Attached(val sensor: Sensor) : Registration
        data object NoSensor : Registration
        data object Detached : Registration
    }

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _lineState =
        MutableStateFlow<LevelIndicatorPolicy.LevelLineState?>(null)

    /** The overlay's current verdict; null while unregistered (nothing to draw). */
    val lineState: StateFlow<LevelIndicatorPolicy.LevelLineState?> = _lineState.asStateFlow()

    private var settingEnabled = false
    private var previewActive = false
    private var registration: Registration = Registration.Detached

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
        val manager = sensorManager ?: return
        val shouldRegister = settingEnabled && previewActive
        when (val current = registration) {
            is Registration.Attached -> {
                if (!shouldRegister) {
                    manager.unregisterListener(this)
                    registration = Registration.Detached
                    // No stale level line after the sensors stop.
                    _lineState.value = null
                }
            }
            Registration.NoSensor -> {
                // Stable no-op: this device has no accelerometer, and no
                // input change can conjure one. Never retried, never logged
                // twice — the gate flipping around it changes nothing.
            }
            Registration.Detached -> {
                if (!shouldRegister) return
                val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accelerometer == null) {
                    // Named case: no accelerometer on this device. The
                    // verdict is remembered (NoSensor) so each input change
                    // does not re-query the sensor manager for an answer
                    // that will stay null — the level overlay stays off
                    // until a fresh process.
                    Log.w(TAG, "Spirit level enabled but no accelerometer available; level overlay stays off")
                    registration = Registration.NoSensor
                    return
                }
                manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                registration = Registration.Attached(accelerometer)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        _lineState.value = LevelIndicatorPolicy.lineState(
            LevelIndicatorPolicy.Acceleration(event.values[0], event.values[1], event.values[2])
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "SpiritLevelMonitor"
    }
}

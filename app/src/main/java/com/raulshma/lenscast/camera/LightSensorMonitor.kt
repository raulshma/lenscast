package com.raulshma.lenscast.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.raulshma.lenscast.camera.model.LightSensorPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The ambient-light sensor's runtime: registers `TYPE_LIGHT` while any
 * demand gate is open — an automation rule that reads illuminance, an overlay
 * showing it while streaming or previewing, a dashboard client (SSE or a
 * status poll), or the automation editor — and publishes the policy's
 * smoothed integer reading. Sampling decisions live in the pure
 * [LightSensorPolicy]; this owns only the listener plumbing and the settle
 * timer that advances on-change sensors (`TYPE_LIGHT` delivers only on change,
 * so steady light after the initial event or a step drop needs scheduled ticks
 * to clear [LightSensorPolicy.DEBOUNCE_MS] and finish EMA convergence). The
 * last gate closing clears the published reading back to null together with
 * the smoother state, so a stale lux value never outlives its demand.
 *
 * [elapsedRealtime] is the monotonic clock the policy's debounce reads —
 * injectable so the JVM tests can walk a sample timeline.
 */
class LightSensorMonitor(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _illuminanceLux = MutableStateFlow<Int?>(null)
    val illuminanceLux: StateFlow<Int?> = _illuminanceLux.asStateFlow()

    val sensorAvailable: Boolean
        get() = lightSensor != null

    private var automationDemand = false
    private var overlayEnabled = false
    private var streamingActive = false
    private var previewActive = false
    private var dashboardDemand = false
    private var editorOpen = false
    private var registered = false
    private var policyState = LightSensorPolicy.State()
    private var settleJob: Job? = null

    @Synchronized
    fun setAutomationDemand(active: Boolean) {
        automationDemand = active
        syncRegistration()
    }

    @Synchronized
    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        syncRegistration()
    }

    @Synchronized
    fun setStreamingActive(active: Boolean) {
        streamingActive = active
        syncRegistration()
    }

    @Synchronized
    fun setPreviewActive(active: Boolean) {
        previewActive = active
        syncRegistration()
    }

    @Synchronized
    fun setDashboardDemand(active: Boolean) {
        dashboardDemand = active
        syncRegistration()
    }

    /** The automation editor's open gate: its live lux readout needs a sample. */
    @Synchronized
    fun setEditorOpen(open: Boolean) {
        editorOpen = open
        syncRegistration()
    }

    @Synchronized
    private fun syncRegistration() {
        val manager = sensorManager
        val sensor = lightSensor
        val shouldRegister = manager != null && sensor != null &&
            (automationDemand || (overlayEnabled && (streamingActive || previewActive)) ||
                dashboardDemand || editorOpen)
        if (registered == shouldRegister) return
        if (shouldRegister) {
            registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (!registered) _illuminanceLux.value = null
        } else {
            manager?.unregisterListener(this)
            registered = false
            settleJob?.cancel()
            settleJob = null
            policyState = LightSensorPolicy.State()
            _illuminanceLux.value = null
        }
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT || event.values.isEmpty()) return
        applySample(LightSensorPolicy.sample(policyState, event.values[0].toDouble(), elapsedRealtime()))
    }

    @Synchronized
    private fun onSettleTick(job: Job) {
        if (!registered || settleJob !== job) return
        applySample(LightSensorPolicy.settle(policyState, elapsedRealtime()))
    }

    private fun applySample(sample: LightSensorPolicy.Sample) {
        policyState = sample.state
        if (sample.lux != null && sample.lux != _illuminanceLux.value) {
            _illuminanceLux.value = sample.lux
        }
        syncSettleJob()
    }

    private fun syncSettleJob() {
        settleJob?.cancel()
        settleJob = null
        if (!registered || !LightSensorPolicy.needsSettle(policyState)) return
        lateinit var launched: Job
        launched = scope.launch {
            delay(LightSensorPolicy.SETTLE_INTERVAL_MS)
            onSettleTick(launched)
        }
        settleJob = launched
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

package com.raulshma.lenscast.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LightSensorMonitorTest {

    private val context = mockk<Context>()
    private val sensorManager = mockk<SensorManager>()
    private val sensor = mockk<Sensor>()

    /** The monitor's injectable monotonic clock — the tests walk it by hand. */
    private var nowMs = 0L

    @Before
    fun setUp() {
        nowMs = 0L
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        every { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) } returns sensor
        every { sensor.type } returns Sensor.TYPE_LIGHT
        every {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
        } returns true
        every { sensorManager.unregisterListener(any<SensorEventListener>()) } just Runs
    }

    @Test
    fun `registers only while an independent demand gate is active`() {
        val monitor = monitor()

        monitor.setOverlayEnabled(true)
        verify(exactly = 0) {
            sensorManager.registerListener(any<SensorEventListener>(), sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        monitor.setStreamingActive(true)
        monitor.setStreamingActive(false)
        verify(exactly = 1) { sensorManager.unregisterListener(any<SensorEventListener>()) }

        monitor.setPreviewActive(true)
        monitor.setPreviewActive(false)
        verify(exactly = 2) {
            sensorManager.registerListener(any<SensorEventListener>(), sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        monitor.setAutomationDemand(true)
        monitor.setPreviewActive(true)
        monitor.setPreviewActive(false)
        verify(exactly = 3) {
            sensorManager.registerListener(any<SensorEventListener>(), sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        monitor.setAutomationDemand(false)

        monitor.setDashboardDemand(true)
        monitor.setDashboardDemand(false)
        verify(exactly = 4) {
            sensorManager.registerListener(any<SensorEventListener>(), sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        verify(exactly = 4) { sensorManager.unregisterListener(any<SensorEventListener>()) }

        monitor.setEditorOpen(true)
        monitor.setEditorOpen(false)
        verify(exactly = 5) {
            sensorManager.registerListener(any<SensorEventListener>(), sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        verify(exactly = 5) { sensorManager.unregisterListener(any<SensorEventListener>()) }
    }

    @Test
    fun `clears lux and smoothing state when the final demand gate closes`() {
        val monitor = monitor()
        monitor.setAutomationDemand(true)

        publish(monitor, 120f)
        assertEquals(120, monitor.illuminanceLux.value)

        monitor.setAutomationDemand(false)
        assertNull(monitor.illuminanceLux.value)

        monitor.setAutomationDemand(true)
        publish(monitor, 80f)
        assertEquals(80, monitor.illuminanceLux.value)
    }

    @Test
    fun `the first reading waits for the policy debounce`() {
        val monitor = monitor()
        monitor.setEditorOpen(true)

        // One sample only seeds the smoother's candidate, whatever it reads.
        monitor.onSensorChanged(lightEvent(0f))
        nowMs += 200
        assertNull(monitor.illuminanceLux.value)

        // The level holds through the debounce window and goes live.
        monitor.onSensorChanged(lightEvent(0f))
        nowMs += 200
        assertNull(monitor.illuminanceLux.value)
        monitor.onSensorChanged(lightEvent(0f))

        assertEquals(0, monitor.illuminanceLux.value)
    }

    @Test
    fun `reports an unavailable sensor without registering`() {
        every { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) } returns null
        val monitor = monitor()

        assertFalse(monitor.sensorAvailable)
        monitor.setAutomationDemand(true)
        monitor.setOverlayEnabled(true)
        monitor.setStreamingActive(true)
        monitor.setDashboardDemand(true)
        monitor.setEditorOpen(true)

        verify(exactly = 0) {
            sensorManager.registerListener(any<SensorEventListener>(), any(), SensorManager.SENSOR_DELAY_NORMAL)
        }
        assertNull(monitor.illuminanceLux.value)
    }

    @Test
    fun `reports an available sensor`() {
        assertTrue(monitor().sensorAvailable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on-change sensor with steady light publishes initial reading and step change via settle timer`() {
        val testScope = TestScope(StandardTestDispatcher())
        val monitor = LightSensorMonitor(
            context,
            elapsedRealtime = { testScope.testScheduler.currentTime },
            scope = testScope,
        )
        monitor.setEditorOpen(true)

        // Android TYPE_LIGHT is on-change: only ONE callback arrives in steady light.
        monitor.onSensorChanged(lightEvent(120f))
        testScope.testScheduler.runCurrent()
        assertNull(monitor.illuminanceLux.value)

        testScope.testScheduler.advanceTimeBy(400L)
        testScope.testScheduler.runCurrent()
        assertEquals(120, monitor.illuminanceLux.value)

        // Single step down to 5 lx in steady darkness converges without a second hardware callback.
        monitor.onSensorChanged(lightEvent(5f))
        testScope.testScheduler.advanceTimeBy(5_000L)
        testScope.testScheduler.runCurrent()
        assertEquals(5, monitor.illuminanceLux.value)

        // Closing the final gate cancels any pending settle work cleanly and clears the reading.
        monitor.onSensorChanged(lightEvent(300f))
        monitor.setEditorOpen(false)
        assertNull(monitor.illuminanceLux.value)
        testScope.testScheduler.advanceTimeBy(5_000L)
        testScope.testScheduler.runCurrent()
        assertNull(monitor.illuminanceLux.value)
    }

    private fun monitor() = LightSensorMonitor(
        context,
        elapsedRealtime = { nowMs },
        scope = TestScope(StandardTestDispatcher()),
    )

    /** Feed one steady level through the debounce window: three samples 200 ms apart. */
    private fun publish(monitor: LightSensorMonitor, lux: Float) {
        repeat(3) {
            monitor.onSensorChanged(lightEvent(lux))
            nowMs += 200
        }
    }

    /**
     * SensorEvent's fields are stamped by the native layer — there is no
     * stubbable getter for mockk to intercept. Mock the class (no
     * constructor runs) and plant the two fields the monitor reads:
     * `sensor` directly, `values` through its final-field reflective seam.
     */
    private fun lightEvent(lux: Float): SensorEvent {
        val event = mockk<SensorEvent>()
        SensorEvent::class.java.getField("sensor").set(event, sensor)
        val valuesField = SensorEvent::class.java.getField("values")
        valuesField.isAccessible = true
        valuesField.set(event, floatArrayOf(lux))
        return event
    }
}

package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.NetworkQualityMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateControllerTest {

    private fun enabledController(): Pair<AdaptiveBitrateController, NetworkQualityMonitor> {
        val monitor = NetworkQualityMonitor()
        val controller = AdaptiveBitrateController(monitor)
        controller.setEnabled(true)
        return controller to monitor
    }

    @Test
    fun `disabled controller passes quality through untouched`() {
        val monitor = NetworkQualityMonitor()
        val controller = AdaptiveBitrateController(monitor)
        assertEquals(60, controller.getAdaptiveQuality(baseQuality = 60, thermalAdjustedQuality = 60))
        val interval = controller.getAdaptiveFrameInterval(baseIntervalMs = 40, thermalAdjustedIntervalMs = 55)
        assertEquals(55L, interval)
    }

    @Test
    fun `state mirrors enabled flag`() {
        val monitor = NetworkQualityMonitor()
        val controller = AdaptiveBitrateController(monitor)
        assertFalse(controller.state.value.enabled)
        controller.setEnabled(true)
        assertTrue(controller.state.value.enabled)
    }

    @Test
    fun `poor network reduces quality but never below the adaptive floor`() {
        val (controller, monitor) = enabledController()
        // Single client sending slowly: 400 bytes * 8 in 40 ms = 80 kbps → CRITICAL
        monitor.registerClient("a")
        repeat(10) { monitor.recordFrameSent("a", 400, 40) }

        val applied = controller.getAdaptiveQuality(baseQuality = 85, thermalAdjustedQuality = 85)
        // CRITICAL factor 0.35 → 29, clamped at the adaptive floor (15)
        assertTrue("expected quality near floor, got $applied", applied in 15..35)

        // State publication is throttled to 250ms; wait out the window and
        // re-apply so the published state catches up with the frame path.
        Thread.sleep(260)
        val settled = controller.getAdaptiveQuality(baseQuality = 85, thermalAdjustedQuality = 85)
        assertEquals(settled, controller.state.value.currentQuality)
    }

    @Test
    fun `excellent network never reduces quality below thermal-adjusted value`() {
        val (controller, monitor) = enabledController()
        // 8000 kbps single client → EXCELLENT, factor 1.0
        monitor.registerClient("a")
        repeat(10) { monitor.recordFrameSent("a", 10_000, 10) }

        assertEquals(85, controller.getAdaptiveQuality(baseQuality = 85, thermalAdjustedQuality = 85))
    }

    @Test
    fun `poor network slows the frame interval and never undercuts thermal backoff`() {
        val (controller, monitor) = enabledController()
        monitor.registerClient("a")
        repeat(10) { monitor.recordFrameSent("a", 200, 40) } // 40 kbps → CRITICAL

        val slowed = controller.getAdaptiveFrameInterval(baseIntervalMs = 40, thermalAdjustedIntervalMs = 40)
        assertTrue("expected slowed interval, got $slowed", slowed > 40)
        // Thermal demanded a longer interval than adaptation would; adaptation must not shorten it.
        val thermalCapped = controller.getAdaptiveFrameInterval(baseIntervalMs = 40, thermalAdjustedIntervalMs = 300)
        assertEquals(300L, thermalCapped)
    }

    @Test
    fun `adjustment counter ticks on published state changes only`() {
        val (controller, monitor) = enabledController()
        monitor.registerClient("a")
        repeat(10) { monitor.recordFrameSent("a", 10_000, 10) }

        val before = controller.state.value.adjustmentCount
        repeat(50) {
            controller.getAdaptiveQuality(baseQuality = 85, thermalAdjustedQuality = 85)
        }
        // Identical applied values between publishes must not increment the counter.
        assertEquals(before, controller.state.value.adjustmentCount)
    }

    @Test
    fun `disabling resets applied quality to the configured default`() {
        val monitor = NetworkQualityMonitor()
        val controller = AdaptiveBitrateController(
            monitor,
            AdaptiveBitrateController.AdaptiveBitrateConfig(defaultQuality = 85),
        )
        controller.setEnabled(true)
        monitor.registerClient("a")
        repeat(10) { monitor.recordFrameSent("a", 200, 40) }
        controller.getAdaptiveQuality(baseQuality = 60, thermalAdjustedQuality = 60)

        controller.setEnabled(false)
        assertEquals(85, controller.state.value.currentQuality)
    }

    @Test
    fun `default frame rate is clamped to the config range`() {
        val monitor = NetworkQualityMonitor()
        val controller = AdaptiveBitrateController(
            monitor,
            AdaptiveBitrateController.AdaptiveBitrateConfig(defaultQuality = 80),
        )
        controller.setDefaultFrameRate(120) // above maxFps=30
        assertEquals(30, controller.state.value.targetFps)
        controller.setDefaultFrameRate(0)
        assertEquals(3, controller.state.value.targetFps)
    }
}

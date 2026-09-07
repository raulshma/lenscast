package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.AdaptiveInputs
import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.BatteryInputs
import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.ClientDetailInputs
import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.NetworkInputs
import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.StreamingInputs
import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.ThermalInputs
import com.raulshma.lenscast.streaming.web.StatusSnapshotBuilder.WatchdogInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatusSnapshotBuilderTest {

    private fun streaming(active: Boolean = true) = StreamingInputs(
        isActive = active,
        url = "http://phone:8080/stream",
        webStreamingEnabled = true,
        webStreamingActive = active,
        clientCount = if (active) 2 else 0,
        audioEnabled = true,
        audioUrl = "http://phone:8080/audio",
        rtspEnabled = false,
        rtspStreamingActive = false,
        rtspUrl = "",
    )

    private fun thermal() = ThermalInputs(
        cameraStateName = "Ready",
        thermalName = "NORMAL",
    )

    private fun battery() = BatteryInputs(
        level = 80,
        isCharging = true,
        isPowerSaveMode = false,
    )

    private fun watchdog() = WatchdogInputs(
        enabled = true,
        statusName = "MONITORING",
        consecutiveFailures = 1,
        totalRecoveries = 2,
        lastRecoveryTimestamp = 12345L,
        lastFailureReason = "boom",
    )

    private fun adaptive(enabled: Boolean = true) = AdaptiveInputs(
        enabled = enabled,
        qualityLevelName = "GOOD",
        currentQuality = 70,
        targetQuality = 70,
        currentFps = 24,
        targetFps = 24,
        estimatedBandwidthKbps = 3000,
        minClientThroughputKbps = 2500,
        activeClients = 2,
        adjustmentCount = 3,
    )

    private fun network(
        activeClients: Int = 2,
        firstClientFps: Double = 23.5,
        clientDetails: Map<String, ClientDetailInputs> = mapOf(
            "client-a" to ClientDetailInputs(
                framesSent = 100,
                bytesSent = 2000,
                avgThroughputKbps = 2500,
                lastFrameSizeBytes = 20,
                lastSendDurationMs = 5,
            ),
            "client-b" to ClientDetailInputs(
                framesSent = 50,
                bytesSent = 1000,
                avgThroughputKbps = 1500,
                lastFrameSizeBytes = 10,
                lastSendDurationMs = 7,
            ),
        ),
    ) = NetworkInputs(
        qualityLevelName = "GOOD",
        estimatedBandwidthKbps = 3000,
        avgThroughputKbps = 2000,
        minThroughputKbps = 1500,
        worstLatencyMs = 7,
        avgFrameSizeBytes = 15,
        totalBytesSent = 3000,
        activeClients = activeClients,
        firstClientFps = firstClientFps,
        clientDetails = clientDetails,
    )

    @Test
    fun `live with adaptive on maps fps from first client`() {
        val response = StatusSnapshotBuilder.build(
            streaming = streaming(active = true),
            thermal = thermal(),
            battery = battery(),
            watchdog = watchdog(),
            adaptive = adaptive(enabled = true),
            network = network(firstClientFps = 23.5),
        )

        val adaptiveDto = response.adaptiveBitrate
        assertNotNull(adaptiveDto)
        assertEquals(true, adaptiveDto!!.enabled)
        assertEquals("GOOD", adaptiveDto.qualityLevel)
        assertEquals(70, adaptiveDto.currentQuality)
        assertEquals(24, adaptiveDto.currentFps)
        assertEquals(3, adaptiveDto.adjustmentCount)

        val quality = response.connectionQuality
        assertNotNull(quality)
        assertEquals(23.5, quality!!.framesPerSecond, 0.0)
        assertEquals(2, quality.clientDetails.size)
        assertEquals(100L, quality.clientDetails.getValue("client-a").framesSent)
        assertEquals(2000L, quality.clientDetails.getValue("client-a").bytesSent)
        assertEquals(2500, quality.clientDetails.getValue("client-a").avgThroughputKbps)
        assertEquals(50L, quality.clientDetails.getValue("client-b").framesSent)
    }

    @Test
    fun `idle maps connectionQuality and adaptive null when disabled`() {
        val response = StatusSnapshotBuilder.build(
            streaming = streaming(active = false),
            thermal = thermal(),
            battery = battery(),
            watchdog = watchdog(),
            adaptive = adaptive(enabled = false),
            network = network(),
        )

        assertNull(response.adaptiveBitrate)
        assertNull(response.connectionQuality)
        assertEquals(false, response.streaming.isActive)
        assertEquals("http://phone:8080/stream", response.streaming.url)
    }

    @Test
    fun `empty clientDetails maps fps zero`() {
        val response = StatusSnapshotBuilder.build(
            streaming = streaming(active = true),
            thermal = thermal(),
            battery = battery(),
            watchdog = watchdog(),
            adaptive = adaptive(enabled = true),
            network = network(
                activeClients = 0,
                firstClientFps = 99.0,
                clientDetails = emptyMap(),
            ),
        )

        val quality = response.connectionQuality
        assertNotNull(quality)
        assertEquals(0.0, quality!!.framesPerSecond, 0.0)
        assertEquals(true, quality.clientDetails.isEmpty())
    }

    @Test
    fun `watchdog thermal camera and battery pass through`() {
        val response = StatusSnapshotBuilder.build(
            streaming = streaming(active = true),
            thermal = ThermalInputs(cameraStateName = "Error(message=oops)", thermalName = "SEVERE"),
            battery = BatteryInputs(level = 15, isCharging = false, isPowerSaveMode = true),
            watchdog = WatchdogInputs(
                enabled = false,
                statusName = "IDLE",
                consecutiveFailures = 0,
                totalRecoveries = 5,
                lastRecoveryTimestamp = 999L,
                lastFailureReason = null,
            ),
            adaptive = adaptive(enabled = true),
            network = network(),
        )

        assertEquals("SEVERE", response.thermal)
        assertEquals("Error(message=oops)", response.camera)
        assertEquals(15, response.battery.level)
        assertEquals(false, response.battery.isCharging)
        assertEquals(true, response.battery.isPowerSaveMode)
        val wd = response.watchdog
        assertNotNull(wd)
        assertEquals(false, wd!!.enabled)
        assertEquals("IDLE", wd.status)
        assertEquals(0, wd.consecutiveFailures)
        assertEquals(5, wd.totalRecoveries)
        assertEquals(999L, wd.lastRecoveryTimestamp)
        assertNull(wd.lastFailureReason)
    }
}

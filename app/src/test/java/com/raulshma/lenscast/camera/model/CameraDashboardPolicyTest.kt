package com.raulshma.lenscast.camera.model

import androidx.compose.ui.graphics.Color
import com.raulshma.lenscast.R
import com.raulshma.lenscast.core.NetworkQualityMonitor.ClientStatsSnapshot
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkStatsSnapshot
import com.raulshma.lenscast.core.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CameraDashboardPolicyTest {

    // ── wifi banner ──

    @Test
    fun `wifi banner shows only when the server runs off wifi`() {
        assertTrue(CameraDashboardPolicy.shouldShowWifiBanner(wifiConnected = false, isServerRunning = true))
        assertFalse(CameraDashboardPolicy.shouldShowWifiBanner(wifiConnected = false, isServerRunning = false))
        assertFalse(CameraDashboardPolicy.shouldShowWifiBanner(wifiConnected = true, isServerRunning = true))
        assertFalse(CameraDashboardPolicy.shouldShowWifiBanner(wifiConnected = true, isServerRunning = false))
    }

    @Test
    fun `wifi banner shortens while a stream is live`() {
        assertEquals(
            CameraDashboardPolicy.WifiBannerVariant.STREAM_ACTIVE,
            CameraDashboardPolicy.wifiBannerVariant(streamActive = true),
        )
        assertEquals(
            CameraDashboardPolicy.WifiBannerVariant.STREAM_IDLE,
            CameraDashboardPolicy.wifiBannerVariant(streamActive = false),
        )
    }

    // ── server status ──

    @Test
    fun `server status tier ladder is live then ready then offline`() {
        assertEquals(
            CameraDashboardPolicy.ServerStatusTier.LIVE,
            CameraDashboardPolicy.serverStatusTier(isActive = true, isServerRunning = true),
        )
        assertEquals(
            CameraDashboardPolicy.ServerStatusTier.LIVE,
            CameraDashboardPolicy.serverStatusTier(isActive = true, isServerRunning = false),
        )
        assertEquals(
            CameraDashboardPolicy.ServerStatusTier.READY,
            CameraDashboardPolicy.serverStatusTier(isActive = false, isServerRunning = true),
        )
        assertEquals(
            CameraDashboardPolicy.ServerStatusTier.OFFLINE,
            CameraDashboardPolicy.serverStatusTier(isActive = false, isServerRunning = false),
        )
    }

    @Test
    fun `server status line ladder covers all four branches`() {
        assertEquals(
            CameraDashboardPolicy.ServerStatusLine(CameraDashboardPolicy.ServerStatusTier.LIVE, 2),
            CameraDashboardPolicy.serverStatusLine(2, isActive = true, isServerRunning = true),
        )
        assertEquals(
            CameraDashboardPolicy.ServerStatusLine(CameraDashboardPolicy.ServerStatusTier.LIVE, 0),
            CameraDashboardPolicy.serverStatusLine(0, isActive = true, isServerRunning = true),
        )
        assertEquals(
            CameraDashboardPolicy.ServerStatusLine(CameraDashboardPolicy.ServerStatusTier.READY, 0),
            CameraDashboardPolicy.serverStatusLine(0, isActive = false, isServerRunning = true),
        )
        assertEquals(
            CameraDashboardPolicy.ServerStatusLine(CameraDashboardPolicy.ServerStatusTier.OFFLINE, 0),
            CameraDashboardPolicy.serverStatusLine(0, isActive = false, isServerRunning = false),
        )
    }

    // ── stream shutter button ──

    @Test
    fun `stream shutter visual is recording red with the web kind while streaming`() {
        val visual = CameraDashboardPolicy.StreamShutterVisual.of(
            isStreaming = true, isEnabled = true, kind = CameraDashboardPolicy.StreamKind.WEB,
        )
        assertEquals(CameraDashboardPolicy.StreamShutterContainer.RECORDING, visual.container)
        assertEquals(Color.White, visual.tint)
        assertEquals(CameraDashboardPolicy.StreamKind.WEB, visual.kind)
        assertTrue(visual.clickEnabled)
    }

    @Test
    fun `stream shutter visual stays stoppable while streaming with the toggle disabled`() {
        val visual = CameraDashboardPolicy.StreamShutterVisual.of(
            isStreaming = true, isEnabled = false, kind = CameraDashboardPolicy.StreamKind.RTSP,
        )
        assertEquals(CameraDashboardPolicy.StreamShutterContainer.RECORDING, visual.container)
        assertEquals(Color.White, visual.tint)
        assertEquals(CameraDashboardPolicy.StreamKind.RTSP, visual.kind)
        assertFalse(visual.clickEnabled)
    }

    @Test
    fun `stream shutter visual dims while enabled and idle`() {
        val visual = CameraDashboardPolicy.StreamShutterVisual.of(
            isStreaming = false, isEnabled = true, kind = CameraDashboardPolicy.StreamKind.WEB,
        )
        assertEquals(CameraDashboardPolicy.StreamShutterContainer.ENABLED, visual.container)
        assertEquals(Color.White, visual.tint)
        assertEquals(CameraDashboardPolicy.StreamKind.WEB, visual.kind)
        assertTrue(visual.clickEnabled)
    }

    @Test
    fun `stream shutter visual ghosts and gates the click while disabled`() {
        val visual = CameraDashboardPolicy.StreamShutterVisual.of(
            isStreaming = false, isEnabled = false, kind = CameraDashboardPolicy.StreamKind.RTSP,
        )
        assertEquals(CameraDashboardPolicy.StreamShutterContainer.DISABLED, visual.container)
        assertEquals(Color.White.copy(alpha = 0.35f), visual.tint)
        assertEquals(CameraDashboardPolicy.StreamKind.RTSP, visual.kind)
        assertFalse(visual.clickEnabled)
    }

    // ── thermal banner ──

    @Test
    fun `thermal banner is null below moderate warming`() {
        assertNull(CameraDashboardPolicy.thermalBanner(ThermalState.NORMAL))
        assertNull(CameraDashboardPolicy.thermalBanner(ThermalState.LIGHT))
    }

    @Test
    fun `thermal banner labels every moderate-or-worse state`() {
        assertEquals(
            CameraDashboardPolicy.ThermalSeverity.MODERATE,
            CameraDashboardPolicy.thermalBanner(ThermalState.MODERATE),
        )
        assertEquals(
            CameraDashboardPolicy.ThermalSeverity.SEVERE,
            CameraDashboardPolicy.thermalBanner(ThermalState.SEVERE),
        )
        assertEquals(
            CameraDashboardPolicy.ThermalSeverity.CRITICAL,
            CameraDashboardPolicy.thermalBanner(ThermalState.CRITICAL),
        )
    }

    // ── network quality ──

    @Test
    fun `quality badge covers every level with its color`() {
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFF4CAF50)),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.EXCELLENT),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFF8BC34A)),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.GOOD),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFFFFC107)),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.FAIR),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFFFF9800)),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.POOR),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFFF44336)),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.CRITICAL),
        )
    }

    // ── connection panel ──

    @Test
    fun `quality indicator shows only for an active stream with adaptation on`() {
        assertTrue(
            CameraDashboardPolicy.qualityIndicatorVisible(streamStatusActive = true, adaptiveEnabled = true)
        )
        assertFalse(CameraDashboardPolicy.qualityIndicatorVisible(streamStatusActive = true, adaptiveEnabled = false))
        assertFalse(CameraDashboardPolicy.qualityIndicatorVisible(streamStatusActive = false, adaptiveEnabled = true))
        assertFalse(CameraDashboardPolicy.qualityIndicatorVisible(streamStatusActive = false, adaptiveEnabled = false))
    }

    @Test
    fun `quality summary renders quality then fps`() {
        assertEquals("80q 24fps", CameraDashboardPolicy.qualitySummary(quality = 80, fps = 24))
        assertEquals("0q 0fps", CameraDashboardPolicy.qualitySummary(quality = 0, fps = 0))
    }

    @Test
    fun `connection stat rows order the expanded panel through the one byte formatter`() {
        val stats = NetworkStatsSnapshot(
            activeClients = 2,
            estimatedBandwidthKbps = 900,
            totalBytesSent = 1024L * 1024,
            minThroughputKbps = 1200,
            avgThroughputKbps = 2400,
            worstLatencyMs = 45L,
            qualityLevel = NetworkQualityLevel.GOOD,
            clientDetails = emptyMap(),
            avgFrameSizeBytes = 2 * 1024 * 1024,
        )
        assertEquals(
            listOf(
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_bandwidth, "8000 kbps"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_min_throughput, "1200 kbps"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_avg_throughput, "2400 kbps"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_latency, "45 ms"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_avg_frame, "2 MB"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_clients, "2"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_total_sent, "1 MB"),
            ),
            CameraDashboardPolicy.connectionStatRows(estimatedBandwidthKbps = 8000, stats = stats),
        )
    }

    @Test
    fun `client stat rows truncate the id header and list the frame stats`() {
        assertEquals("1a2b3c4d", CameraDashboardPolicy.clientIdPrefix("1a2b3c4d5e6f7a7b"))
        val detail = ClientStatsSnapshot(
            framesSent = 1200L,
            bytesSent = 2048L,
            avgThroughputKbps = 900,
            lastFrameSizeBytes = 2048,
            lastSendDurationMs = 33L,
        )
        assertEquals(
            listOf(
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_frames, "1200"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_throughput, "900 kbps"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_latency, "33 ms"),
                CameraDashboardPolicy.ConnectionStatRow(R.string.camera_stat_frame_size, "2 KB"),
            ),
            CameraDashboardPolicy.clientStatRows(detail),
        )
    }

    // ── formats ──

    @Test
    fun `formatBytes uses integer division at each unit boundary`() {
        assertEquals("0 B", CameraDashboardPolicy.formatBytes(0))
        assertEquals("1023 B", CameraDashboardPolicy.formatBytes(1023))
        assertEquals("1 KB", CameraDashboardPolicy.formatBytes(1024))
        assertEquals("1023 KB", CameraDashboardPolicy.formatBytes(1024 * 1024 - 1))
        assertEquals("1 MB", CameraDashboardPolicy.formatBytes(1024 * 1024))
        assertEquals("1 GB", CameraDashboardPolicy.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `slider endpoint strips the trailing point zero`() {
        assertEquals("12", CameraDashboardPolicy.sliderEndpoint(12.0f))
        assertEquals("0", CameraDashboardPolicy.sliderEndpoint(0.0f))
        assertEquals("2.5", CameraDashboardPolicy.sliderEndpoint(2.5f))
    }

    @Test
    fun `slider value label renders whole numbers without decimals`() {
        assertEquals("12", CameraDashboardPolicy.sliderValueLabel(12.0f))
        assertEquals("2.5", CameraDashboardPolicy.sliderValueLabel(2.5f))
    }

    @Test
    fun `slider value label pins the decimal separator to Locale US`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.5", CameraDashboardPolicy.sliderValueLabel(2.5f))
            assertEquals("12", CameraDashboardPolicy.sliderValueLabel(12.0f))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `zoom label matches the pill formatter and pins Locale US`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.5x", QuickSettingCatalog.zoomLabel(2.5f))
            assertEquals("10.0x", QuickSettingCatalog.zoomLabel(10.0f))
        } finally {
            Locale.setDefault(original)
        }
    }
}

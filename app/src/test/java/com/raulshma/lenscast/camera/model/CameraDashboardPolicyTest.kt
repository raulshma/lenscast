package com.raulshma.lenscast.camera.model

import androidx.compose.ui.graphics.Color
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
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
        assertEquals("Not on WiFi", CameraDashboardPolicy.wifiBannerMessage(streamActive = true))
        assertEquals(
            "Not on WiFi — server may not be reachable",
            CameraDashboardPolicy.wifiBannerMessage(streamActive = false),
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
    fun `server status text ladder covers all four branches`() {
        assertEquals("2 viewer(s) connected", CameraDashboardPolicy.serverStatusText(2, isActive = true, isServerRunning = true))
        assertEquals("Live stream active", CameraDashboardPolicy.serverStatusText(0, isActive = true, isServerRunning = true))
        assertEquals("Server ready", CameraDashboardPolicy.serverStatusText(0, isActive = false, isServerRunning = true))
        assertEquals("Offline", CameraDashboardPolicy.serverStatusText(0, isActive = false, isServerRunning = false))
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
            CameraDashboardPolicy.ThermalBanner(CameraDashboardPolicy.ThermalSeverity.MODERATE, "Thermal: Moderate"),
            CameraDashboardPolicy.thermalBanner(ThermalState.MODERATE),
        )
        assertEquals(
            CameraDashboardPolicy.ThermalBanner(CameraDashboardPolicy.ThermalSeverity.SEVERE, "Thermal: Severe"),
            CameraDashboardPolicy.thermalBanner(ThermalState.SEVERE),
        )
        assertEquals(
            CameraDashboardPolicy.ThermalBanner(CameraDashboardPolicy.ThermalSeverity.CRITICAL, "Thermal: Critical!"),
            CameraDashboardPolicy.thermalBanner(ThermalState.CRITICAL),
        )
    }

    // ── network quality ──

    @Test
    fun `quality badge covers every level with its color and abbreviation`() {
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFF4CAF50), "EXC"),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.EXCELLENT),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFF8BC34A), "GOOD"),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.GOOD),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFFFFC107), "FAIR"),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.FAIR),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFFFF9800), "POOR"),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.POOR),
        )
        assertEquals(
            CameraDashboardPolicy.QualityBadge(Color(0xFFF44336), "CRIT"),
            CameraDashboardPolicy.qualityBadge(NetworkQualityLevel.CRITICAL),
        )
    }

    @Test
    fun `client summary pluralizes the client count`() {
        assertEquals("1 client · 2500kbps", CameraDashboardPolicy.clientSummary(1, 2500))
        assertEquals("3 clients · 2500kbps", CameraDashboardPolicy.clientSummary(3, 2500))
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

package com.raulshma.lenscast.core

import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The network-adaptation decisions the monitor delegates: the level ladder's
 * rungs, the quality/fps factor tables, and the 0-while-idle display rule.
 */
class NetworkAdaptationPolicyTest {

    // ── Level ladder ──

    @Test
    fun `the ladder maps throughput rungs to levels`() {
        val p = NetworkAdaptationPolicy
        assertEquals(NetworkQualityLevel.CRITICAL, p.levelFor(499, 2))
        assertEquals(NetworkQualityLevel.POOR, p.levelFor(500, 2))
        assertEquals(NetworkQualityLevel.FAIR, p.levelFor(1500, 2))
        assertEquals(NetworkQualityLevel.GOOD, p.levelFor(3000, 2))
        assertEquals(NetworkQualityLevel.GOOD, p.levelFor(8000, 2))
    }

    @Test
    fun `excellent requires a good link and at most one client`() {
        assertEquals(NetworkQualityLevel.EXCELLENT, NetworkAdaptationPolicy.levelFor(3000, 1))
        assertEquals(NetworkQualityLevel.EXCELLENT, NetworkAdaptationPolicy.levelFor(8000, 0))
        assertEquals(NetworkQualityLevel.GOOD, NetworkAdaptationPolicy.levelFor(3000, 2))
    }

    // ── Quality factors ──

    @Test
    fun `full-factor levels leave the thermal quality standing`() {
        assertEquals(
            70,
            NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.EXCELLENT, 70, 80),
        )
        assertEquals(
            63, // 70 * 0.9
            NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.GOOD, 70, 80),
        )
    }

    @Test
    fun `degraded levels scale the quality down by their factor`() {
        assertEquals(52, NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.FAIR, 70, 80))     // 0.75
        assertEquals(38, NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.POOR, 70, 80))     // 0.55
        assertEquals(24, NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.CRITICAL, 70, 80)) // 0.35
    }

    @Test
    fun `quality never drops below the adaptive minimum nor above base or thermal`() {
        // Floor: CRITICAL at quality 20 → 7, clamped to ADAPTIVE_JPEG_QUALITY_MIN = 15.
        assertEquals(
            StreamDefaults.ADAPTIVE_JPEG_QUALITY_MIN,
            NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.CRITICAL, 20, 80),
        )
        // Ceiling: never above the base quality's valid range.
        assertEquals(
            StreamDefaults.JPEG_QUALITY_MAX,
            NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.EXCELLENT, 150, 150),
        )
        // Never above what thermal already allows.
        assertEquals(
            50,
            NetworkAdaptationPolicy.qualityFor(NetworkQualityLevel.EXCELLENT, 50, 90),
        )
    }

    // ── Frame interval factors ──

    @Test
    fun `full-factor levels keep the thermal interval`() {
        assertEquals(
            50L,
            NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.EXCELLENT, 40, 50),
        )
        assertEquals(
            50L,
            NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.GOOD, 40, 50),
        )
    }

    @Test
    fun `degraded levels slow the base frame rate by their factor`() {
        // 25 fps base slowed to 0.75 → 18.75 fps → 53 ms.
        assertEquals(53L, NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.FAIR, 40, 50))
        // 0.5 factor → 12.5 fps → 80 ms.
        assertEquals(80L, NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.POOR, 40, 50))
        // 0.3 factor → 7.5 fps → 133 ms.
        assertEquals(133L, NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.CRITICAL, 40, 50))
    }

    @Test
    fun `adapted fps floors at the minimum fps and never beats thermal`() {
        // 5 fps base slowed to 0.3 → 1.5 fps, floored at MIN_FPS = 3 → 333 ms.
        assertEquals(
            333L,
            NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.CRITICAL, 200, 200),
        )
        // The thermal-adjusted interval is the fastest allowed.
        assertEquals(
            200L,
            NetworkAdaptationPolicy.intervalFor(NetworkQualityLevel.FAIR, 40, 200),
        )
    }

    // ── Display rule ──

    @Test
    fun `display bandwidth is zero while idle and measured otherwise`() {
        assertEquals(0, NetworkAdaptationPolicy.displayKbps(measuredKbps = 8000, hasActiveClients = false))
        assertEquals(8000, NetworkAdaptationPolicy.displayKbps(measuredKbps = 8000, hasActiveClients = true))
        assertEquals(0, NetworkAdaptationPolicy.displayKbps(measuredKbps = 0, hasActiveClients = true))
    }
}

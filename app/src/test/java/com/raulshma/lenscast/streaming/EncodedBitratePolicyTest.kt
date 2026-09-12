package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure encoded-video bitrate ladder ([EncodedBitratePolicy]):
 * the network/thermal factors, the configured-ceiling clamp, and the
 * hysteresis step — the anti-sawtooth contract the adaptive monitor relies on.
 */
class EncodedBitratePolicyTest {

    private val configured = StreamDefaults.RTSP_VIDEO_BITRATE // 2 Mbps

    @Test
    fun `disabled policy never moves the target`() {
        assertNull(
            EncodedBitratePolicy.targetBitrate(
                enabled = false,
                level = NetworkQualityMonitor.NetworkQualityLevel.CRITICAL,
                thermal = ThermalState.SEVERE,
                configuredBitrate = configured,
                currentBitrate = configured,
            )
        )
    }

    @Test
    fun `excellent network at normal thermal keeps the configured bitrate`() {
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT,
            thermal = ThermalState.NORMAL,
            configuredBitrate = configured,
            currentBitrate = configured,
        )
        // desired == configured == current: no step beyond the threshold.
        assertNull(target)
    }

    @Test
    fun `critical network scales down to the 35 percent rung`() {
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.CRITICAL,
            thermal = ThermalState.NORMAL,
            configuredBitrate = configured,
            currentBitrate = configured,
        )
        assertEquals((configured * 0.35f).toInt(), target)
    }

    @Test
    fun `thermal and network factors stack`() {
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.POOR, // 0.55
            thermal = ThermalState.SEVERE, // 0.5
            configuredBitrate = configured,
            currentBitrate = configured,
        )
        val desired = (configured * 0.55f * 0.5f).toInt()
            .coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, configured)
        assertEquals(desired, target)
        assertTrue(target!! >= StreamDefaults.VIDEO_BITRATE_MIN)
    }

    @Test
    fun `target never rises above the configured bitrate`() {
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT,
            thermal = ThermalState.NORMAL,
            configuredBitrate = 600_000,
            currentBitrate = 550_000,
        )
        // desired == 600k, current 550k: within the 10 percent step — keep.
        assertNull(target)
    }

    @Test
    fun `small movements inside the hysteresis band are ignored`() {
        // desired = 0.9 * 2M = 1.8M; step = 200k; current 2M: 200k not > 200k → keep.
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.GOOD,
            thermal = ThermalState.NORMAL,
            configuredBitrate = configured,
            currentBitrate = configured,
        )
        assertNull(target)
    }

    @Test
    fun `movements beyond the hysteresis band step`() {
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.FAIR, // 0.75 → 1.5M
            thermal = ThermalState.NORMAL,
            configuredBitrate = configured,
            currentBitrate = configured,
        )
        assertEquals((configured * 0.75f).toInt(), target)
    }

    @Test
    fun `target never falls below the sane floor`() {
        // 0.35 * 600k = 210k → clamped to the 500k floor.
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = true,
            level = NetworkQualityMonitor.NetworkQualityLevel.CRITICAL,
            thermal = ThermalState.CRITICAL,
            configuredBitrate = 600_000,
            currentBitrate = 600_000,
        )
        assertEquals(StreamDefaults.VIDEO_BITRATE_MIN, target)
    }

    @Test
    fun `network factor rungs mirror the MJPEG ladder shape`() {
        assertEquals(
            1.0f,
            EncodedBitratePolicy.networkFactor(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT),
        )
        assertEquals(
            0.35f,
            EncodedBitratePolicy.networkFactor(NetworkQualityMonitor.NetworkQualityLevel.CRITICAL),
        )
        assertEquals(1.0f, EncodedBitratePolicy.thermalFactor(ThermalState.NORMAL))
        assertEquals(0.5f, EncodedBitratePolicy.thermalFactor(ThermalState.SEVERE))
    }
}

package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalThrottlePolicyTest {

    private fun resolve(state: ThermalState) = ThermalThrottlePolicy.resolve(state)

    // ── tier mapping: every thermal state lands in its own tier ──

    @Test
    fun `normal restores the stream defaults baseline`() {
        val result = resolve(ThermalState.NORMAL)
        assertEquals(StreamDefaults.JPEG_QUALITY, result.jpegQuality)
        assertEquals(1.0f, result.frameRateMultiplier)
        assertFalse(result.shouldPause)
    }

    @Test
    fun `light trims frame rate slightly and jpeg quality a little`() {
        val result = resolve(ThermalState.LIGHT)
        assertEquals(ThermalThrottlePolicy.LIGHT_JPEG_QUALITY, result.jpegQuality)
        assertEquals(ThermalThrottlePolicy.LIGHT_FRAME_RATE_MULTIPLIER, result.frameRateMultiplier)
        assertFalse(result.shouldPause)
    }

    @Test
    fun `moderate halves-ish the frame rate and drops quality further`() {
        val result = resolve(ThermalState.MODERATE)
        assertEquals(ThermalThrottlePolicy.MODERATE_JPEG_QUALITY, result.jpegQuality)
        assertEquals(ThermalThrottlePolicy.MODERATE_FRAME_RATE_MULTIPLIER, result.frameRateMultiplier)
        assertFalse(result.shouldPause)
    }

    @Test
    fun `severe keeps half the frame rate at reduced quality`() {
        val result = resolve(ThermalState.SEVERE)
        assertEquals(ThermalThrottlePolicy.SEVERE_JPEG_QUALITY, result.jpegQuality)
        assertEquals(ThermalThrottlePolicy.SEVERE_FRAME_RATE_MULTIPLIER, result.frameRateMultiplier)
        assertFalse(result.shouldPause)
    }

    @Test
    fun `critical zeroes the frame rate and pauses the stream`() {
        val result = resolve(ThermalState.CRITICAL)
        assertEquals(ThermalThrottlePolicy.CRITICAL_JPEG_QUALITY, result.jpegQuality)
        assertEquals(ThermalThrottlePolicy.CRITICAL_FRAME_RATE_MULTIPLIER, result.frameRateMultiplier)
        assertTrue(result.shouldPause)
    }

    // ── ladder shape: monotonic degradation, pause only at the bottom ──

    @Test
    fun `every thermal state resolves without falling through`() {
        // Exhaustiveness guard: a newly added ThermalState value that the
        // policy forgets must fail here, not at runtime on a hot phone.
        ThermalState.entries.forEach { state ->
            val result = resolve(state)
            assertTrue(result.jpegQuality in StreamDefaults.JPEG_QUALITY_MIN..StreamDefaults.JPEG_QUALITY_MAX)
        }
    }

    @Test
    fun `quality degrades strictly as the thermal state worsens`() {
        val ladder = ThermalState.entries.map { resolve(it).jpegQuality }
        assertTrue("quality must strictly decrease down the ladder: $ladder", ladder.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun `frame rate multiplier degrades strictly as the thermal state worsens`() {
        val ladder = ThermalState.entries.map { resolve(it).frameRateMultiplier }
        assertTrue(
            "multiplier must strictly decrease down the ladder: $ladder",
            ladder.zipWithNext().all { (a, b) -> a > b }
        )
    }

    @Test
    fun `only the critical tier pauses the stream`() {
        ThermalState.entries.forEach { state ->
            assertEquals(
                "pause expected only for CRITICAL, got $state",
                state == ThermalState.CRITICAL,
                resolve(state).shouldPause
            )
        }
    }
}

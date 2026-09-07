package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryQualityPolicyTest {

    private fun resolve(
        batteryLevel: Int = 80,
        isPowerSave: Boolean = false,
        isCharging: Boolean = false,
        inDoze: Boolean = false,
    ) = BatteryQualityPolicy.resolve(batteryLevel, isPowerSave, isCharging, inDoze)

    // ── branch order: charging > doze > critical > low > saver > normal ──

    @Test
    fun `charging wins over everything at full quality`() {
        val result = resolve(batteryLevel = 5, isPowerSave = true, isCharging = true, inDoze = true)
        assertEquals(StreamDefaults.JPEG_QUALITY, result.suggestedJpegQuality)
        assertEquals(5, result.batteryLevel)
        assertTrue(result.isPowerSaveMode) // echoes the isPowerSave input
        assertEquals("Charging - full quality", result.message)
    }

    @Test
    fun `charging with healthy battery reports normal power-save state`() {
        val result = resolve(batteryLevel = 90, isCharging = true)
        assertEquals(StreamDefaults.JPEG_QUALITY, result.suggestedJpegQuality)
        assertFalse(result.isPowerSaveMode)
        assertEquals("Charging - full quality", result.message)
    }

    @Test
    fun `doze forces minimal quality and the power-save flag`() {
        val result = resolve(batteryLevel = 90, inDoze = true)
        assertEquals(BatteryQualityPolicy.DOZE_QUALITY, result.suggestedJpegQuality)
        assertTrue(result.isPowerSaveMode)
        assertEquals("Doze mode - minimal quality", result.message)
    }

    @Test
    fun `doze wins over critical battery`() {
        val result = resolve(batteryLevel = 3, inDoze = true)
        assertEquals(BatteryQualityPolicy.DOZE_QUALITY, result.suggestedJpegQuality)
        assertEquals("Doze mode - minimal quality", result.message)
    }

    @Test
    fun `critical battery below 15 drops to minimal quality`() {
        val result = resolve(batteryLevel = 14)
        assertEquals(BatteryQualityPolicy.CRITICAL_BATTERY_QUALITY, result.suggestedJpegQuality)
        assertFalse(result.isPowerSaveMode)
        assertEquals("Critical battery - minimal quality", result.message)
    }

    @Test
    fun `critical battery with power save echoes the power-save flag`() {
        val result = resolve(batteryLevel = 10, isPowerSave = true)
        assertEquals(BatteryQualityPolicy.CRITICAL_BATTERY_QUALITY, result.suggestedJpegQuality)
        assertTrue(result.isPowerSaveMode)
        assertEquals("Critical battery - minimal quality", result.message)
    }

    @Test
    fun `low battery below 30 reduces quality`() {
        val result = resolve(batteryLevel = 29)
        assertEquals(BatteryQualityPolicy.LOW_BATTERY_QUALITY, result.suggestedJpegQuality)
        assertFalse(result.isPowerSaveMode)
        assertEquals("Low battery - reduced quality", result.message)
    }

    @Test
    fun `saver tier covers sub-50 levels and power save mode`() {
        val lowButNotLow = resolve(batteryLevel = 49)
        assertEquals(BatteryQualityPolicy.BATTERY_SAVER_QUALITY, lowButNotLow.suggestedJpegQuality)
        assertFalse(lowButNotLow.isPowerSaveMode)
        assertEquals("Battery saver - balanced quality", lowButNotLow.message)

        val powerSave = resolve(batteryLevel = 80, isPowerSave = true)
        assertEquals(BatteryQualityPolicy.BATTERY_SAVER_QUALITY, powerSave.suggestedJpegQuality)
        assertTrue(powerSave.isPowerSaveMode)
        assertEquals("Battery saver - balanced quality", powerSave.message)
    }

    @Test
    fun `healthy battery with no savings active is normal operation`() {
        val result = resolve(batteryLevel = 100)
        assertEquals(StreamDefaults.JPEG_QUALITY, result.suggestedJpegQuality)
        assertFalse(result.isPowerSaveMode)
        assertEquals("Normal operation", result.message)
    }

    // ── boundaries ──

    @Test
    fun `boundary pairs at 15 30 and 50`() {
        // 14 → critical, 15 → low.
        assertEquals(
            BatteryQualityPolicy.CRITICAL_BATTERY_QUALITY,
            resolve(batteryLevel = 14).suggestedJpegQuality
        )
        assertEquals(
            BatteryQualityPolicy.LOW_BATTERY_QUALITY,
            resolve(batteryLevel = 15).suggestedJpegQuality
        )
        // 29 → low, 30 → saver.
        assertEquals(
            BatteryQualityPolicy.LOW_BATTERY_QUALITY,
            resolve(batteryLevel = 29).suggestedJpegQuality
        )
        assertEquals(
            BatteryQualityPolicy.BATTERY_SAVER_QUALITY,
            resolve(batteryLevel = 30).suggestedJpegQuality
        )
        // 49 → saver, 50 → normal.
        assertEquals(
            BatteryQualityPolicy.BATTERY_SAVER_QUALITY,
            resolve(batteryLevel = 49).suggestedJpegQuality
        )
        assertEquals(
            StreamDefaults.JPEG_QUALITY,
            resolve(batteryLevel = 50).suggestedJpegQuality
        )
    }

    @Test
    fun `power save at the 50 boundary still lands in the saver tier`() {
        assertEquals(
            BatteryQualityPolicy.BATTERY_SAVER_QUALITY,
            resolve(batteryLevel = 50, isPowerSave = true).suggestedJpegQuality
        )
        assertEquals(
            StreamDefaults.JPEG_QUALITY,
            resolve(batteryLevel = 51).suggestedJpegQuality
        )
    }
}

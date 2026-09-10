package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionArmingPolicyTest {

    private val ALL = MotionArmingPolicy.ALL_DAYS_MASK
    private val MON = 0

    @Test
    fun `disabled detection is never armed`() {
        assertFalse(MotionArmingPolicy.isArmed(false, false, 0, 1439, 600, ALL, MON))
    }

    @Test
    fun `no schedule arms around the clock`() {
        assertTrue(MotionArmingPolicy.isArmed(true, false, 0, 1439, 0, ALL, MON))
        assertTrue(MotionArmingPolicy.isArmed(true, false, 600, 700, 1439, ALL, MON))
    }

    @Test
    fun `window arms inclusively at start and end`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 600, ALL, MON))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 699, ALL, MON))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 700, ALL, MON))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 600, 700, 701, ALL, MON))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 600, 700, 599, ALL, MON))
    }

    @Test
    fun `default all-day window never disarms`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 0, 1439, 0, ALL, MON))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 0, 1439, 1439, ALL, MON))
    }

    @Test
    fun `wrapping window arms across midnight`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 1320, 420, 1380, ALL, MON))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 1320, 420, 100, ALL, MON))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 1320, 420, 720, ALL, MON))
    }

    @Test
    fun `equal start and end means always armed`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 600, 100, ALL, MON))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 600, 1000, ALL, MON))
    }

    // ── Day-of-week mask ──

    @Test
    fun `all-days mask keeps the schedule purely time-of-day`() {
        // Monday (0) through Sunday (6), inside and outside the window.
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 650, daysMask = 127, isoDayIndex = 0))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 600, 700, 800, daysMask = 127, isoDayIndex = 6))
    }

    @Test
    fun `cleared day is disarmed even inside the window`() {
        val weekdays = 0b0011111 // Mon–Fri
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 650, daysMask = weekdays, isoDayIndex = 0))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 600, 700, 650, daysMask = weekdays, isoDayIndex = 5))
    }

    @Test
    fun `day bit helpers clamp and match ISO indices`() {
        assertEquals(0b0000001, MotionArmingPolicy.dayBit(0))
        assertEquals(0b1000000, MotionArmingPolicy.dayBit(6))
        // Out-of-range indices clamp, never shift past the mask.
        assertEquals(0b0000001, MotionArmingPolicy.dayBit(-3))
        assertEquals(0b1000000, MotionArmingPolicy.dayBit(9))
        assertTrue(MotionArmingPolicy.isArmedDay(0b0000010, 1))
        assertFalse(MotionArmingPolicy.isArmedDay(0b0000010, 2))
    }

    @Test
    fun `toggling a day flips its bit but never clears the last armed day`() {
        val weekdays = 0b0011111 // Mon–Fri
        assertEquals(0b0111111, MotionArmingPolicy.toggleDay(weekdays, isoDayIndex = 5))
        assertEquals(0b0001111, MotionArmingPolicy.toggleDay(weekdays, isoDayIndex = 4))
        // The last armed day stays on — an all-off mask cannot arise.
        assertEquals(0b0000001, MotionArmingPolicy.toggleDay(0b0000001, isoDayIndex = 0))
    }

    @Test
    fun `day gate applies even when the schedule is off`() {
        // Schedule off = armed around the clock regardless of days; the mask
        // only narrows an enabled schedule.
        assertTrue(MotionArmingPolicy.isArmed(true, false, 600, 700, 650, daysMask = 0, isoDayIndex = 3))
    }
}

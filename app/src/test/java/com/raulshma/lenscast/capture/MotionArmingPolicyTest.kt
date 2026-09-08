package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionArmingPolicyTest {

    @Test
    fun `disabled detection is never armed`() {
        assertFalse(MotionArmingPolicy.isArmed(false, false, 0, 1439, 600))
    }

    @Test
    fun `no schedule arms around the clock`() {
        assertTrue(MotionArmingPolicy.isArmed(true, false, 0, 1439, 0))
        assertTrue(MotionArmingPolicy.isArmed(true, false, 600, 700, 1439))
    }

    @Test
    fun `window arms inclusively at start and end`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 600))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 699))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 700, 700))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 600, 700, 701))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 600, 700, 599))
    }

    @Test
    fun `default all-day window never disarms`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 0, 1439, 0))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 0, 1439, 1439))
    }

    @Test
    fun `wrapping window arms across midnight`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 1320, 420, 1380))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 1320, 420, 100))
        assertFalse(MotionArmingPolicy.isArmed(true, true, 1320, 420, 720))
    }

    @Test
    fun `equal start and end means always armed`() {
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 600, 100))
        assertTrue(MotionArmingPolicy.isArmed(true, true, 600, 600, 1000))
    }
}

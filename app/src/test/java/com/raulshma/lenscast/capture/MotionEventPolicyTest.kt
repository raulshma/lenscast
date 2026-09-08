package com.raulshma.lenscast.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEventPolicyTest {
    @Test
    fun `warming up never fires`() {
        val v = MotionEventPolicy.evaluate(
            lastAvg = 100.0, currentAvg = 200.0,
            nowMs = 20_000L, lastFireMs = 0L, framesSeen = 3,
        )
        assertFalse(v.fire)
    }

    @Test
    fun `large delta after cooldown fires`() {
        val v = MotionEventPolicy.evaluate(
            lastAvg = 100.0, currentAvg = 130.0,
            nowMs = 20_000L, lastFireMs = 0L, framesSeen = 20,
        )
        assertTrue(v.fire)
    }

    @Test
    fun `cooldown suppresses repeat fire`() {
        val v = MotionEventPolicy.evaluate(
            lastAvg = 100.0, currentAvg = 130.0,
            nowMs = 5_000L, lastFireMs = 0L, framesSeen = 20,
        )
        assertFalse(v.fire)
    }

    @Test
    fun `small delta never fires`() {
        val v = MotionEventPolicy.evaluate(
            lastAvg = 100.0, currentAvg = 102.0,
            nowMs = 60_000L, lastFireMs = 0L, framesSeen = 50,
        )
        assertFalse(v.fire)
    }

    @Test
    fun `sensitivity ladder spans deaf to eager`() {
        org.junit.Assert.assertEquals(24.0, MotionEventPolicy.thresholdFor(0f), 1e-9)
        org.junit.Assert.assertEquals(4.0, MotionEventPolicy.thresholdFor(1f), 1e-9)
        org.junit.Assert.assertEquals(14.0, MotionEventPolicy.thresholdFor(0.5f), 1e-9)
    }

    @Test
    fun `sensitivity outside 0-1 coerces to the ladder ends`() {
        org.junit.Assert.assertEquals(24.0, MotionEventPolicy.thresholdFor(-2f), 1e-9)
        org.junit.Assert.assertEquals(4.0, MotionEventPolicy.thresholdFor(9f), 1e-9)
    }
}

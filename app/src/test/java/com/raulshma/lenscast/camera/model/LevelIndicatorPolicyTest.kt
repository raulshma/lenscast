package com.raulshma.lenscast.camera.model

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelIndicatorPolicyTest {

    private fun tilt(pitchDeg: Float, rollDeg: Float): LevelIndicatorPolicy.Acceleration {
        // The standard tilt decomposition: pitch tilts around the long (x)
        // axis, roll around the short (y) axis; gravity stays unit length.
        val pitchRad = Math.toRadians(pitchDeg.toDouble())
        val rollRad = Math.toRadians(rollDeg.toDouble())
        return LevelIndicatorPolicy.Acceleration(
            x = (-sin(pitchRad) * cos(rollRad)).toFloat(),
            y = sin(rollRad).toFloat(),
            z = cos(pitchRad).toFloat() * cos(rollRad).toFloat(),
        )
    }

    // ── the angle ladder ──

    @Test
    fun `a flat screen-up device reads zero on both axes`() {
        val a = tilt(pitchDeg = 0f, rollDeg = 0f)

        assertEquals(0f, LevelIndicatorPolicy.pitchDeg(a), 1e-3f)
        assertEquals(0f, LevelIndicatorPolicy.rollDeg(a), 1e-3f)
    }

    @Test
    fun `a roll tilt reads on the short axis only`() {
        val a = tilt(pitchDeg = 0f, rollDeg = 10f)

        assertEquals(0f, LevelIndicatorPolicy.pitchDeg(a), 1e-3f)
        assertEquals(10f, LevelIndicatorPolicy.rollDeg(a), 1e-2f)
    }

    @Test
    fun `a pitch tilt reads on the long axis only`() {
        val a = tilt(pitchDeg = 12f, rollDeg = 0f)

        assertEquals(12f, LevelIndicatorPolicy.pitchDeg(a), 1e-2f)
        assertEquals(0f, LevelIndicatorPolicy.rollDeg(a), 1e-3f)
    }

    // ── the near-level verdict ──

    @Test
    fun `the level threshold is inclusive on both axes`() {
        assertTrue(LevelIndicatorPolicy.isLevel(pitchDeg = 2f, rollDeg = -2f))
        assertFalse(LevelIndicatorPolicy.isLevel(pitchDeg = 2.1f, rollDeg = 0f))
        assertFalse(LevelIndicatorPolicy.isLevel(pitchDeg = 0f, rollDeg = -2.1f))
        assertFalse(LevelIndicatorPolicy.isLevel(pitchDeg = 0f, rollDeg = 30f))
    }

    // ── the overlay verdict ──

    @Test
    fun `a level reading draws a centered green-flagged line`() {
        val state = LevelIndicatorPolicy.lineState(tilt(pitchDeg = 0f, rollDeg = 0f))

        assertTrue(state.isLevel)
        assertEquals(0f, state.lineRotationDeg, 1e-3f)
        assertEquals(0f, state.lineCenterOffsetFraction, 1e-3f)
    }

    @Test
    fun `the line rotates with the roll`() {
        val state = LevelIndicatorPolicy.lineState(tilt(pitchDeg = 0f, rollDeg = 15f))

        assertEquals(15f, state.lineRotationDeg, 1e-2f)
        assertFalse(state.isLevel)
    }

    @Test
    fun `the line travels downward with the pitch within the budget`() {
        val state = LevelIndicatorPolicy.lineState(tilt(pitchDeg = 30f, rollDeg = 0f))

        val expected = 30f / LevelIndicatorPolicy.PITCH_TRAVEL_RANGE_DEG *
            LevelIndicatorPolicy.LINE_MAX_TRAVEL_FRACTION
        assertEquals(expected, state.lineCenterOffsetFraction, 1e-3f)
    }

    @Test
    fun `the line pins to its travel clamp past the pitch range`() {
        val state = LevelIndicatorPolicy.lineState(tilt(pitchDeg = 80f, rollDeg = 0f))

        assertEquals(
            LevelIndicatorPolicy.LINE_MAX_TRAVEL_FRACTION,
            state.lineCenterOffsetFraction,
            1e-3f,
        )
    }
}

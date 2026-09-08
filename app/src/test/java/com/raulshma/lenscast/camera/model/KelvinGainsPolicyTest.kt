package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KelvinGainsPolicyTest {

    @Test
    fun `warm kelvin boosts blue and cools red`() {
        val warm = KelvinGainsPolicy.gainsFor(2500)
        val neutral = KelvinGainsPolicy.gainsFor(5600)
        val cool = KelvinGainsPolicy.gainsFor(8500)
        // Warm light carries abundant red, so the red gain drops; cool light
        // is the inverse on blue.
        assertTrue(warm[0] < neutral[0])
        assertTrue(cool[0] > neutral[0])
        assertTrue(cool[3] < neutral[3])
        assertTrue(warm[3] > cool[3])
    }

    @Test
    fun `green stays at unity and values are clamped`() {
        for (kelvin in intArrayOf(2000, 3500, 5600, 7500, 9000)) {
            val gains = KelvinGainsPolicy.gainsFor(kelvin)
            assertEquals(4, gains.size)
            assertEquals(1.0f, gains[1])
            assertEquals(1.0f, gains[2])
            gains.forEach { assertTrue(it in KelvinGainsPolicy.GAIN_MIN..KelvinGainsPolicy.GAIN_MAX) }
        }
    }
}

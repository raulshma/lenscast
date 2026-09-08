package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundDetectionPolicyTest {

    @Test
    fun `rms of silence is zero and of full scale is near 100`() {
        assertEquals(0.0, SoundDetectionPolicy.rmsPercent(ByteArray(1024)), 0.0)
        val full = ByteArray(1024)
        for (i in full.indices step 2) {
            full[i] = 0x00
            full[i + 1] = 0x7F // ~32639 amplitude
        }
        assertTrue(SoundDetectionPolicy.rmsPercent(full) > 90.0)
    }

    @Test
    fun `threshold zero disables detection`() {
        assertFalse(
            SoundDetectionPolicy.evaluate(100.0, 0.0, nowMs = 1000, lastFireMs = 0).fire,
        )
    }

    @Test
    fun `fires on breach after cooldown`() {
        assertTrue(
            SoundDetectionPolicy.evaluate(50.0, 30.0, nowMs = 100_000, lastFireMs = 0).fire,
        )
        // Inside cooldown: no fire.
        assertFalse(
            SoundDetectionPolicy.evaluate(50.0, 30.0, nowMs = 105_000, lastFireMs = 100_000).fire,
        )
        // Below threshold: no fire even outside cooldown.
        assertFalse(
            SoundDetectionPolicy.evaluate(10.0, 30.0, nowMs = 200_000, lastFireMs = 100_000).fire,
        )
    }
}

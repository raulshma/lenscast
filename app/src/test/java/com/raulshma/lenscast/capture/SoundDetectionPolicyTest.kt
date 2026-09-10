package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
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

    // ── Adaptive noise floor ──

    @Test
    fun `effective threshold keeps the user setting as the floor`() {
        assertEquals(30.0, SoundDetectionPolicy.effectiveThreshold(30.0, null), 0.0)
        assertEquals(30.0, SoundDetectionPolicy.effectiveThreshold(30.0, 5.0), 0.0)
    }

    @Test
    fun `effective threshold rides above a loud ambient`() {
        // Ambient 40% + 10% headroom beats the user's 30%.
        assertEquals(50.0, SoundDetectionPolicy.effectiveThreshold(30.0, 40.0), 1e-9)
        // The user's higher setting still wins.
        assertEquals(65.0, SoundDetectionPolicy.effectiveThreshold(65.0, 40.0), 1e-9)
    }

    @Test
    fun `noise floor rises fast and falls slowly`() {
        val ema = AdaptiveNoiseFloor(initialPercent = 10.0)
        // A loud sample lifts the floor a good step (rise alpha 0.15).
        ema.update(50.0)
        assertEquals(10.0 + 0.15 * 40.0, ema.floorPercent, 1e-9)
        // A quiet sample barely lowers it (fall alpha 0.02).
        val afterRise = ema.floorPercent
        ema.update(0.0)
        assertEquals(afterRise - 0.02 * afterRise, ema.floorPercent, 1e-9)
    }

    @Test
    fun `noise floor first sample seeds directly and reset clears`() {
        val ema = AdaptiveNoiseFloor()
        assertEquals(0.0, ema.floorPercent, 0.0)
        ema.update(12.5)
        assertEquals(12.5, ema.floorPercent, 1e-9)
        ema.reset()
        assertEquals(0.0, ema.floorPercent, 0.0)
    }

    @Test
    fun `detector with adaptive floor ignores a constant ambient`() {
        // A constant 40% ambient: the floor converges near 40%, the effective
        // threshold near 50%, so identical chunks never breach it.
        val dormant = SoundDetector(listener = { fail("should not fire on constant ambient") })
        dormant.enabled = true
        dormant.thresholdPercent = 30
        dormant.adaptiveNoiseFloor = true
        var t = 1_000_000L
        repeat(60) {
            dormant.feed(sinePcm(40.0), nowMs = t)
            t += 100
        }

        // A spike to 80% does breach, once, then the cooldown holds.
        var fired = 0
        val spiky = SoundDetector(listener = { fired++ })
        spiky.enabled = true
        spiky.thresholdPercent = 30
        spiky.adaptiveNoiseFloor = true
        var t2 = 1_000_000L
        repeat(60) {
            spiky.feed(sinePcm(40.0), nowMs = t2)
            t2 += 100
        }
        spiky.feed(sinePcm(80.0), nowMs = t2)
        t2 += 100
        spiky.feed(sinePcm(80.0), nowMs = t2)
        assertEquals(1, fired)
    }

    @Test
    fun `toggling the adaptive floor off and on re-anchors it`() {
        var fired = 0
        val detector = SoundDetector(listener = { fired++ })
        detector.enabled = true
        detector.thresholdPercent = 30
        detector.adaptiveNoiseFloor = true
        var t = 1_000_000L
        // A loud period converges the floor near 80%, effective ~90%.
        repeat(60) {
            detector.feed(sinePcm(80.0), nowMs = t)
            t += 100
        }

        detector.adaptiveNoiseFloor = false
        detector.adaptiveNoiseFloor = true

        // The quiet new ambient anchors the floor at ~10% (effective = the
        // user's 30%)…
        repeat(10) {
            detector.feed(sinePcm(10.0), nowMs = t)
            t += 100
        }
        // …so a 60% spike — silent against a stale 80% floor — fires.
        detector.feed(sinePcm(60.0), nowMs = t)
        assertEquals(1, fired)
    }

    /** A PCM16 buffer whose RMS lands near [percent] of full scale. */
    private fun sinePcm(percent: Double): ByteArray {
        val amplitude = percent / 100.0 * 32_767.0
        val out = ByteArray(2048)
        for (i in out.indices step 2) {
            val v = amplitude.toInt()
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

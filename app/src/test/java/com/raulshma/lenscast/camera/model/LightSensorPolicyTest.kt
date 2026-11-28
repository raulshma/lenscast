package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LightSensorPolicyTest {

    @Test
    fun `the first sample only seeds a candidate`() {
        val sample = LightSensorPolicy.sample(LightSensorPolicy.State(), 123.4, 0)

        assertNull(sample.lux)
        assertNull(sample.state.publishedLux)
        assertEquals(123, sample.state.candidateLux)
        assertEquals(0L, sample.state.candidateSinceMs)
        assertEquals(123.4, sample.state.smoothedLux!!, 0.0)
    }

    @Test
    fun `the first reading goes live once the smoothed level holds for the debounce`() {
        val seeded = LightSensorPolicy.sample(LightSensorPolicy.State(), 120.0, 0)
        val holding = LightSensorPolicy.sample(seeded.state, 120.0, 200)
        val boundary = LightSensorPolicy.sample(holding.state, 120.0, 400)

        assertNull(holding.lux)
        assertEquals(120, boundary.lux)
        assertEquals(120, boundary.state.publishedLux)
        assertNull(boundary.state.candidateLux)
    }

    @Test
    fun `a first sample far from the real light is never published`() {
        // The sensor's opening sample is a bogus 0 lx while the room sits at
        // 300: the smoothed value climbs toward the truth, and every step of
        // that climb leaves the candidate's band — so nothing goes live until
        // the climb settles, and the bogus 0 never reaches a reader.
        var state = LightSensorPolicy.sample(LightSensorPolicy.State(), 0.0, 0).state
        val published = ArrayList<Int>()
        repeat(20) { index ->
            val sample = LightSensorPolicy.sample(state, 300.0, (index + 1) * 200L)
            sample.lux?.let(published::add)
            state = sample.state
        }

        assertEquals(listOf(298), published.distinct())
    }

    @Test
    fun `raw samples are clamped to the published lux bounds`() {
        val above = LightSensorPolicy.sample(LightSensorPolicy.State(), 200_000.0, 0)
        val below = LightSensorPolicy.sample(LightSensorPolicy.State(), -10.0, 0)

        assertEquals(StreamDefaults.LIGHT_SENSOR_MAX_LUX.toDouble(), above.state.smoothedLux!!, 0.0)
        assertEquals(StreamDefaults.LIGHT_SENSOR_MAX_LUX, above.state.candidateLux)
        assertEquals(0.0, below.state.smoothedLux!!, 0.0)
        assertEquals(0, below.state.candidateLux)
    }

    @Test
    fun `non-finite samples leave the policy state untouched`() {
        val state = LightSensorPolicy.State(
            smoothedLux = 100.0,
            publishedLux = 100,
            candidateLux = 120,
            candidateSinceMs = 10,
        )

        val sample = LightSensorPolicy.sample(state, Double.NaN, 500)

        assertSame(state, sample.state)
        assertEquals(100, sample.lux)
    }

    @Test
    fun `smoothing moves a quarter of the way toward each raw sample`() {
        val state = LightSensorPolicy.State(smoothedLux = 100.0, publishedLux = 100)

        val sample = LightSensorPolicy.sample(state, 200.0, 200)

        assertEquals(125.0, sample.state.smoothedLux!!, 0.0)
        assertEquals(100, sample.lux)
    }

    @Test
    fun `small jitter inside hysteresis never republishes`() {
        val state = LightSensorPolicy.State(smoothedLux = 10_000.0, publishedLux = 10_000)

        val sample = LightSensorPolicy.sample(state, 10_050.0, 1_000)

        assertEquals(10_000, sample.lux)
        assertEquals(10_000, sample.state.publishedLux)
        assertNull(sample.state.candidateLux)
    }

    @Test
    fun `the relative hysteresis band ends at one percent`() {
        val state = LightSensorPolicy.State(smoothedLux = 1_008.0, publishedLux = 1_000)

        // Rounded 1 009: still inside the 10 lux band — no candidate.
        assertNull(LightSensorPolicy.sample(state, 1_012.0, 0).state.candidateLux)
        // Rounded 1 010: exactly at the band's edge, which already counts as outside.
        assertEquals(1_010, LightSensorPolicy.sample(state, 1_016.0, 0).state.candidateLux)
    }

    @Test
    fun `the hysteresis band never shrinks below one lux`() {
        val state = LightSensorPolicy.State(smoothedLux = 0.0, publishedLux = 0)

        // Rounded 0: inside the one-lux band.
        assertNull(LightSensorPolicy.sample(state, 1.0, 0).state.candidateLux)
        // Rounded 1: a whole lux away is already outside.
        assertEquals(1, LightSensorPolicy.sample(state, 3.0, 0).state.candidateLux)
    }

    @Test
    fun `a stable crossing publishes at the debounce boundary`() {
        val initial = LightSensorPolicy.State(smoothedLux = 100.0, publishedLux = 100)
        val first = LightSensorPolicy.sample(initial, 106.0, 0)
        val before = LightSensorPolicy.sample(first.state, 106.0, 399)
        val boundary = LightSensorPolicy.sample(before.state, 106.0, 400)

        assertEquals(100, first.lux)
        assertEquals(100, before.lux)
        assertEquals(103, boundary.lux)
        assertEquals(103, boundary.state.publishedLux)
        assertNull(boundary.state.candidateLux)
    }

    @Test
    fun `a flicker to another value restarts the debounce`() {
        val initial = LightSensorPolicy.State(smoothedLux = 100.0, publishedLux = 100)
        val first = LightSensorPolicy.sample(initial, 120.0, 0)
        val flicker = LightSensorPolicy.sample(first.state, 80.0, 300)
        val tooSoon = LightSensorPolicy.sample(flicker.state, 80.0, 699)
        val settled = LightSensorPolicy.sample(tooSoon.state, 80.0, 700)

        assertEquals(100, tooSoon.lux)
        assertEquals(91, settled.lux)
    }

    @Test
    fun `dashboard demand rides the SSE count or the status-poll pulse window`() {
        val servedAt = 1_000L
        val ttl = LightSensorPolicy.STATUS_PULSE_TTL_MS

        // An SSE dashboard client holds the gate with no expiry.
        assertTrue(LightSensorPolicy.dashboardDemand(sseClients = 2, lastStatusPollMs = 0L, nowMs = servedAt))
        // A poller's pulse holds it for the TTL after its last served GET...
        assertTrue(
            LightSensorPolicy.dashboardDemand(
                sseClients = 0,
                lastStatusPollMs = servedAt,
                nowMs = servedAt + ttl,
            ),
        )
        // ...and lapses one instant past it.
        assertFalse(
            LightSensorPolicy.dashboardDemand(
                sseClients = 0,
                lastStatusPollMs = servedAt,
                nowMs = servedAt + ttl + 1,
            ),
        )
        // Nothing ever served: quiet.
        assertFalse(
            LightSensorPolicy.dashboardDemand(sseClients = 0, lastStatusPollMs = 0L, nowMs = servedAt + ttl),
        )
        // The SSE client outranks a long-lapsed pulse.
        assertTrue(
            LightSensorPolicy.dashboardDemand(
                sseClients = 1,
                lastStatusPollMs = servedAt,
                nowMs = servedAt + ttl * 10,
            ),
        )
    }

    @Test
    fun `settle finishes establishing and converges a single on-change step`() {
        var state = LightSensorPolicy.sample(LightSensorPolicy.State(), 120.0, 0L).state
        assertTrue(LightSensorPolicy.needsSettle(state))

        state = LightSensorPolicy.settle(state, 200L).state
        assertNull(state.publishedLux)
        assertTrue(LightSensorPolicy.needsSettle(state))

        val established = LightSensorPolicy.settle(state, 400L)
        state = established.state
        assertEquals(120, established.lux)
        assertFalse(LightSensorPolicy.needsSettle(state))

        // A single step down to 5 lx must keep needing settle until the EMA converges.
        val step = LightSensorPolicy.sample(state, 5.0, 1_000L)
        state = step.state
        assertEquals(120, step.lux)
        assertTrue(LightSensorPolicy.needsSettle(state))

        var now = 1_000L
        var ticks = 0
        while (LightSensorPolicy.needsSettle(state) && ticks < 50) {
            now += LightSensorPolicy.SETTLE_INTERVAL_MS
            state = LightSensorPolicy.settle(state, now).state
            ticks++
        }
        assertFalse(LightSensorPolicy.needsSettle(state))
        assertEquals(5, state.publishedLux)
    }

    @Test
    fun `needsSettle terminates cleanly even on an exact half-lux rounding boundary`() {
        var state = LightSensorPolicy.State(
            smoothedLux = 103.0,
            publishedLux = 103,
            lastRawLux = 103.0,
        )
        state = LightSensorPolicy.sample(state, 104.5, 0L).state
        var now = 0L
        var ticks = 0
        while (LightSensorPolicy.needsSettle(state) && ticks < 50) {
            now += LightSensorPolicy.SETTLE_INTERVAL_MS
            state = LightSensorPolicy.settle(state, now).state
            ticks++
        }
        assertFalse(LightSensorPolicy.needsSettle(state))
        assertTrue(ticks < 50)
    }
}

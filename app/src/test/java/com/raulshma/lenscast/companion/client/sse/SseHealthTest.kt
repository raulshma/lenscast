package com.raulshma.lenscast.companion.client.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven pin of the freshness watchdog — the vitest table of
 * web/src/hooks/sseHealth.test.ts, extended for the added Stale rung. Every
 * threshold mirrors its TS origin (see the constant table in [SseHealth]).
 */
class SseHealthTest {

    private val now = 10_000_000L
    private val health = SseHealth()

    // ── TS parity: the healthy window ──

    @Test
    fun `a fresh open socket with no events yet is healthy`() {
        // TS case 1: the onopen handler stamps both clocks (the Kotlin caller
        // stamps lastEventAtMs on open) — a socket that just answered must
        // not read as dead at birth.
        assertEquals(SseHealth.SseHealthState.Healthy, health.evaluate(now, now))
        assertTrue(health.laneHealthy(now, now))
    }

    @Test
    fun `holds the TS 5s boundary at the healthy-stale edge`() {
        // TS case 2: 4999 ms alive-grace healthy, 5000 ms not.
        assertEquals(SseHealth.SseHealthState.Healthy, health.evaluate(now, now - 4_999))
        assertEquals(SseHealth.SseHealthState.Stale, health.evaluate(now, now - 5_000))
        // TS case 3: 4999 ms of event silence healthy, SSE_STALE_MS not.
        assertTrue(health.laneHealthy(now, now - (SseHealth.TS_SSE_STALE_MS - 1)))
        assertFalse(health.laneHealthy(now, now - SseHealth.TS_SSE_STALE_MS))
    }

    @Test
    fun `keeps the freshness bar at its production value`() {
        // TS "keeps the freshness bar at its production value": the ratio
        // form must still materialize as exactly 5000 ms at the 1s interval.
        assertEquals(5_000L, (SseHealth.DEFAULT_EXPECTED_INTERVAL_MS * SseHealth.DEFAULT_GRACE_FACTOR).toLong())
        assertEquals(5_000L, SseHealth.TS_SSE_STALE_MS)
    }

    // ── The added Stale rung (no TS equivalent) ──

    @Test
    fun `stale band spans the grace bar to the dead bar`() {
        val table = listOf(
            5_000L to SseHealth.SseHealthState.Stale,
            6_000L to SseHealth.SseHealthState.Stale,
            9_999L to SseHealth.SseHealthState.Stale,
        )
        for ((silenceMs, expected) in table) {
            assertEquals("silence=$silenceMs", expected, health.evaluate(now, now - silenceMs))
        }
    }

    @Test
    fun `dead bar lands at twice the stale point`() {
        val table = listOf(
            10_000L to SseHealth.SseHealthState.Dead,
            60_000L to SseHealth.SseHealthState.Dead,
            1_000_000L to SseHealth.SseHealthState.Dead,
        )
        for ((silenceMs, expected) in table) {
            assertEquals("silence=$silenceMs", expected, health.evaluate(now, now - silenceMs))
        }
    }

    @Test
    fun `whole freshness table at the production constants`() {
        val table = listOf(
            0L to SseHealth.SseHealthState.Healthy,
            1_000L to SseHealth.SseHealthState.Healthy, // one missed 1s push
            4_999L to SseHealth.SseHealthState.Healthy,
            5_000L to SseHealth.SseHealthState.Stale,
            7_500L to SseHealth.SseHealthState.Stale,
            9_999L to SseHealth.SseHealthState.Stale,
            10_000L to SseHealth.SseHealthState.Dead,
            45_000L to SseHealth.SseHealthState.Dead,
        )
        for ((silenceMs, expected) in table) {
            assertEquals("silence=$silenceMs", expected, health.evaluate(now, now - silenceMs))
        }
    }

    @Test
    fun `expected interval scales the bar, not a hardcoded millisecond value`() {
        // A device variant pushing every 500 ms keeps the 5-interval grace:
        // stale at 5 * 500 = 2500 ms, dead at 5000 ms.
        assertEquals(SseHealth.SseHealthState.Healthy, health.evaluate(now, now - 2_499, expectedIntervalMs = 500))
        assertEquals(SseHealth.SseHealthState.Stale, health.evaluate(now, now - 2_500, expectedIntervalMs = 500))
        assertEquals(SseHealth.SseHealthState.Dead, health.evaluate(now, now - 5_000, expectedIntervalMs = 500))
    }

    @Test
    fun `laneHealthy equals the TS boolean at the same boundary`() {
        // The poll ladder's sseHealthy input and the web's sseLaneHealthy
        // must never disagree with the three-state verdict's healthy edge.
        for (ageMs in longArrayOf(0, 1_000, 4_998, 4_999, 5_000, 5_001, 9_999, 10_000)) {
            assertEquals(
                "age=$ageMs",
                ageMs < 5_000,
                health.laneHealthy(now, now - ageMs) &&
                    (health.evaluate(now, now - ageMs) == SseHealth.SseHealthState.Healthy),
            )
        }
    }
}

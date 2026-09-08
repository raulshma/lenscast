package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWatchdogBackoffTest {

    @Test
    fun `backoff doubles per attempt`() {
        assertEquals(2_000L, WatchdogPolicy.backoffMs(1))
        assertEquals(4_000L, WatchdogPolicy.backoffMs(2))
        assertEquals(8_000L, WatchdogPolicy.backoffMs(3))
        assertEquals(16_000L, WatchdogPolicy.backoffMs(4))
    }

    @Test
    fun `backoff caps the doubling exponent at six attempts`() {
        // 2000 * 2^6 = 128000 clamped to the 60s cap
        assertEquals(60_000L, WatchdogPolicy.backoffMs(7))
        assertEquals(60_000L, WatchdogPolicy.backoffMs(20))
    }

    @Test
    fun `backoff never exceeds the max cap`() {
        for (attempt in 1..30) {
            assertTrue(WatchdogPolicy.backoffMs(attempt) <= 60_000L)
        }
        assertEquals(60_000L, WatchdogPolicy.backoffMs(6))
        assertEquals(60_000L, WatchdogPolicy.backoffMs(30))
    }
}

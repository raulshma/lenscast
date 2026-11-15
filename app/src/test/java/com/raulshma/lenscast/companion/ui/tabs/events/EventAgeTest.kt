package com.raulshma.lenscast.companion.ui.tabs.events

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The relative-time bucket's pure rules, pinned at every boundary: the
 * minute/hour/day cuts, the bucket counts, and the device-clock-ahead clamp
 * (a negative delta reads "just now", never a negative count).
 */
class EventAgeTest {

    private val nowMs = 1_000_000_000_000L

    @Test
    fun `under a minute reads just now`() {
        assertEquals(EventAge.JustNow, eventAge(eventMs = nowMs, nowMs = nowMs))
        assertEquals(EventAge.JustNow, eventAge(eventMs = nowMs - 59_999, nowMs = nowMs))
    }

    @Test
    fun `a future timestamp clamps to just now`() {
        // The device's clock runs ahead: no negative buckets, ever.
        assertEquals(EventAge.JustNow, eventAge(eventMs = nowMs + 5_000, nowMs = nowMs))
        assertEquals(EventAge.JustNow, eventAge(eventMs = nowMs + 86_400_000, nowMs = nowMs))
    }

    @Test
    fun `exactly one minute opens the minutes bucket`() {
        assertEquals(EventAge.Minutes(1), eventAge(eventMs = nowMs - 60_000, nowMs = nowMs))
    }

    @Test
    fun `minutes count whole minutes below the hour`() {
        assertEquals(EventAge.Minutes(2), eventAge(eventMs = nowMs - 2 * 60_000, nowMs = nowMs))
        assertEquals(EventAge.Minutes(59), eventAge(eventMs = nowMs - 59 * 60_000, nowMs = nowMs))
    }

    @Test
    fun `exactly one hour opens the hours bucket`() {
        assertEquals(EventAge.Hours(1), eventAge(eventMs = nowMs - 3_600_000, nowMs = nowMs))
    }

    @Test
    fun `hours count whole hours below the day`() {
        assertEquals(EventAge.Hours(5), eventAge(eventMs = nowMs - 5 * 3_600_000, nowMs = nowMs))
        assertEquals(EventAge.Hours(23), eventAge(eventMs = nowMs - 23 * 3_600_000, nowMs = nowMs))
    }

    @Test
    fun `exactly one day opens the days bucket`() {
        assertEquals(EventAge.Days(1), eventAge(eventMs = nowMs - 86_400_000, nowMs = nowMs))
    }

    @Test
    fun `days count whole days past that`() {
        assertEquals(EventAge.Days(3), eventAge(eventMs = nowMs - 3 * 86_400_000, nowMs = nowMs))
        // 365L: the product must be Long — 365 * 86_400_000 overflows Int.
        assertEquals(EventAge.Days(365), eventAge(eventMs = nowMs - 365L * 86_400_000, nowMs = nowMs))
    }
}

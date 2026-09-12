package com.raulshma.lenscast.streaming.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The event feed's `?day=YYYY-MM-DD` filter window, pinned: a strict calendar
 * day in device-local time, DST-honoring (the end is the next local midnight,
 * never start + 24 h), malformed inputs rejected as a whole — the handler
 * turns a null window into the handler-error payload, never a silently
 * filtered or silently unfiltered feed.
 */
class DetectionEventDayFilterTest {

    private val zone = TimeZone.getTimeZone("Europe/Berlin")

    @Test
    fun `a valid day yields its local midnight window`() {
        val (start, end) = DetectionEventDayFilter.windowOrNull("2026-09-10", zone)!!
        val cal = Calendar.getInstance(zone).apply {
            clear()
            set(2026, 8, 10, 0, 0, 0)
        }
        assertEquals(cal.timeInMillis, start)
        assertEquals(cal.timeInMillis + 24 * 3_600_000L, end)
    }

    @Test
    fun `a dst-transition day is shorter than 24 hours, not longer`() {
        // 2026-03-29 is the spring-forward day in Europe/Berlin: local midnight
        // to next local midnight is 23 h, so a fixed +24 h would double-count.
        val (start, end) = DetectionEventDayFilter.windowOrNull("2026-03-29", zone)!!
        assertEquals(23 * 3_600_000L, end - start)
        // And the autumn back-transition day is 25 h.
        val (_, fallEnd) = DetectionEventDayFilter.windowOrNull("2026-10-25", zone)!!
        val fallStart = DetectionEventDayFilter.windowOrNull("2026-10-25", zone)!!.first
        assertEquals(25 * 3_600_000L, fallEnd - fallStart)
    }

    @Test
    fun `the window is half-open and ordered`() {
        val (start, end) = DetectionEventDayFilter.windowOrNull("2026-09-10", zone)!!
        // The list filter treats the window as [start, end): an event stamped
        // exactly at this day's midnight belongs, one at the next midnight
        // belongs to the next day.
        assertTrue(start < end)
        assertEquals(start, DetectionEventDayFilter.windowOrNull("2026-09-10", zone)!!.first)
    }

    @Test
    fun `malformed shapes are rejected`() {
        assertNull(DetectionEventDayFilter.windowOrNull(null, zone))
        assertNull(DetectionEventDayFilter.windowOrNull("", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("2026-9-10", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("10-09-2026", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("2026-09-10T00:00", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("yesterday", zone))
    }

    @Test
    fun `impossible calendar dates are rejected, not leniently rolled over`() {
        assertNull(DetectionEventDayFilter.windowOrNull("2026-02-30", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("2026-13-01", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("2026-00-10", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("2026-09-00", zone))
        assertNull(DetectionEventDayFilter.windowOrNull("2025-02-29", zone))
        // The leap year's own day is real.
        assertEquals(true, DetectionEventDayFilter.windowOrNull("2024-02-29", zone) != null)
    }

    @Test
    fun `a blank or absent day means no filter`() {
        assertNull(DetectionEventDayFilter.windowOrNull(null, zone))
        assertNull(DetectionEventDayFilter.windowOrNull("  ", zone))
    }
}

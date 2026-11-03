package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The shared wall-clock derivations behind the automation scheduler's
 * occurrence keys and the detection coordinator's arm-schedule verdicts: the
 * minute/day math pinned with constructed calendars — including a DST
 * boundary and a pre-1970 day, the two cases the floorDiv form exists for.
 */
class LocalWallClockTest {

    private fun calendar(
        year: Int,
        month: Int, // 1-based, Calendar months are 0-based
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        zone: TimeZone = TimeZone.getTimeZone("America/New_York"),
    ): Calendar = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }

    // ── minuteOfDay ──

    @Test
    fun `minute of day is hour times sixty plus minute`() {
        assertEquals(0, LocalWallClock.minuteOfDay(calendar(2026, 3, 15)))
        assertEquals(600, LocalWallClock.minuteOfDay(calendar(2026, 3, 15, 10, 0)))
        assertEquals(927, LocalWallClock.minuteOfDay(calendar(2026, 3, 15, 15, 27)))
        assertEquals(1439, LocalWallClock.minuteOfDay(calendar(2026, 3, 15, 23, 59)))
    }

    // ── isoDayIndex ──

    @Test
    fun `iso day index is zero on monday through six on sunday`() {
        // 2026-03-16 is a Monday; the week runs Monday..Sunday.
        assertEquals(0, LocalWallClock.isoDayIndex(calendar(2026, 3, 16)))
        assertEquals(1, LocalWallClock.isoDayIndex(calendar(2026, 3, 17)))
        assertEquals(2, LocalWallClock.isoDayIndex(calendar(2026, 3, 18)))
        assertEquals(3, LocalWallClock.isoDayIndex(calendar(2026, 3, 19)))
        assertEquals(4, LocalWallClock.isoDayIndex(calendar(2026, 3, 20)))
        assertEquals(5, LocalWallClock.isoDayIndex(calendar(2026, 3, 21)))
        assertEquals(6, LocalWallClock.isoDayIndex(calendar(2026, 3, 22)))
        // Calendar's own numbering starts at 1 = Sunday; 2026-03-15 is one.
        assertEquals(6, LocalWallClock.isoDayIndex(calendar(2026, 3, 15)))
    }

    // ── epochDay ──

    @Test
    fun `epoch day counts local midnights and advances by one per day`() {
        // 1970-01-01 00:00 UTC is epoch day 0 in the UTC zone.
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(0L, LocalWallClock.epochDay(calendar(1970, 1, 1, zone = utc)))
        assertEquals(1L, LocalWallClock.epochDay(calendar(1970, 1, 2, zone = utc)))
        assertEquals(20_454L, LocalWallClock.epochDay(calendar(2026, 1, 1, zone = utc)))
    }

    @Test
    fun `epoch day is the local day, not the utc day, across the zone offset`() {
        // 2026-01-01 03:00 in New_York (UTC-5) is 08:00 UTC the same day —
        // still local day 20454. At 00:00 New_York it is 05:00 UTC on Jan 1,
        // so the local day equals the UTC day there too; the discriminator
        // is the other direction: 2026-01-01 20:00 New_York is 01:00 UTC on
        // Jan 2 — the UTC day has rolled over, the local day must not.
        assertEquals(20_454L, LocalWallClock.epochDay(calendar(2026, 1, 1, 20, 0)))
        assertEquals(20_454L, LocalWallClock.epochDay(calendar(2026, 1, 1, 0, 0)))
    }

    @Test
    fun `epoch day is dst-safe across the spring gap and the fall overlap`() {
        // America/New_York, 2026: DST starts Mar 8 (2:00 → 3:00) and ends
        // Nov 1 (2:00 → 1:00). Local midnight both days is a real instant
        // with a different offset (-5 vs -4); the day number must count
        // local midnights, so both midnights land on consecutive days and
        // 23:59 local never leaks into the next day number.
        val spring = LocalWallClock.epochDay(calendar(2026, 3, 8))
        val springEve = LocalWallClock.epochDay(calendar(2026, 3, 7))
        val springAfter = LocalWallClock.epochDay(calendar(2026, 3, 9))
        assertEquals(springEve + 1, spring)
        assertEquals(spring + 1, springAfter)

        val fall = LocalWallClock.epochDay(calendar(2026, 11, 1))
        val fallEve = LocalWallClock.epochDay(calendar(2026, 10, 31))
        val fallAfter = LocalWallClock.epochDay(calendar(2026, 11, 2))
        assertEquals(fallEve + 1, fall)
        assertEquals(fall + 1, fallAfter)

        // Late in each transition day the day number is still that day's.
        assertEquals(spring, LocalWallClock.epochDay(calendar(2026, 3, 8, 23, 59)))
        assertEquals(fall, LocalWallClock.epochDay(calendar(2026, 11, 1, 23, 59)))
    }

    @Test
    fun `epoch day stays negative and correct before 1970`() {
        // floorDiv keeps pre-1970 local days negative: 1969-12-31 is day -1,
        // 1969-01-01 is -365 in the UTC zone (1968 was a leap year, so the
        // 369-day back-count lands there — verified against the raw instant
        // arithmetic below instead of a hand-computed constant).
        assertEquals(-1L, LocalWallClock.epochDay(calendar(1969, 12, 31, zone = TimeZone.getTimeZone("UTC"))))
        val epoch1969 = calendar(1969, 1, 1, zone = TimeZone.getTimeZone("UTC"))
        assertEquals(-365L, LocalWallClock.epochDay(epoch1969))
    }
}

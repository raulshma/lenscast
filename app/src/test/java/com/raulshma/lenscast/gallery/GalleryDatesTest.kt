package com.raulshma.lenscast.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * GalleryDates is the API-23 replacement for java.time; java.time itself
 * (JVM-only) is the oracle: civil conversions across a wide sweep, and the
 * local-day boundary across offsets and DST transitions.
 */
class GalleryDatesTest {

    // ── civil calendar conversions ──

    @Test
    fun `civil conversions round-trip against localdate over a wide sweep`() {
        var epochDay = -30_000L // ~1888
        while (epochDay <= 30_000L) { // ~2052
            val date = LocalDate.ofEpochDay(epochDay)
            val civil = GalleryDates.civilFromDays(epochDay)
            assertEquals("year of $epochDay", date.year, civil[0])
            assertEquals("month of $epochDay", date.monthValue, civil[1])
            assertEquals("day of $epochDay", date.dayOfMonth, civil[2])
            assertEquals("isoDate of $epochDay", date.toString(), GalleryDates.isoDate(epochDay))
            assertEquals("daysFromCivil of $epochDay", epochDay, GalleryDates.daysFromCivil(civil[0], civil[1], civil[2]))
            epochDay += 97 // coprime with 4/100/400-year cycles, so leap days get hit
        }
    }

    @Test
    fun `civil conversions pin known dates`() {
        assertEquals(intArrayOf(1970, 1, 1).toList(), GalleryDates.civilFromDays(0L).toList())
        assertEquals(intArrayOf(2000, 2, 29).toList(), GalleryDates.civilFromDays(LocalDate.of(2000, 2, 29).toEpochDay()).toList())
        assertEquals(intArrayOf(2026, 9, 9).toList(), GalleryDates.civilFromDays(LocalDate.of(2026, 9, 9).toEpochDay()).toList())
    }

    // ── local-day boundary ──

    @Test
    fun `epochDayOf and dayStartMillis agree with java_time across zones and dst transitions`() {
        // UTC, a whole-hour DST zone, and a half-hour zone with 30-minute DST.
        checkZone("UTC")
        checkZone("America/New_York")
        checkZone("Australia/Lord_Howe")
    }

    private fun checkZone(zoneId: String) {
        val timeZone = TimeZone.getTimeZone(zoneId)
        val zone = ZoneId.of(zoneId)

        // 2026-03-08 is the US spring-forward Sunday; Lord_Howe shifts 2026-10-04.
        var day = LocalDate.of(2026, 3, 6)
        repeat(8) {
            val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()

            assertEquals("$zoneId midnight day of $day", day.toEpochDay(), GalleryDates.epochDayOf(midnight, timeZone))
            assertEquals("$zoneId pre-midnight belongs to the prior day", day.toEpochDay() - 1, GalleryDates.epochDayOf(midnight - 1, timeZone))
            assertEquals("$zoneId dayStartMillis of $day", midnight, GalleryDates.dayStartMillis(day.toEpochDay(), timeZone))

            // Wall-clock hours, not plusHours(): absolute durations would cross
            // the spring-forward gap and land on the wrong calendar day.
            for (hour in longArrayOf(5, 12, 23)) {
                val ts = day.atTime(hour.toInt(), 0).atZone(zone).toInstant().toEpochMilli()
                assertEquals("$zoneId $hour:00 of $day", day.toEpochDay(), GalleryDates.epochDayOf(ts, timeZone))
            }
            day = day.plusDays(1)
        }
    }
}

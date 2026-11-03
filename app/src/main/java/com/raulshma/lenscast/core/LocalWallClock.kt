package com.raulshma.lenscast.core

import java.util.Calendar

/**
 * The shared local wall-clock derivations over a [Calendar]: minute of day,
 * ISO day index, and the local epoch day. The automation scheduler's
 * occurrence keys and the detection coordinator's arm-schedule/quiet-hours
 * verdicts both need the same three numbers from the same clock, so the math
 * lives once — a coordinator that disagreed with the scheduler about "today"
 * would arm and dedup on different days, which is the drift class this
 * deletes.
 *
 * Every function is pure over the calendar passed in: callers own the clock
 * (a `Calendar.getInstance()` seeded with their injected now), so the math is
 * JVM-testable with constructed calendars — no Android, no implicit now().
 */
object LocalWallClock {

    /** Minutes since local midnight: `HOUR_OF_DAY * 60 + MINUTE`. */
    fun minuteOfDay(calendar: Calendar): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    /**
     * The ISO day index the day-mask bits reference (0 = Monday … 6 =
     * Sunday), derived from Calendar's 1=Sunday..7=Saturday numbering.
     */
    fun isoDayIndex(calendar: Calendar): Int =
        (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7

    /**
     * Local wall-clock day number, DST-safe: adding the zone offset plus
     * the DST offset in effect at this instant turns the UTC epoch into
     * local milliseconds, and floorDiv to days counts local-midnight to
     * local-midnight days (floorDiv keeps pre-1970 days negative and
     * correct). The same number the automation occurrence key is built from.
     */
    fun epochDay(calendar: Calendar): Long =
        Math.floorDiv(
            calendar.timeInMillis + calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET),
            86_400_000L,
        )
}

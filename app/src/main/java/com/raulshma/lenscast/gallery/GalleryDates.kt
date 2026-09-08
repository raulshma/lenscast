package com.raulshma.lenscast.gallery

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Epoch-day arithmetic for the gallery's day grouping. java.time is API 26+,
 * so this is the minSdk-23 equivalent: java.util.Calendar for the local-day
 * boundary (DST-aware) and pure civil-calendar conversions for everything
 * else. The JVM tests cross-check every function against java.time.
 */
internal object GalleryDates {

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    fun todayEpochDay(timeZone: TimeZone = TimeZone.getDefault()): Long =
        epochDayOf(System.currentTimeMillis(), timeZone)

    /** The local calendar day [timestampMs] falls in, as days since 1970-01-01. */
    fun epochDayOf(timestampMs: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = timestampMs
        val offsetMs = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)
        return floorDiv(timestampMs + offsetMs, MILLIS_PER_DAY)
    }

    /** Milliseconds since the epoch of local midnight starting [epochDay]. */
    fun dayStartMillis(epochDay: Long, timeZone: TimeZone): Long {
        val civil = civilFromDays(epochDay)
        val calendar = Calendar.getInstance(timeZone)
        calendar.clear()
        calendar.set(civil[0], civil[1] - 1, civil[2], 0, 0, 0)
        return calendar.timeInMillis
    }

    /** ISO-8601 "yyyy-MM-dd" of [epochDay] — the stable gallery section key. */
    fun isoDate(epochDay: Long): String {
        val civil = civilFromDays(epochDay)
        return String.format(Locale.US, "%04d-%02d-%02d", civil[0], civil[1], civil[2])
    }

    /** Howard Hinnant's civil_from_days: days since 1970-01-01 to {year, month, day}. */
    internal fun civilFromDays(epochDay: Long): IntArray {
        val z = epochDay + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097 // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
        val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
        return intArrayOf((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    /** days_from_civil, the inverse of [civilFromDays]. */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = floorDiv(y.toLong(), 400)
        val yoe = y - era * 400 // [0, 399]
        val mp = (month + 9) % 12 // [0, 11]
        val doy = (153 * mp + 2) / 5 + day - 1 // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
        return era * 146_097 + doe - 719_468
    }

    /** java.lang.Math.floorDiv is API 24+; this is the manual equivalent. */
    private fun floorDiv(value: Long, divisor: Long): Long {
        var quotient = value / divisor
        if (value % divisor != 0L && (value < 0) != (divisor < 0)) quotient--
        return quotient
    }
}

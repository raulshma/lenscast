package com.raulshma.lenscast.capture

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The pure aggregation behind GET /api/detection/stats: per-type counts over
 * the 24 h / 7 d / all-time windows, a per-UTC-day series for the recent
 * stretch, and the most-fired zone/ML labels. Caller supplies [nowMs], so the
 * whole ladder is JVM-tested; the store only lends its list.
 */
object DetectionStatsPolicy {

    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val RECENT_DAYS = 7
    private const val TOP_LABELS = 5

    data class Stats(
        val last24h: Map<String, Int>,
        val last7d: Map<String, Int>,
        val allTime: Map<String, Int>,
        /** Per-UTC-day totals over the recent window, oldest day first. */
        val perDay: List<Pair<String, Int>>,
        val totalEvents: Int,
        val topZones: List<Pair<String, Int>>,
        val topLabels: List<Pair<String, Int>>,
    )

    fun build(events: List<DetectionEvent>, nowMs: Long): Stats {
        val since24h = nowMs - DAY_MS
        val since7d = nowMs - RECENT_DAYS * DAY_MS
        return Stats(
            last24h = countsByType(events.filter { it.timestampMs >= since24h }),
            last7d = countsByType(events.filter { it.timestampMs >= since7d }),
            allTime = countsByType(events),
            perDay = perDayCounts(events, nowMs),
            totalEvents = events.size,
            topZones = topCounts(events.flatMap { it.zones }),
            topLabels = topCounts(events.flatMap { it.labels }),
        )
    }

    private fun countsByType(events: List<DetectionEvent>): Map<String, Int> =
        events.groupingBy { it.type }.eachCount()

    /** Totals per UTC day over the recent window, oldest first, zero days included. */
    private fun perDayCounts(events: List<DetectionEvent>, nowMs: Long): List<Pair<String, Int>> {
        val eventsByDay = events.groupingBy { dayKeyOf(it.timestampMs) }.eachCount()
        val days = mutableListOf<Pair<String, Int>>()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        for (offset in RECENT_DAYS - 1 downTo 0) {
            utc.timeInMillis = nowMs - offset * DAY_MS
            val day = dayKey(utc)
            days.add(day to (eventsByDay[day] ?: 0))
        }
        return days
    }

    private fun topCounts(labels: List<String>): List<Pair<String, Int>> =
        labels.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_LABELS)
            .map { it.key to it.value }

    /** "yyyy-MM-dd" for a UTC millisecond stamp. */
    fun dayKeyOf(timestampMs: Long): String {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = timestampMs
        return dayKey(utc)
    }

    private fun dayKey(utc: Calendar): String = String.format(
        Locale.US,
        "%04d-%02d-%02d",
        utc.get(Calendar.YEAR),
        utc.get(Calendar.MONTH) + 1,
        utc.get(Calendar.DAY_OF_MONTH),
    )
}

package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionStatsPolicyTest {

    private fun event(type: String, timestampMs: Long, zones: List<String> = emptyList(), labels: List<String> = emptyList()) =
        DetectionEvent(id = "$type-$timestampMs", type = type, source = "lenscast", timestampMs = timestampMs, zones = zones, labels = labels)

    @Test
    fun `windows split at the 24h and 7d boundaries`() {
        val now = 1_788_825_600_000L // 2026-09-09T00:00:00Z (a UTC midnight)
        val day = 24 * 60 * 60 * 1000L
        val events = listOf(
            event("motion", now - 1000),
            event("sound", now - 23 * 60 * 60 * 1000),
            event("motion", now - 3 * day),
            event("tamper", now - 10 * day),
        )
        val stats = DetectionStatsPolicy.build(events, now)
        assertEquals(mapOf("motion" to 1, "sound" to 1), stats.last24h)
        assertEquals(mapOf("motion" to 2, "sound" to 1), stats.last7d)
        assertEquals(mapOf("motion" to 2, "sound" to 1, "tamper" to 1), stats.allTime)
        assertEquals(4, stats.totalEvents)
    }

    @Test
    fun `per-day series covers seven UTC days oldest first with zeros`() {
        val now = 1_788_825_600_000L
        val day = 24 * 60 * 60 * 1000L
        val events = listOf(
            event("motion", now),
            event("sound", now - day),
            event("motion", now - day),
        )
        val stats = DetectionStatsPolicy.build(events, now)
        assertEquals(7, stats.perDay.size)
        assertEquals("2026-09-02", stats.perDay.first().first)
        assertEquals("2026-09-08", stats.perDay.last().first)
        assertEquals(2, stats.perDay.last { it.first == "2026-09-07" }.second)
        assertEquals(1, stats.perDay.last { it.first == "2026-09-08" }.second)
        assertEquals(0, stats.perDay.last { it.first == "2026-09-02" }.second)
    }

    @Test
    fun `top zones and labels rank by count then name`() {
        val now = 1_788_825_600_000L
        val events = (0 until 3).map { event("motion", now - it, zones = listOf("B")) } +
            (0 until 2).map { event("motion", now - 10 - it, zones = listOf("A")) } +
            listOf(
                event("motion", now - 20, labels = listOf("person", "car")),
                event("motion", now - 21, labels = listOf("person")),
            )
        val stats = DetectionStatsPolicy.build(events, now)
        assertEquals(listOf("B" to 3, "A" to 2), stats.topZones)
        assertEquals(listOf("person" to 2, "car" to 1), stats.topLabels)
    }

    @Test
    fun `empty log yields empty maps and a zeroed series`() {
        val stats = DetectionStatsPolicy.build(emptyList(), nowMs = 1_788_825_600_000L)
        assertEquals(emptyMap<String, Int>(), stats.allTime)
        assertEquals(0, stats.totalEvents)
        assertEquals(7, stats.perDay.size)
        assertEquals(0, stats.perDay.sumOf { it.second })
    }
}

package com.raulshma.lenscast.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alert-policy pins: the new-event verdict over the newest-first feed
 * (first-contact baseline, id dedup, timestamp floor, freshness gate, ring
 * rotation), the banner detail line, and the age ladder — the watch-side
 * twin of the phone's event-sequence policy tests.
 */
class WearAlertPolicyTest {

    private fun event(
        id: String,
        timestampMs: Long,
        type: String = "motion",
        zones: List<String> = emptyList(),
        labels: List<String> = emptyList(),
    ) = WearDetectionEvent(id = id, type = type, timestampMs = timestampMs, zones = zones, labels = labels)

    private val now = 1_000_000L

    @Test fun `first contact baselines silently without alerting on history`() {
        val feed = listOf(event("newest", 900), event("older", 500))
        val verdict = WearAlertPolicy.evaluate(feed, seen = null, nowMs = now)
        assertTrue(verdict.newEvents.isEmpty())
        val seen = verdict.nextSeen
        assertEquals(900L, seen?.lastTimestampMs)
        assertEquals(listOf("newest", "older"), seen?.seenIds)
    }

    @Test fun `first contact with empty feed stays null so the first event alerts`() {
        val verdict = WearAlertPolicy.evaluate(emptyList(), seen = null, nowMs = now)
        assertTrue(verdict.newEvents.isEmpty())
        assertNull(verdict.nextSeen)
    }

    @Test fun `unseen newer id alerts and advances the floor`() {
        // Timestamps near `now`: the freshness gate must not eat the dedup
        // verdict under test.
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = now - 2_000, seenIds = listOf("a"))
        val feed = listOf(event("b", now - 1_000), event("a", now - 2_000))
        val verdict = WearAlertPolicy.evaluate(feed, seen, nowMs = now)
        assertEquals(listOf("b"), verdict.newEvents.map { it.id })
        assertEquals(now - 1_000, verdict.nextSeen?.lastTimestampMs)
    }

    @Test fun `already seen id never re-alerts`() {
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = 700, seenIds = listOf("b", "a"))
        val feed = listOf(event("b", 700), event("a", 500))
        val verdict = WearAlertPolicy.evaluate(feed, seen, nowMs = now)
        assertTrue(verdict.newEvents.isEmpty())
        assertEquals(seen, verdict.nextSeen)
    }

    @Test fun `same-millisecond unseen sibling alerts once then dedups by id`() {
        val stamp = now - 5_000
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = stamp, seenIds = listOf("a"))
        val first = WearAlertPolicy.evaluate(listOf(event("b2", stamp), event("a", stamp - 200)), seen, nowMs = now)
        assertEquals(listOf("b2"), first.newEvents.map { it.id })
        // The very next poll sees the same page: b2 is in the ring now.
        val second = WearAlertPolicy.evaluate(
            listOf(event("b2", stamp), event("a", stamp - 200)),
            first.nextSeen,
            nowMs = now,
        )
        assertTrue(second.newEvents.isEmpty())
    }

    @Test fun `stale unseen entry below the floor is absorbed not alerted`() {
        // A phone-side log clear/re-sync can surface unseen ids with old
        // timestamps; the floor swallows them and they still enter the ring.
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = 900, seenIds = listOf("new"))
        val feed = listOf(event("new", 900), event("ancient-unseen", 100))
        val verdict = WearAlertPolicy.evaluate(feed, seen, nowMs = now)
        assertTrue(verdict.newEvents.isEmpty())
        assertTrue(verdict.nextSeen?.seenIds?.contains("ancient-unseen") == true)
    }

    @Test fun `unseen event older than the freshness window updates ring silently`() {
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = 500, seenIds = listOf("a"))
        val staleMs = now - WearAlertPolicy.MAX_ALERT_AGE_MS - 1_000
        val verdict = WearAlertPolicy.evaluate(listOf(event("late", staleMs), event("a", 500)), seen, nowMs = now)
        assertTrue(verdict.newEvents.isEmpty())
        assertEquals(listOf("late", "a"), verdict.nextSeen?.seenIds)
        assertEquals(staleMs, verdict.nextSeen?.lastTimestampMs)
    }

    @Test fun `unseen event just inside the freshness window alerts`() {
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = 500, seenIds = listOf("a"))
        val freshMs = now - WearAlertPolicy.MAX_ALERT_AGE_MS + 1_000
        val verdict = WearAlertPolicy.evaluate(listOf(event("fresh", freshMs), event("a", 500)), seen, nowMs = now)
        assertEquals(listOf("fresh"), verdict.newEvents.map { it.id })
    }

    @Test fun `burst returns chronological events with the newest last`() {
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = now - 300, seenIds = listOf("a"))
        val feed = listOf(event("c", now - 100), event("b", now - 200), event("a", now - 300))
        val verdict = WearAlertPolicy.evaluate(feed, seen, nowMs = now)
        assertEquals(listOf("b", "c"), verdict.newEvents.map { it.id })
    }

    @Test fun `ring stays bounded and newest-first across polls`() {
        val seen = WearAlertPolicy.SeenState(lastTimestampMs = 0, seenIds = (1..WearAlertPolicy.SEEN_RING_SIZE).map { "old$it" })
        val feed = listOf(event("n2", 20), event("n1", 10))
        val next = WearAlertPolicy.evaluate(feed, seen, nowMs = now).nextSeen!!
        assertEquals(WearAlertPolicy.SEEN_RING_SIZE, next.seenIds.size)
        assertEquals(listOf("n2", "n1"), next.seenIds.take(2))
    }

    @Test fun `detail line prefers labels then zones then collapses`() {
        assertEquals("person, dog", WearAlertPolicy.detailLine(event("x", 0, labels = listOf("person", "dog"))))
        assertEquals("Front door", WearAlertPolicy.detailLine(event("x", 0, zones = listOf("Front door"))))
        assertEquals("", WearAlertPolicy.detailLine(event("x", 0)))
    }

    @Test fun `age ladder buckets`() {
        assertTrue(WearAlertPolicy.relativeAge(now - 5_000, now) is WearAlertPolicy.AgeBucket.Now)
        assertEquals(
            WearAlertPolicy.AgeBucket.Seconds(30),
            WearAlertPolicy.relativeAge(now - 30_000, now),
        )
        assertEquals(
            WearAlertPolicy.AgeBucket.Minutes(5),
            WearAlertPolicy.relativeAge(now - 5 * 60_000, now),
        )
        assertEquals(
            WearAlertPolicy.AgeBucket.Hours(2),
            WearAlertPolicy.relativeAge(now - 2 * 3_600_000, now),
        )
    }

    @Test fun `future timestamp clamps to now`() {
        assertTrue(WearAlertPolicy.relativeAge(now + 60_000, now) is WearAlertPolicy.AgeBucket.Now)
    }
}

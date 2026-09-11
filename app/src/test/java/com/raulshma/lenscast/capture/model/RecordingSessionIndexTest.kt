package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.DetectionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * JVM tests for the pure recording-session index behind
 * GET /api/recordings/sessions: local-day bucketing, end inference
 * (duration → gap → fallback) and trigger attribution.
 */
class RecordingSessionIndexTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 2026-09-11 local (UTC here) midnight and next midnight. */
    private val dayStart = dayWindow("2026-09-11").startMs
    private val dayEnd = dayWindow("2026-09-11").endExclusiveMs

    private fun dayWindow(day: String, zone: TimeZone = utc) =
        RecordingSessionIndex.dayWindow(day, nowMs = 1_789_000_000_000, zone = zone)

    private fun video(id: String, timestamp: Long, durationMs: Long = 0) = CaptureHistory(
        id = id,
        type = CaptureType.VIDEO,
        fileName = "VID_$id.mp4",
        filePath = "/Movies/VID_$id.mp4",
        timestamp = timestamp,
        durationMs = durationMs,
    )

    private fun photo(timestamp: Long) = CaptureHistory(
        id = "photo",
        type = CaptureType.PHOTO,
        fileName = "IMG.jpg",
        filePath = "/Pictures/IMG.jpg",
        timestamp = timestamp,
    )

    private fun event(type: String, timestampMs: Long) = DetectionEvent(
        id = "event-$type-$timestampMs",
        type = type,
        source = "test",
        timestampMs = timestampMs,
    )

    // ── Day window parsing ──

    @Test
    fun `valid day parses to local midnight through next midnight`() {
        val window = dayWindow("2026-09-11")
        assertEquals(dayStart, window.startMs)
        assertEquals(24 * 60 * 60 * 1000L, window.endExclusiveMs - window.startMs)
    }

    @Test
    fun `day window respects a non-UTC zone`() {
        // GMT+2: local 2026-09-11 00:00 is 2026-09-10 22:00 UTC.
        val plus2 = TimeZone.getTimeZone("GMT+02:00")
        val window = RecordingSessionIndex.dayWindow("2026-09-11", 0, plus2)
        val utcMidnight = dayWindow("2026-09-11").startMs
        assertEquals(utcMidnight - 2 * 60 * 60 * 1000L, window.startMs)
    }

    @Test
    fun `missing day answers today`() {
        val now = 1_789_123_456_789L
        val window = RecordingSessionIndex.dayWindow(null, now, utc)
        assertEquals(dayKeyOf(now), dayKeyOf(window.startMs))
        assertTrue(window.startMs <= now && now < window.endExclusiveMs)
    }

    @Test
    fun `malformed day answers today`() {
        val now = 1_789_123_456_789L
        for (bad in listOf("garbage", "2026-9-11", "20260911", "11-09-2026", "")) {
            val window = RecordingSessionIndex.dayWindow(bad, now, utc)
            assertEquals(dayKeyOf(now), dayKeyOf(window.startMs))
        }
    }

    @Test
    fun `impossible calendar date answers today`() {
        val now = 1_789_123_456_789L
        val window = RecordingSessionIndex.dayWindow("2026-02-30", now, utc)
        assertEquals(dayKeyOf(now), dayKeyOf(window.startMs))
    }

    private fun dayKeyOf(timestampMs: Long, zone: TimeZone = utc): String =
        RecordingSessionIndex.dayKeyFor(timestampMs, zone)

    // ── Session bucketing ──

    @Test
    fun `only video captures of the requested day become sessions`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(
                photo(dayStart + 1_000),
                video("a", dayStart + 2_000),
                video("b", dayEnd + 2_000), // tomorrow
                video("c", dayStart - 2_000), // yesterday
            ),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals(listOf("a"), sessions.map { it.id })
    }

    @Test
    fun `sessions are ordered by start time regardless of input order`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(
                video("late", dayStart + 5_000),
                video("early", dayStart + 1_000),
            ),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals(listOf("early", "late"), sessions.map { it.id })
    }

    // ── End inference ──

    @Test
    fun `known duration wins over the gap`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(
                video("a", dayStart, durationMs = 15_200),
                video("b", dayStart + 1_000),
            ),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals(dayStart + 15_200, sessions.first { it.id == "a" }.endMs)
    }

    @Test
    fun `unknown duration infers the end from the next capture`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(
                video("a", dayStart),
                video("b", dayStart + 910_000),
            ),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals(dayStart + 910_000, sessions.first { it.id == "a" }.endMs)
    }

    @Test
    fun `gap inference reaches into the next day`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(
                video("a", dayEnd - 100_000),
                video("b", dayEnd + 200_000),
            ),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals(dayEnd + 200_000, sessions.first { it.id == "a" }.endMs)
    }

    @Test
    fun `gap beyond the cap falls back to the fixed bar`() {
        val beyondCap = dayStart + RecordingSessionIndex.MAX_GAP_INFERENCE_MS + 1
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(
                video("a", dayStart),
                video("b", beyondCap),
            ),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals(dayStart + RecordingSessionIndex.FALLBACK_SESSION_MS, sessions.first { it.id == "a" }.endMs)
    }

    @Test
    fun `no next capture falls back to the fixed bar`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(video("last", dayStart + 1_000)),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        val session = sessions.single()
        assertEquals(dayStart + 1_000 + RecordingSessionIndex.FALLBACK_SESSION_MS, session.endMs)
    }

    // ── Trigger attribution ──

    @Test
    fun `no overlapping event reads as manual`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(event("motion", 1_200_000)),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_MANUAL, trigger)
    }

    @Test
    fun `overlapping motion event attributes motion`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(event("motion", 1_050_000)),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_MOTION, trigger)
    }

    @Test
    fun `overlapping sound event attributes sound`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(event("sound", 1_050_000)),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_SOUND, trigger)
    }

    @Test
    fun `event within the lead tolerance attributes`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(event("motion", 1_000_000 - RecordingSessionIndex.TRIGGER_TOLERANCE_MS)),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_MOTION, trigger)
    }

    @Test
    fun `event beyond the lead tolerance does not attribute`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(event("motion", 1_000_000 - RecordingSessionIndex.TRIGGER_TOLERANCE_MS - 1)),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_MANUAL, trigger)
    }

    @Test
    fun `nearest event to the start decides`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(
                event("sound", 1_000_000 + 500),
                event("motion", 1_000_000 + 2_000),
            ),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_SOUND, trigger)
    }

    @Test
    fun `tie between motion and sound reads as motion`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(
                event("sound", 1_000_000 + 1_000),
                event("motion", 1_000_000 + 1_000),
            ),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_MOTION, trigger)
    }

    @Test
    fun `tamper events never attribute`() {
        val trigger = RecordingSessionIndex.attributeTrigger(
            startMs = 1_000_000,
            endMs = 1_100_000,
            triggerEvents = listOf(event("tamper", 1_000_000)),
        )
        assertEquals(RecordingSessionIndex.TRIGGER_MANUAL, trigger)
    }

    @Test
    fun `continuous-style chained captures stay manual without events`() {
        // A loop's chained segments arrive as back-to-back captures; without a
        // persisted recording-state history they read as manual sessions.
        val captures = (0 until 3).map { video("seg$it", dayStart + it * 910_000, durationMs = 900_000) }
        val sessions = RecordingSessionIndex.buildSessions(captures, emptyList(), dayWindow("2026-09-11"))
        assertEquals(sessions.map { it.trigger }, List(3) { RecordingSessionIndex.TRIGGER_MANUAL })
        assertTrue(sessions.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    // ── mediaId passthrough ──

    @Test
    fun `mediaId carries the capture-history id`() {
        val sessions = RecordingSessionIndex.buildSessions(
            captures = listOf(video("a", dayStart)),
            events = emptyList(),
            window = dayWindow("2026-09-11"),
        )
        assertEquals("a", sessions.single().mediaId)
    }
}

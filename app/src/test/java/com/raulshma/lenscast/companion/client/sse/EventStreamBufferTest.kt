package com.raulshma.lenscast.companion.client.sse

import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed reducer's dedupe/cap/order table — the vitest cases of
 * eventStreamCore.test.ts's mergeEvents suite, pinned against
 * [EventStreamBuffer] including its quirks (existing-wins on a key
 * collision, timestamp fallback for blank ids, newest-first order).
 */
class EventStreamBufferTest {

    private fun event(id: String, timestampMs: Long): RemoteDetectionEvent =
        RemoteDetectionEvent(id = id, type = "motion", source = "test", timestampMs = timestampMs)

    private fun ids(events: List<RemoteDetectionEvent>): List<String> = events.map { it.id }

    // ── TS mergeEvents dedupe/cap ──

    @Test
    fun `keeps the newest first and caps the buffer`() {
        val buffer = EventStreamBuffer(capacity = 2)
        buffer.onPollRefresh(listOf(event("a", 100), event("b", 300), event("c", 200)))
        assertEquals(listOf("b", "c"), ids(buffer.snapshot()))
    }

    @Test
    fun `dedupes by id keeping the existing occurrence`() {
        val buffer = EventStreamBuffer()
        buffer.onSse(event("a", 100))
        buffer.onPollRefresh(listOf(event("a", 100), event("b", 50)))
        assertEquals(listOf("a", "b"), ids(buffer.snapshot()))
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `falls back to the timestamp as identity when the id is blank`() {
        // TS eventKey: `t:<timestampMs>` — two id-less same-ms events collapse.
        assertEquals("t:100", EventStreamBuffer.eventKey(event("", 100)))
        val buffer = EventStreamBuffer()
        buffer.onPollRefresh(listOf(event("", 100), event("", 100), event("", 90)))
        assertEquals(2, buffer.snapshot().size)
        assertEquals(100, buffer.snapshot()[0].timestampMs)
        assertEquals(90, buffer.snapshot()[1].timestampMs)
    }

    @Test
    fun `replays keep their position when older than the live tail`() {
        val buffer = EventStreamBuffer()
        buffer.onSse(event("live", 500))
        buffer.onPollRefresh(listOf(event("replayOld", 100), event("replayNew", 600)))
        assertEquals(listOf("replayNew", "live", "replayOld"), ids(buffer.snapshot()))
    }

    @Test
    fun `sse arrival and poll refresh merge without duplicates`() {
        val buffer = EventStreamBuffer()
        buffer.onSse(event("e1", 1000))
        buffer.onPollRefresh(listOf(event("e1", 1000), event("e2", 2000), event("e3", 500)))
        buffer.onSse(event("e4", 3000))
        assertEquals(listOf("e4", "e2", "e1", "e3"), ids(buffer.snapshot()))
    }

    @Test
    fun `a poll refresh does not resurrect a cleared feed`() {
        val buffer = EventStreamBuffer()
        buffer.onSse(event("e1", 1000))
        buffer.clear()
        assertEquals(emptyList<String>(), ids(buffer.snapshot()))
    }

    @Test
    fun `default capacity is the web's MAX_EVENTS of 200`() {
        assertEquals(200, EventStreamBuffer.DEFAULT_CAPACITY)
        val buffer = EventStreamBuffer()
        val page = (1..250L).map { event("e$it", it * 10) }
        buffer.onPollRefresh(page)
        val snapshot = buffer.snapshot()
        assertEquals(200, snapshot.size)
        // Newest (highest timestamp) first; the oldest 50 (e1..e50, ts
        // 10..500) fell off, so the oldest kept is e51 at ts 510.
        assertEquals(2500, snapshot.first().timestampMs)
        assertEquals(510, snapshot.last().timestampMs)
        assertTrue(snapshot.all { it.timestampMs >= 510 })
    }

    @Test
    fun `same-millisecond events keep a stable key-ordered tiebreak`() {
        // TS sort: equal timestamps compare via localeCompare of the keys
        // (natural String order here): id:a < id:b.
        val buffer = EventStreamBuffer()
        buffer.onPollRefresh(listOf(event("b", 100), event("a", 100)))
        assertEquals(listOf("a", "b"), ids(buffer.snapshot()))
    }

    @Test
    fun `snapshot is a defensive copy, not the live list`() {
        val buffer = EventStreamBuffer()
        buffer.onSse(event("e1", 100))
        val taken = buffer.snapshot()
        buffer.onSse(event("e2", 200))
        assertEquals(1, taken.size)
        assertEquals(2, buffer.snapshot().size)
    }
}

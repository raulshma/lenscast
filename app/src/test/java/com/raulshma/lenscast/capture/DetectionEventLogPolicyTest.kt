package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detection event log's pure decisions: drop-oldest cap eviction,
 * newest-first read order, the API limit clamp, and the snapshot size gate.
 */
class DetectionEventLogPolicyTest {

    private fun event(timestampMs: Long) = DetectionEvent(
        id = "id-$timestampMs",
        type = "motion",
        source = "lenscast",
        timestampMs = timestampMs,
    )

    // ── Cap eviction ──

    @Test
    fun `append puts the new event first`() {
        val log = DetectionEventLogPolicy.append(listOf(event(2)), event(3))
        assertEquals(listOf("id-3", "id-2"), log.map { it.id })
    }

    @Test
    fun `append evicts the oldest past the cap`() {
        val full = (DetectionEventLogPolicy.MAX_EVENTS downTo 1).map { event(it.toLong()) }
        val appended = DetectionEventLogPolicy.append(full, event(999))
        assertEquals(DetectionEventLogPolicy.MAX_EVENTS, appended.size)
        assertEquals("id-999", appended.first().id)
        assertEquals("id-2", appended.last().id)
        assertFalse(appended.any { it.id == "id-1" })
    }

    @Test
    fun `append on an empty log keeps one entry`() {
        val log = DetectionEventLogPolicy.append(emptyList(), event(1))
        assertEquals(listOf("id-1"), log.map { it.id })
    }

    // ── Read limit ──

    @Test
    fun `readNewestFirst truncates to the requested limit`() {
        val log = listOf(event(3), event(2), event(1))
        assertEquals(listOf("id-3", "id-2"), DetectionEventLogPolicy.readNewestFirst(log, 2).map { it.id })
    }

    @Test
    fun `listLimit clamps into the cap and folds null to the default`() {
        assertEquals(
            DetectionEventLogPolicy.DEFAULT_LIST_LIMIT,
            DetectionEventLogPolicy.listLimit(null),
        )
        assertEquals(1, DetectionEventLogPolicy.listLimit(0))
        assertEquals(1, DetectionEventLogPolicy.listLimit(-5))
        assertEquals(
            DetectionEventLogPolicy.MAX_EVENTS,
            DetectionEventLogPolicy.listLimit(100_000),
        )
    }

    // ── Snapshot size gate ──

    @Test
    fun `null and empty snapshots are skipped`() {
        assertFalse(DetectionEventLogPolicy.acceptsSnapshot(null))
        assertFalse(DetectionEventLogPolicy.acceptsSnapshot(ByteArray(0)))
    }

    @Test
    fun `snapshots within the cap are accepted`() {
        // 30_000 raw bytes encode to exactly 40_000 base64 bytes.
        val bytes = ByteArray(30_000)
        assertTrue(DetectionEventLogPolicy.acceptsSnapshot(bytes))
    }

    @Test
    fun `snapshots over the cap are skipped, never truncated`() {
        // 30_001 raw bytes encode to 40_004 base64 bytes — over the cap.
        val bytes = ByteArray(30_001)
        assertFalse(DetectionEventLogPolicy.acceptsSnapshot(bytes))
    }
}

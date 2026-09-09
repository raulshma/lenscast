package com.raulshma.lenscast.data

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageManagerTest {
    private fun entry(id: String, ts: Long, size: Long) = CaptureHistory(
        id = id,
        type = CaptureType.PHOTO,
        fileName = "$id.jpg",
        filePath = "/tmp/$id.jpg",
        timestamp = ts,
        fileSizeBytes = size,
    )

    @Test
    fun `eviction is oldest-first until under quota`() {
        val history = listOf(
            entry("old", 1000L, 600L),
            entry("mid", 2000L, 600L),
            entry("new", 3000L, 600L),
        )
        val victims = StorageManager.evictionOrder(history, usedBytes = 1800L, quotaBytes = 1000L)
        assertEquals(listOf("old", "mid"), victims.map { it.id })
    }

    @Test
    fun `no eviction when under quota`() {
        val history = listOf(entry("a", 1L, 100L))
        assertTrue(StorageManager.evictionOrder(history, 100L, 1000L).isEmpty())
    }

    @Test
    fun `storage bar percent clamps`() {
        val bar = StorageManager.storageBar(250L, 1000L)
        assertEquals(25, bar.percent)
    }

    // ── time-based retention ──

    @Test
    fun `retention victims are the entries older than the window - oldest first`() {
        val now = 10_000_000L
        val history = listOf(
            entry("fresh", now - 1000L, 10L),
            entry("aged", now - 8 * RetentionPolicy.MS_PER_DAY, 10L),
            entry("ancient", now - 30 * RetentionPolicy.MS_PER_DAY, 10L),
        )
        val victims = StorageManager.retentionVictims(history, now, retentionDays = 7)
        // Oldest first, and the fresh entry survives.
        assertEquals(listOf("ancient", "aged"), victims.map { it.id })
    }

    @Test
    fun `retention off is a no-op`() {
        val now = 10_000_000L
        val history = listOf(entry("ancient", 1L, 10L))
        assertTrue(StorageManager.retentionVictims(history, now, retentionDays = 0).isEmpty())
    }
}

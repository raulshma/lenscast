package com.raulshma.lenscast.data

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureHistoryStoreMergeTest {

    private fun entry(
        id: String,
        filePath: String,
        timestamp: Long,
        fileSizeBytes: Long = 0,
        durationMs: Long = 0,
        fileName: String = "file",
    ) = CaptureHistory(
        id = id,
        type = CaptureType.VIDEO,
        fileName = fileName,
        filePath = filePath,
        timestamp = timestamp,
        fileSizeBytes = fileSizeBytes,
        durationMs = durationMs,
    )

    @Test
    fun `new entry is prepended and list stays newest-first`() {
        val existing = listOf(
            entry("a", "/m/a.mp4", timestamp = 200),
            entry("b", "/m/b.mp4", timestamp = 100),
        )
        val merged = CaptureHistoryStore.mergeEntry(existing, entry("c", "/m/c.mp4", timestamp = 300))
        assertEquals(listOf("c", "a", "b"), merged.map { it.id })
    }

    @Test
    fun `older entry still lands on top sorted by timestamp`() {
        val existing = listOf(entry("a", "/m/a.mp4", timestamp = 500))
        val merged = CaptureHistoryStore.mergeEntry(existing, entry("c", "/m/c.mp4", timestamp = 50))
        assertEquals(listOf("a", "c"), merged.map { it.id })
    }

    @Test
    fun `same path merges instead of duplicating`() {
        val existing = listOf(entry("a", "/m/a.mp4", timestamp = 100, fileSizeBytes = 10))
        val merged = CaptureHistoryStore.mergeEntry(
            existing,
            entry("b", "/m/a.mp4", timestamp = 200, fileSizeBytes = 42),
        )
        assertEquals(1, merged.size)
        assertEquals("a", merged[0].id)
        assertEquals(200L, merged[0].timestamp)
        assertEquals(42L, merged[0].fileSizeBytes)
    }

    @Test
    fun `merge keeps existing size when the new entry has none`() {
        val existing = listOf(entry("a", "/m/a.mp4", timestamp = 100, fileSizeBytes = 99, durationMs = 5))
        val merged = CaptureHistoryStore.mergeEntry(existing, entry("b", "/m/a.mp4", timestamp = 150))
        assertEquals(99L, merged[0].fileSizeBytes)
        assertEquals(5L, merged[0].durationMs)
    }

    @Test
    fun `path comparison trims whitespace`() {
        val existing = listOf(entry("a", "/m/a.mp4 ", timestamp = 100))
        val merged = CaptureHistoryStore.mergeEntry(existing, entry("b", "/m/a.mp4", timestamp = 200))
        assertEquals(1, merged.size)
    }
}

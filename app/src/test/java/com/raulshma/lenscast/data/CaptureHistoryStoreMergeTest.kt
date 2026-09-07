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

    // ── MediaStore reconciliation (mergeWithDeviceMedia) ──

    @Test
    fun `existing entry is upgraded from device media`() {
        val existing = listOf(
            entry("a", "content://media/video/1", timestamp = 100, fileSizeBytes = 0, durationMs = 0, fileName = "local.mp4"),
        )
        val device = listOf(
            entry("", "content://media/video/1", timestamp = 300, fileSizeBytes = 4096, durationMs = 9000, fileName = "device.mp4"),
        )
        val merged = CaptureHistoryStore.mergeWithDeviceMedia(existing, device)
        assertEquals(1, merged.size)
        assertEquals("a", merged[0].id)
        assertEquals(300L, merged[0].timestamp)
        assertEquals(4096L, merged[0].fileSizeBytes)
        assertEquals(9000L, merged[0].durationMs)
        assertEquals("device.mp4", merged[0].fileName)
    }

    @Test
    fun `device match with blank name or zero fields keeps the existing values`() {
        val existing = listOf(
            entry("a", "/m/a.mp4", timestamp = 500, fileSizeBytes = 99, durationMs = 7, fileName = "mine.mp4"),
        )
        val device = listOf(
            entry("", "/m/a.mp4", timestamp = 100, fileSizeBytes = 0, durationMs = 0, fileName = ""),
        )
        val merged = CaptureHistoryStore.mergeWithDeviceMedia(existing, device)
        assertEquals("mine.mp4", merged[0].fileName)
        assertEquals(500L, merged[0].timestamp)
        assertEquals(99L, merged[0].fileSizeBytes)
        assertEquals(7L, merged[0].durationMs)
    }

    @Test
    fun `brand-new device media is adopted with a generated id`() {
        val existing = emptyList<CaptureHistory>()
        val device = listOf(
            entry("", "content://media/video/9", timestamp = 300, fileName = "new.mp4"),
        )
        val merged = CaptureHistoryStore.mergeWithDeviceMedia(existing, device)
        assertEquals(1, merged.size)
        assertEquals(true, merged[0].id.isNotBlank())
        assertEquals("new.mp4", merged[0].fileName)
    }

    @Test
    fun `device media already covered by an existing path is not duplicated`() {
        val existing = listOf(entry("a", "/m/a.mp4", timestamp = 100))
        val device = listOf(
            entry("", "/m/a.mp4", timestamp = 100, fileName = "a.mp4"),
            entry("", "/m/b.mp4", timestamp = 200, fileName = "b.mp4"),
        )
        val merged = CaptureHistoryStore.mergeWithDeviceMedia(existing, device)
        assertEquals(2, merged.size)
        assertEquals("/m/b.mp4", merged[0].filePath)
        assertEquals("a", merged[1].id)
    }

    @Test
    fun `result stays newest-first after reconciliation`() {
        val existing = listOf(
            entry("old", "/m/old.mp4", timestamp = 50),
        )
        val device = listOf(
            entry("", "/m/new.mp4", timestamp = 900, fileName = "new.mp4"),
            entry("", "/m/mid.mp4", timestamp = 400, fileName = "mid.mp4"),
        )
        val merged = CaptureHistoryStore.mergeWithDeviceMedia(existing, device)
        assertEquals(listOf("/m/new.mp4", "/m/mid.mp4", "/m/old.mp4"), merged.map { it.filePath })
    }

    @Test
    fun `empty device media leaves the list sorted unchanged`() {
        val existing = listOf(
            entry("a", "/m/a.mp4", timestamp = 100),
            entry("b", "/m/b.mp4", timestamp = 300),
        )
        val merged = CaptureHistoryStore.mergeWithDeviceMedia(existing, emptyList())
        assertEquals(listOf("b", "a"), merged.map { it.id })
    }
}


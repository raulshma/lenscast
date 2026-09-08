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
}

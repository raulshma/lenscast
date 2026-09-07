package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaResponderTest {

    @Test
    fun `missing header means full content`() {
        assertNull(MediaResponder.resolveRange(null, totalSize = 1000))
    }

    @Test
    fun `foreign unit means full content`() {
        assertNull(MediaResponder.resolveRange("items=0-99", totalSize = 1000))
    }

    @Test
    fun `dashless spec means full content`() {
        assertNull(MediaResponder.resolveRange("bytes=500", totalSize = 1000))
    }

    @Test
    fun `closed range is honored`() {
        val range = MediaResponder.resolveRange("bytes=0-99", totalSize = 1000)!!
        assertEquals(0L, range.start)
        assertEquals(99L, range.end)
    }

    @Test
    fun `open end is capped to a two megabyte chunk`() {
        val range = MediaResponder.resolveRange("bytes=0-", totalSize = 10 * 1024 * 1024)!!
        assertEquals(0L, range.start)
        assertEquals(2 * 1024 * 1024 - 1L, range.end)
    }

    @Test
    fun `open end near the tail stops at the last byte`() {
        val range = MediaResponder.resolveRange("bytes=900-", totalSize = 1000)!!
        assertEquals(900L, range.start)
        assertEquals(999L, range.end)
    }

    @Test
    fun `end past the tail is clamped`() {
        val range = MediaResponder.resolveRange("bytes=0-9999", totalSize = 1000)!!
        assertEquals(999L, range.end)
    }

    @Test
    fun `garbage numbers fall back to zero start`() {
        val range = MediaResponder.resolveRange("bytes=abc-def", totalSize = 1000)!!
        assertEquals(0L, range.start)
        assertEquals(999L, range.end)
    }

    @Test
    fun `snapshot flags parse both spellings`() {
        assertEquals(
            MediaResponder.SnapshotOptions(highRes = true, saveToDisk = false),
            MediaResponder.parseSnapshotQuery("highres=1"),
        )
        assertEquals(
            MediaResponder.SnapshotOptions(highRes = true, saveToDisk = true),
            MediaResponder.parseSnapshotQuery("high_res=1&save_to_disk=1"),
        )
        assertEquals(
            MediaResponder.SnapshotOptions(highRes = false, saveToDisk = true),
            MediaResponder.parseSnapshotQuery("save=1"),
        )
    }

    @Test
    fun `empty snapshot query means low-res transient`() {
        assertEquals(
            MediaResponder.SnapshotOptions(highRes = false, saveToDisk = false),
            MediaResponder.parseSnapshotQuery(null),
        )
        assertEquals(
            MediaResponder.SnapshotOptions(highRes = false, saveToDisk = false),
            MediaResponder.parseSnapshotQuery(""),
        )
    }
}

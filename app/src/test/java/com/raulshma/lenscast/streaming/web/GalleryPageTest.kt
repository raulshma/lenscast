package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPageTest {

    private fun item(id: String, type: CaptureType = CaptureType.VIDEO) = CaptureHistory(
        id = id,
        type = type,
        fileName = "$id.mp4",
        filePath = "/m/$id.mp4",
        timestamp = id.toLongOrNull()?.toLong() ?: 0L,
    )

    private fun items(count: Int) = (1..count).map { item(it.toString()) }

    @Test
    fun `first page returns the head of the list`() {
        val page = GalleryPage.of(items(120), type = null, page = 0, pageSize = 50)
        assertEquals(50, page.items.size)
        assertEquals("1", page.items.first().id)
        assertEquals(120, page.total)
        assertEquals(50, page.pageSize)
        assertTrue(page.hasMore)
    }

    @Test
    fun `middle page drops past earlier pages`() {
        val page = GalleryPage.of(items(120), type = null, page = 1, pageSize = 50)
        assertEquals("51", page.items.first().id)
        assertEquals("100", page.items.last().id)
        assertTrue(page.hasMore)
    }

    @Test
    fun `exact-fit last page has no more`() {
        val page = GalleryPage.of(items(100), type = null, page = 1, pageSize = 50)
        assertEquals(50, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `partial last page reports hasMore false`() {
        val page = GalleryPage.of(items(120), type = null, page = 2, pageSize = 50)
        assertEquals(20, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `page beyond the end is empty with hasMore false`() {
        val page = GalleryPage.of(items(60), type = null, page = 5, pageSize = 50)
        assertEquals(0, page.items.size)
        assertFalse(page.hasMore)
        assertEquals(60, page.total)
    }

    @Test
    fun `filter applies before paging`() {
        val mixed = (1..60).map { item(it.toString(), if (it % 2 == 0) CaptureType.PHOTO else CaptureType.VIDEO) }
        val page = GalleryPage.of(mixed, type = "PHOTO", page = 0, pageSize = 20)
        assertEquals(20, page.items.size)
        assertEquals(30, page.total)
        assertTrue(page.items.all { it.type == CaptureType.PHOTO })
        assertTrue(page.hasMore)
    }

    @Test
    fun `total reflects filtered count not page size`() {
        val mixed = (1..10).map { item(it.toString(), CaptureType.PHOTO) } + items(40)
        val page = GalleryPage.of(mixed, type = "photo", page = 0, pageSize = 100)
        assertEquals(10, page.total)
        assertEquals(10, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `unknown or missing type means everything`() {
        val all = items(10)
        assertEquals(10, GalleryPage.of(all, null, 0, 100).total)
        assertEquals(10, GalleryPage.of(all, "ALL", 0, 100).total)
    }

    @Test
    fun `non-positive page size falls back to the default`() {
        val page = GalleryPage.of(items(60), null, 0, 0)
        assertEquals(GalleryPage.DEFAULT_PAGE_SIZE, page.pageSize)
        assertEquals(GalleryPage.DEFAULT_PAGE_SIZE, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `negative page serves the unfiltered whole list`() {
        val page = GalleryPage.of(items(10), null, page = -1, pageSize = 5)
        assertEquals(10, page.items.size)
    }
}

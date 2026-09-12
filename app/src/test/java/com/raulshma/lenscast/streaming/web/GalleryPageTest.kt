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

    // ── q (file-name search) filter ──

    @Test
    fun `blank or missing query matches everything`() {
        assertEquals(10, GalleryPage.of(items(10), null, 0, 100, query = null).total)
        assertEquals(10, GalleryPage.of(items(10), null, 0, 100, query = "  ").total)
    }

    @Test
    fun `query filters case-insensitively on the file name`() {
        val all = listOf(item("VID_20260912_101530"), item("IMG_20260912_101531"))
        val page = GalleryPage.of(all, null, 0, 100, query = " vid_2026")
        assertEquals(1, page.total)
        assertEquals("VID_20260912_101530", page.items.single().id)
    }

    @Test
    fun `query applies before pagination and total reflects the filtered count`() {
        val all = (1..30).map { item("VID_$it") } + (1..30).map { item("IMG_$it") }
        val first = GalleryPage.of(all, null, 0, 50, query = "img_")
        assertEquals(30, first.total)
        assertEquals(30, first.items.size)
        assertFalse(first.hasMore)
        // A query that narrows past the requested page's window pages honestly.
        val second = GalleryPage.of(all, null, 1, 20, query = "img_")
        assertEquals(10, second.items.size)
        assertFalse(second.hasMore)
    }

    @Test
    fun `query combines with the type filter`() {
        val all = listOf(
            item("VID_a", CaptureType.VIDEO),
            item("IMG_a", CaptureType.PHOTO),
        )
        val page = GalleryPage.of(all, type = "PHOTO", page = 0, pageSize = 50, query = "_a")
        assertEquals(1, page.total)
        assertEquals(CaptureType.PHOTO, page.items.single().type)
    }
}

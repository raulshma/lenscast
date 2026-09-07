package com.raulshma.lenscast.gallery

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryPagerMathTest {

    private fun item(id: String) = CaptureHistory(
        id = id,
        type = CaptureType.PHOTO,
        fileName = "$id.jpg",
        filePath = "/tmp/$id.jpg",
        timestamp = 0L,
    )

    @Test
    fun `deleting a middle page lands on the next page`() {
        // 5 pages, deleting index 2: the old index 3 slides into slot 2.
        assertEquals(2, indexAfterDelete(currentIndex = 2, sizeAfterDelete = 4))
    }

    @Test
    fun `deleting the first page lands on the new first page`() {
        assertEquals(0, indexAfterDelete(currentIndex = 0, sizeAfterDelete = 3))
    }

    @Test
    fun `deleting the last page falls back to the previous page`() {
        assertEquals(3, indexAfterDelete(currentIndex = 4, sizeAfterDelete = 4))
    }

    @Test
    fun `deleting the only page pops back`() {
        assertNull(indexAfterDelete(currentIndex = 0, sizeAfterDelete = 0))
    }

    @Test
    fun `an already empty gallery pops back`() {
        assertNull(indexAfterDelete(currentIndex = 0, sizeAfterDelete = -1))
    }

    // ── initialIndexFor ──

    @Test
    fun `the viewer opens on the item's own index`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(1, initialIndexFor(items, "b"))
        assertEquals(2, initialIndexFor(items, "c"))
    }

    @Test
    fun `an unknown media id falls back to the first page`() {
        val items = listOf(item("a"), item("b"))
        assertEquals(0, initialIndexFor(items, "missing"))
        assertEquals(0, initialIndexFor(emptyList(), "missing"))
    }

    // ── clampedPage ──

    @Test
    fun `in-range pages need no resync`() {
        assertNull(clampedPage(current = 0, size = 3))
        assertNull(clampedPage(current = 2, size = 3))
    }

    @Test
    fun `a page past the shrunken list resyncs to the last page`() {
        assertEquals(2, clampedPage(current = 3, size = 3))
        assertEquals(0, clampedPage(current = 5, size = 1))
    }

    @Test
    fun `an empty list needs no resync - the viewer pops back instead`() {
        assertNull(clampedPage(current = 0, size = 0))
    }
}

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

    // ── viewerResyncTarget ──

    @Test
    fun `initial open resyncs to the tapped item's index`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(1, viewerResyncTarget(items, "b", currentPage = 0))
        assertEquals(2, viewerResyncTarget(items, "c", currentPage = 0))
    }

    @Test
    fun `an unknown id on first placement stays on the first page`() {
        // Stale-route fallback: the pager already sits on page 0, so no resync.
        val items = listOf(item("a"), item("b"))
        assertNull(viewerResyncTarget(items, "missing", currentPage = 0))
        assertNull(viewerResyncTarget(emptyList(), "missing", currentPage = 0))
    }

    @Test
    fun `a known id resyncs when the list shifted it`() {
        // "d" sat at index 3; an earlier item left the list and slid it to 2.
        val items = listOf(item("b"), item("c"), item("d"))
        assertEquals(2, viewerResyncTarget(items, "d", currentPage = 3))
    }

    @Test
    fun `a page past the shrunken list resyncs to the last page`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(2, viewerResyncTarget(items, "missing", currentPage = 5))
        assertEquals(0, viewerResyncTarget(listOf(item("a")), "missing", currentPage = 3))
    }

    @Test
    fun `deleting the current item already sits on the next-page neighbor`() {
        // Deleting index 2 of 5: the old index 3 slides into page 2 — no scroll.
        val after = listOf(item("a"), item("b"), item("d"), item("e"))
        assertNull(viewerResyncTarget(after, "c", currentPage = 2))
    }

    @Test
    fun `deleting the first item stays on the new first page`() {
        // Deleting index 0 of 3: the old index 1 slides into page 0.
        val after = listOf(item("b"), item("c"))
        assertNull(viewerResyncTarget(after, "a", currentPage = 0))
    }

    @Test
    fun `deleting the last item resyncs to the previous page`() {
        // Deleting index 4 of 5: page 4 no longer exists, fall back to 3.
        val after = listOf(item("a"), item("b"), item("c"), item("d"))
        assertEquals(3, viewerResyncTarget(after, "e", currentPage = 4))
    }

    @Test
    fun `deleting the only item needs no resync - the viewer pops back`() {
        assertNull(viewerResyncTarget(emptyList(), "a", currentPage = 0))
    }

    @Test
    fun `no resync when the pager already sits on its target`() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertNull(viewerResyncTarget(items, "b", currentPage = 1))
    }
}

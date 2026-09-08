package com.raulshma.lenscast.gallery

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerSyncTest {

    private fun item(id: String) = CaptureHistory(
        id = id,
        type = CaptureType.PHOTO,
        fileName = "$id.jpg",
        filePath = "/tmp/$id.jpg",
        timestamp = 0L,
    )

    // ── the placed one-shot: first placement jumps ──

    @Test
    fun `first load jumps instantly to the tapped item's index`() {
        val sync = ViewerSync()
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(ViewerSyncEffect.JumpTo(1), sync.reduce(items, "b", currentPage = 0))
    }

    @Test
    fun `an empty observation does not place the viewer`() {
        // The pager may be created before the list loads; the jump one-shot
        // must survive empty observations — the first non-empty resync still
        // jumps instantly instead of animating.
        val sync = ViewerSync()
        assertNull(sync.reduce(emptyList(), "b", currentPage = 0))
        assertEquals(
            ViewerSyncEffect.JumpTo(1),
            sync.reduce(listOf(item("a"), item("b")), "b", currentPage = 0),
        )
    }

    @Test
    fun `a stale route on first placement stays on the first page`() {
        // The pager already sits on the fallback page, so no resync fires.
        val sync = ViewerSync()
        val items = listOf(item("a"), item("b"))
        assertNull(sync.reduce(items, "missing", currentPage = 0))
    }

    @Test
    fun `an unknown id on first placement falls back instead of snapping to page 0`() {
        // Unknown id with the pager past the list: clamp to the last page.
        val sync = ViewerSync()
        assertEquals(ViewerSyncEffect.JumpTo(0), sync.reduce(listOf(item("a")), "missing", currentPage = 3))
    }

    // ── later resyncs animate ──

    @Test
    fun `a list change after placement animates to the shifted index`() {
        val sync = ViewerSync()
        sync.reduce(listOf(item("a"), item("b"), item("c"), item("d")), "d", currentPage = 3)
        // "d" sat at index 3; an earlier item left the list and slid it to 2.
        val shifted = listOf(item("b"), item("c"), item("d"))
        assertEquals(ViewerSyncEffect.AnimateTo(2), sync.reduce(shifted, "d", currentPage = 3))
    }

    @Test
    fun `no effect when the pager already sits on its target`() {
        val sync = ViewerSync()
        val items = listOf(item("a"), item("b"), item("c"))
        sync.reduce(items, "b", currentPage = 1)
        assertNull(sync.reduce(items, "b", currentPage = 1))
    }

    // ── the post-delete landing ──

    @Test
    fun `deleting a mid-list item already sits on the neighbor that slid in`() {
        // Deleting index 2 of 5: the old index 3 slides into page 2 — no scroll.
        val sync = ViewerSync()
        sync.reduce(listOf(item("a"), item("b"), item("c"), item("d"), item("e")), "c", currentPage = 2)
        val after = listOf(item("a"), item("b"), item("d"), item("e"))
        assertNull(sync.reduce(after, "c", currentPage = 2))
    }

    @Test
    fun `deleting the first item stays on the new first page`() {
        // Deleting index 0 of 3: the old index 1 slides into page 0.
        val sync = ViewerSync()
        sync.reduce(listOf(item("a"), item("b"), item("c")), "a", currentPage = 0)
        val after = listOf(item("b"), item("c"))
        assertNull(sync.reduce(after, "a", currentPage = 0))
    }

    @Test
    fun `deleting the last item lands on the previous page`() {
        // Deleting index 4 of 5: page 4 no longer exists, fall back to 3.
        val sync = ViewerSync()
        sync.reduce(listOf(item("a"), item("b"), item("c"), item("d"), item("e")), "e", currentPage = 4)
        val after = listOf(item("a"), item("b"), item("c"), item("d"))
        assertEquals(ViewerSyncEffect.AnimateTo(3), sync.reduce(after, "e", currentPage = 4))
    }

    @Test
    fun `deleting the only item resyncs nowhere - the pop verdict owns the viewer`() {
        val sync = ViewerSync()
        sync.reduce(listOf(item("a")), "a", currentPage = 0)
        assertNull(sync.reduce(emptyList(), "a", currentPage = 0))
    }

    // ── the delete-time pop verdict ──

    @Test
    fun `deleting the last remaining item pops back`() {
        val sync = ViewerSync()
        assertEquals(ViewerSyncEffect.Pop, sync.onDelete(currentIndex = 0, sizeBeforeDelete = 1))
    }

    @Test
    fun `deleting from a larger gallery leaves the landing to the resync`() {
        val sync = ViewerSync()
        assertNull(sync.onDelete(currentIndex = 2, sizeBeforeDelete = 5))
    }

    @Test
    fun `deleting from an already empty gallery pops back`() {
        val sync = ViewerSync()
        assertEquals(ViewerSyncEffect.Pop, sync.onDelete(currentIndex = 0, sizeBeforeDelete = 0))
    }

    // ── placed restoration ──

    @Test
    fun `a restored placed viewer animates instead of jumping`() {
        // The saveable placed flag: a placed machine rebuilt after process
        // death keeps animating.
        val sync = ViewerSync(initialPlaced = true)
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(ViewerSyncEffect.AnimateTo(1), sync.reduce(items, "b", currentPage = 0))
    }
}

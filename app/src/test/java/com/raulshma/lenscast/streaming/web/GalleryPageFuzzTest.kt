package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /api/gallery pagination under hostile query params: a deterministic corpus
 * fuzzer over [GalleryPage.of].
 *
 * ── The contract ──
 * For any (page, pageSize) Int pair the call returns a GalleryPage — never an
 * IllegalArgumentException from a negative drop (the Int product overflow),
 * never an exception of any kind. Page windows stay within the page size and
 * `total` always reports the filtered count.
 *
 * ── The corpus ──
 * negative/zero/boundary page and page-size values, the Int-overflow product
 * (46341²), Int.MAX_VALUE combinations, junk type filters, empty histories.
 */
class GalleryPageFuzzTest {

    private fun history(count: Int, type: CaptureType = CaptureType.PHOTO): List<CaptureHistory> =
        (0 until count).map { CaptureHistory("id-$it", type, "f$it.jpg", "/p/$it", it.toLong()) }

    // ── regression: page × pageSize overflowed Int negative and crashed on
    // drop() — `page=46341&pageSize=46341` on GET /api/gallery threw
    // IllegalArgumentException("Requested element count … is negative") ──

    @Test(timeout = 10_000)
    fun `regression - an overflowing page-times-size product is an empty page, not a crash`() {
        val page = GalleryPage.of(history(10), null, page = 46341, pageSize = 46341)
        assertTrue(page.items.isEmpty())
        // hasMore is false: the window starts past the end of the history.
        assertTrue(!page.hasMore)
        // Int.MAX_VALUE combinations behave the same way.
        val extreme = GalleryPage.of(history(10), null, page = Int.MAX_VALUE, pageSize = Int.MAX_VALUE)
        assertTrue(extreme.items.isEmpty())
        assertTrue(!extreme.hasMore)
    }

    @Test(timeout = 10_000)
    fun `corpus - every hostile page and pageSize combination answers a GalleryPage`() {
        val pages = listOf(-1, 0, 1, 2, 46341, Int.MAX_VALUE, Int.MIN_VALUE)
        val pageSizes = listOf(-1, 0, 1, 50, 46341, Int.MAX_VALUE, Int.MIN_VALUE)
        for (type in listOf(null, "", "PHOTO", "photo", "VIDEO", "junk", "\u0000")) {
            for (page in pages) {
                for (pageSize in pageSizes) {
                    for (count in listOf(0, 1, 101)) {
                        val items = history(count, if (type == "VIDEO") CaptureType.VIDEO else CaptureType.PHOTO)
                        val result = GalleryPage.of(items, type, page, pageSize)
                        assertEquals(count, result.total)
                        // Negative pages fall back to the full filtered list.
                        assertTrue(result.items.size <= result.pageSize || page < 0)
                        assertTrue(result.hasMore == (page.toLong() * result.pageSize + result.pageSize < count))
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun `the default and windowed shapes are unchanged`() {
        // Default page size applies when the param is not positive.
        assertEquals(50, GalleryPage.of(history(200), null, 0, 0).pageSize)
        // A page past the end is empty with hasMore false.
        val late = GalleryPage.of(history(10), null, 5, 50)
        assertTrue(late.items.isEmpty())
        assertTrue(!late.hasMore)
        // The exact window lands.
        val first = GalleryPage.of(history(101), null, 0, 50)
        assertEquals(50, first.items.size)
        assertTrue(first.hasMore)
        val second = GalleryPage.of(history(101), null, 1, 50)
        assertEquals(50, second.items.size)
        assertEquals("id-50", second.items.first().id)
        assertTrue(second.hasMore)
        // Negative pages serve the full list (documented fallback).
        assertEquals(10, GalleryPage.of(history(10), null, -3, 50).items.size)
    }
}

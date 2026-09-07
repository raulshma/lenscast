package com.raulshma.lenscast.gallery

import com.raulshma.lenscast.capture.model.CaptureHistory

/**
 * The media viewer pager's delete fallback: the page to land on after the
 * current page is removed. Prefers the page that slides into the deleted
 * slot (the next one), falls back to the previous page at the end of the
 * list; null means the gallery is empty and the viewer should pop back.
 * Pure index math so the navigation wiring stays declarative.
 */
fun indexAfterDelete(currentIndex: Int, sizeAfterDelete: Int): Int? {
    if (sizeAfterDelete <= 0) return null
    return if (currentIndex < sizeAfterDelete) currentIndex else sizeAfterDelete - 1
}

/**
 * The page a viewer opened via [mediaId] lands on: the item's index, or the
 * first page when the id is unknown (stale route argument). Pure index math
 * so the navigation wiring stays declarative.
 */
fun initialIndexFor(items: List<CaptureHistory>, mediaId: String): Int =
    items.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)

/**
 * The one pager-resync verdict for the viewer: the page [currentPage] should
 * move to after [items] changed, or null when no resync is needed. A
 * still-present [mediaId] pins the pager to its index — that is both the
 * initial placement when the list loads after the pager and the re-sync when
 * earlier items leave the list. An id the list no longer knows is the
 * post-delete shape: land on the [indexAfterDelete] neighbor of the current
 * page (the item that slid into the deleted slot, else the previous page)
 * instead of snapping to page 0. A stale route on first placement is the same
 * shape already sitting at page 0 and stays there, and an empty list needs no
 * resync — the viewer pops back instead. Pure index math so the navigation
 * wiring stays declarative.
 */
fun viewerResyncTarget(items: List<CaptureHistory>, mediaId: String, currentPage: Int): Int? {
    val index = items.indexOfFirst { it.id == mediaId }
    if (index >= 0) return if (currentPage == index) null else index
    return indexAfterDelete(currentPage, items.size)?.takeIf { it != currentPage }
}

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
 * The pager resync target after the item list shrinks: null while [current]
 * is still a valid page (no resync needed), otherwise the last remaining
 * page. Null for an empty list too — the viewer pops back instead.
 */
fun clampedPage(current: Int, size: Int): Int? =
    if (size <= 0 || current < size) null else size - 1

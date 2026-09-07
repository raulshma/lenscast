package com.raulshma.lenscast.gallery

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

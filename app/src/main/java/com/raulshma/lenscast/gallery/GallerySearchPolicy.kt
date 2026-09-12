package com.raulshma.lenscast.gallery

/**
 * The pure search-filter verdict for the gallery: a query matches an item
 * when the (case-insensitive) file name contains the trimmed query tokens.
 * Blank query matches everything. Kept pure so the app-side filter and the
 * tests share one ladder.
 */
object GallerySearchPolicy {

    /** True when [fileName] matches [query] (case-insensitive contains on the trimmed query). */
    fun matches(query: String, fileName: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return fileName.lowercase().contains(trimmed.lowercase())
    }

    /** The filtered list for [query], order preserved. */
    fun <T> filter(items: List<T>, query: String, nameOf: (T) -> String): List<T> =
        if (query.isBlank()) items else items.filter { matches(query, nameOf(it)) }
}

package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType

/**
 * Pure /api/gallery pagination: type filter applied first, then the page-size
 * resolution, drop/take window and hasMore arithmetic. The handler only
 * parses query params and serializes — the math is JVM-tested.
 */
data class GalleryPage(
    val items: List<CaptureHistory>,
    val total: Int,
    val pageSize: Int,
    val hasMore: Boolean,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50

        /**
         * Filters [items] by the wire [type] ("PHOTO"/"VIDEO", anything else
         * means all), resolves the effective page size ([pageSize] when
         * positive, [DEFAULT_PAGE_SIZE] otherwise) and pages. [total] always
         * reflects the filtered count, not the page size; a page past the end
         * is empty with hasMore=false. The window math runs in Long — hostile
         * query params (`page=46341&pageSize=46341`) used to overflow the Int
         * product negative and crash on drop().
         */
        fun of(items: List<CaptureHistory>, type: String?, page: Int, pageSize: Int): GalleryPage {
            val filtered = when (type?.uppercase()) {
                "PHOTO" -> items.filter { it.type == CaptureType.PHOTO }
                "VIDEO" -> items.filter { it.type == CaptureType.VIDEO }
                else -> items
            }

            val effectivePageSize = if (pageSize > 0) pageSize else DEFAULT_PAGE_SIZE
            val dropCount = page.toLong() * effectivePageSize
            val hasMore = dropCount + effectivePageSize < filtered.size

            val paged = if (page >= 0) {
                if (dropCount >= filtered.size) {
                    emptyList()
                } else {
                    // dropCount < filtered.size <= Int.Max here, so the narrow
                    // cast cannot lose a bit.
                    filtered.drop(dropCount.toInt()).take(effectivePageSize)
                }
            } else {
                filtered
            }

            return GalleryPage(
                items = paged,
                total = filtered.size,
                pageSize = effectivePageSize,
                hasMore = hasMore,
            )
        }
    }
}

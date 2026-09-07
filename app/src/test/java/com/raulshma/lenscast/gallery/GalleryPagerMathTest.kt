package com.raulshma.lenscast.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryPagerMathTest {

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
}

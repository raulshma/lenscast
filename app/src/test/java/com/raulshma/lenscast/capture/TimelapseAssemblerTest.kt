package com.raulshma.lenscast.capture

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseAssemblerTest {

    private fun photo(id: String, timestamp: Long) = CaptureHistory(
        id = id,
        type = CaptureType.PHOTO,
        fileName = "$id.jpg",
        filePath = "/photos/$id.jpg",
        timestamp = timestamp,
    )

    private fun video(id: String, timestamp: Long) = CaptureHistory(
        id = id,
        type = CaptureType.VIDEO,
        fileName = "$id.mp4",
        filePath = "/videos/$id.mp4",
        timestamp = timestamp,
    )

    @Test
    fun `selectSources keeps photos newest-first in oldest-first assembly order`() {
        val history = listOf(
            photo("c", 3000L),
            video("v", 4000L),
            photo("a", 1000L),
            photo("b", 2000L),
        )
        val selected = TimelapseAssembler.selectSources(history, lastN = 100)
        assertEquals(listOf("a", "b", "c"), selected.map { it.id })
    }

    @Test
    fun `selectSources clamps to the newest 500`() {
        val history = (0 until 600).map { photo("p$it", it.toLong()) }
        val selected = TimelapseAssembler.selectSources(history, lastN = 10_000)
        assertEquals(500, selected.size)
        assertTrue(selected.all { it.type == CaptureType.PHOTO })
        assertEquals("p100", selected.first().id)
        assertEquals("p599", selected.last().id)
    }
}

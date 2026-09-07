package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFileNamingTest {

    @Test
    fun `file name carries IMG prefix and jpg suffix`() {
        val name = PhotoCaptureManager.generateFileName()
        assertTrue(name.startsWith("IMG_"))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun `file name embeds a timestamp stamp`() {
        val name = PhotoCaptureManager.generateFileName()
        assertTrue(name.matches(Regex("IMG_\\d{8}_\\d{6}\\.jpg")))
        assertEquals(23, name.length)
    }
}

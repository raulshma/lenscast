package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

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

    @Test
    fun `photoName formats the shared timestamp pattern`() {
        val name = MediaFileNaming.photoName(Date(0))
        assertTrue(name.matches(Regex("IMG_\\d{8}_\\d{6}\\.jpg")))
        assertEquals(23, name.length)
    }

    @Test
    fun `videoName carries VID prefix and mp4 suffix`() {
        val name = MediaFileNaming.videoName(Date())
        assertTrue(name.startsWith("VID_"))
        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.matches(Regex("VID_\\d{8}_\\d{6}\\.mp4")))
        assertEquals(23, name.length)
    }

    @Test
    fun `photo and video share one timestamp stamp`() {
        val now = Date(1_700_000_000_000L)
        val stamp = "\\d{8}_\\d{6}"
        val photo = MediaFileNaming.photoName(now)
        val video = MediaFileNaming.videoName(now)
        assertEquals(photo.removePrefix("IMG_").removeSuffix(".jpg"), video.removePrefix("VID_").removeSuffix(".mp4"))
        assertTrue(photo.matches(Regex("IMG_$stamp\\.jpg")))
        assertTrue(video.matches(Regex("VID_$stamp\\.mp4")))
    }
}

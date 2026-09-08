package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavUploaderTest {

    private fun uploader(baseUrl: String = "http://192.168.1.10:5005/remote.php/dav/backup") =
        WebDavUploader(baseUrl, "user", "pass")

    @Test
    fun `directory path strips trailing slash`() {
        assertEquals("/remote.php/dav/backup", uploader().directoryPath())
    }

    @Test
    fun `directory path of root collection is empty-ish slash`() {
        assertEquals("", uploader("http://192.168.1.10:5005").directoryPath())
    }

    @Test
    fun `collection url percent-encodes spaces`() {
        val url = uploader().collectionUrlFor("clip 2026-09-09.mp4")
        assertEquals("http://192.168.1.10:5005/remote.php/dav/backup/clip%202026-09-09.mp4", url.toString())
    }

    @Test
    fun `content types map by extension`() {
        assertEquals("image/jpeg", uploader().contentTypesFor("IMG_0001.jpg"))
        assertEquals("image/jpeg", uploader().contentTypesFor("IMG_0001.JPG"))
        assertEquals("video/mp4", uploader().contentTypesFor("clip.mp4"))
        assertEquals("application/octet-stream", uploader().contentTypesFor("data.bin"))
    }
}

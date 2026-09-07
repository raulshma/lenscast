package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMediaFormatTest {

    @Test
    fun `mime per capture type`() {
        assertEquals("image/jpeg", CaptureMediaFormat.mimeFor(CaptureType.PHOTO))
        assertEquals("video/mp4", CaptureMediaFormat.mimeFor(CaptureType.VIDEO))
    }

    @Test
    fun `relative paths keep the trailing slash the MediaStore queries rely on`() {
        assertEquals("Pictures/LensCast/", CaptureMediaFormat.PHOTOS_RELATIVE_PATH)
        assertEquals("Movies/LensCast/", CaptureMediaFormat.VIDEOS_RELATIVE_PATH)
        assertEquals("LensCast", CaptureMediaFormat.PHOTO_DIR_NAME)
        assertEquals("LensCast", CaptureMediaFormat.VIDEO_DIR_NAME)
    }

    @Test
    fun `relative paths contain the folder name`() {
        assertTrue(CaptureMediaFormat.PHOTOS_RELATIVE_PATH.contains(CaptureMediaFormat.PHOTO_DIR_NAME))
        assertTrue(CaptureMediaFormat.VIDEOS_RELATIVE_PATH.contains(CaptureMediaFormat.VIDEO_DIR_NAME))
    }

    @Test
    fun `write paths are the rooted folder and the query prefixes derive from them`() {
        // The exact RELATIVE_PATH RecordingService and PhotoCaptureManager
        // insert — composed once here, never root + "/" + dir at the site.
        assertEquals("Pictures/LensCast", CaptureMediaFormat.PHOTOS_WRITE_RELATIVE_PATH)
        assertEquals("Movies/LensCast", CaptureMediaFormat.VIDEOS_WRITE_RELATIVE_PATH)
        // The queries see the same folders with a trailing slash, so a rename
        // can never desync the write path from the query prefix.
        assertEquals(CaptureMediaFormat.PHOTOS_WRITE_RELATIVE_PATH + "/", CaptureMediaFormat.PHOTOS_RELATIVE_PATH)
        assertEquals(CaptureMediaFormat.VIDEOS_WRITE_RELATIVE_PATH + "/", CaptureMediaFormat.VIDEOS_RELATIVE_PATH)
    }

    @Test
    fun `write paths contain their folder name`() {
        assertTrue(CaptureMediaFormat.PHOTOS_WRITE_RELATIVE_PATH.contains(CaptureMediaFormat.PHOTO_DIR_NAME))
        assertTrue(CaptureMediaFormat.VIDEOS_WRITE_RELATIVE_PATH.contains(CaptureMediaFormat.VIDEO_DIR_NAME))
    }

    @Test
    fun `content uri sniff`() {
        assertTrue(CaptureMediaFormat.isContentUri("content://media/external/images/1"))
        assertFalse(CaptureMediaFormat.isContentUri("/storage/emulated/0/Pictures/LensCast/IMG_1.jpg"))
        assertFalse(CaptureMediaFormat.isContentUri("file:///storage/IMG_1.jpg"))
        assertFalse(CaptureMediaFormat.isContentUri(""))
    }
}

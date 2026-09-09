package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the event→clip linkage: extracting the MediaStore numeric
 * id from the saved clip's content URI (the coordinator links the rest of the
 * clip reference — file name — straight from the capture history entry).
 */
class DetectionEventClipLinkTest {

    @Test
    fun `a media-store video uri yields its numeric id`() {
        assertEquals(
            123456L,
            DetectionEvent.clipMediaIdFromContentUri("content://media/external/video/media/123456"),
        )
    }

    @Test
    fun `a photo content uri parses the same way`() {
        assertEquals(
            42L,
            DetectionEvent.clipMediaIdFromContentUri("content://media/external/images/media/42"),
        )
    }

    @Test
    fun `file paths and legacy schemes yield null`() {
        assertNull(DetectionEvent.clipMediaIdFromContentUri("/storage/emulated/0/Movies/LensCast/VID_1.mp4"))
        assertNull(DetectionEvent.clipMediaIdFromContentUri("file:///Movies/LensCast/VID_1.mp4"))
    }

    @Test
    fun `a non-numeric tail yields null`() {
        assertNull(DetectionEvent.clipMediaIdFromContentUri("content://media/external/video/media/not-a-number"))
        assertNull(DetectionEvent.clipMediaIdFromContentUri("content://media/"))
    }

    @Test
    fun `null and blank paths yield null`() {
        assertNull(DetectionEvent.clipMediaIdFromContentUri(null))
        assertNull(DetectionEvent.clipMediaIdFromContentUri(""))
    }

    @Test
    fun `the linked event carries both clip fields`() {
        val event = DetectionEvent(
            id = "e1",
            type = "motion",
            source = "lenscast",
            timestampMs = 1_000L,
        )
        assertNull(event.clipMediaId)
        assertNull(event.clipFileName)

        val linked = event.copy(
            clipMediaId = DetectionEvent.clipMediaIdFromContentUri("content://media/external/video/media/77"),
            clipFileName = "VID_20260908_10153100.mp4",
        )
        assertEquals(77L, linked.clipMediaId)
        assertEquals("VID_20260908_10153100.mp4", linked.clipFileName)
    }
}

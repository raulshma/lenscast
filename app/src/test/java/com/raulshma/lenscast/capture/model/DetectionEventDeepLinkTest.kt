package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detection event's dashboard deep links, pinned: a linked clip deep-links
 * the gallery viewer on that clip's MediaStore id, everything else lands on
 * the events feed. The push payload, the MQTT/webhook bodies, and the web
 * feed's `url` field all derive through this one builder.
 */
class DetectionEventDeepLinkTest {

    @Test
    fun `a linked clip deep links the gallery viewer on its media id`() {
        assertEquals("#/gallery/123456", DetectionEventDeepLink.gallery(123_456L))
        assertEquals("#/gallery/123456", DetectionEventDeepLink.forClip(123_456L))
        assertEquals("#/gallery/0", DetectionEventDeepLink.forClip(0L))
    }

    @Test
    fun `a not-yet-linked clip deep links the events feed`() {
        assertEquals("#/events", DetectionEventDeepLink.forClip(null))
        assertEquals("#/events", DetectionEventDeepLink.EVENTS)
    }

    @Test
    fun `the gallery hash round-trips the web router's mediaId route`() {
        // The dashboard's hash router maps "#/gallery/<id>" onto
        // { name: 'gallery', mediaId: '<id>' } — the link must keep that shape.
        val link = DetectionEventDeepLink.forClip(42L)
        assertTrue(link.startsWith("#/gallery/"))
        assertEquals("42", link.removePrefix("#/gallery/"))
    }
}

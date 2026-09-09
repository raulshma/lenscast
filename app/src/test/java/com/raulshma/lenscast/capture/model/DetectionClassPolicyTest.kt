package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold, allow-list, ordering, and dedupe verdicts of the ML gate's class
 * filter — the pure half of the motion-suppression decision.
 */
class DetectionClassPolicyTest {

    private fun detection(label: String, score: Float) = DetectionClassPolicy.Detection(label, score)

    @Test
    fun `allowed detection at threshold passes`() {
        // Exactly at the threshold counts (at/above, not above).
        val labels = DetectionClassPolicy.filter(
            listOf(detection("person", 0.60f)),
            minScorePercent = 60,
        )
        assertEquals(listOf("person"), labels)
    }

    @Test
    fun `detection below threshold is dropped`() {
        val labels = DetectionClassPolicy.filter(
            listOf(detection("person", 0.59f)),
            minScorePercent = 60,
        )
        assertTrue(labels.isEmpty())
    }

    @Test
    fun `disallowed coco classes never pass`() {
        val labels = DetectionClassPolicy.filter(
            listOf(
                detection("potted plant", 0.99f),
                detection("chair", 0.98f),
                detection("couch", 0.97f),
            ),
            minScorePercent = 10,
        )
        assertTrue(labels.isEmpty())
    }

    @Test
    fun `labels preserve allow-list order with person first regardless of score order`() {
        val labels = DetectionClassPolicy.filter(
            listOf(
                detection("truck", 0.90f),
                detection("dog", 0.80f),
                detection("person", 0.70f),
                detection("car", 0.60f),
            ),
            minScorePercent = 50,
        )
        assertEquals(listOf("person", "dog", "car", "truck"), labels)
    }

    @Test
    fun `duplicate detections of one class dedupe to a single label`() {
        val labels = DetectionClassPolicy.filter(
            listOf(
                detection("person", 0.90f),
                detection("person", 0.70f),
                detection("Person", 0.60f), // case-insensitive match
            ),
            minScorePercent = 50,
        )
        assertEquals(listOf("person"), labels)
    }

    @Test
    fun `empty detections filter to empty`() {
        assertTrue(DetectionClassPolicy.filter(emptyList(), minScorePercent = 10).isEmpty())
    }

    @Test
    fun `allow-list is exactly the documented person pet vehicle set`() {
        assertEquals(
            listOf(
                "person",
                "cat", "dog", "bird", "horse", "sheep", "cow",
                "bicycle", "car", "motorcycle", "bus", "truck",
            ),
            DetectionClassPolicy.ALLOWED_CLASSES,
        )
    }

    @Test
    fun `human readable capitalizes each word`() {
        assertEquals("Person", DetectionClassPolicy.humanReadable("person"))
        assertEquals("Fire Hydrant", DetectionClassPolicy.humanReadable("fire hydrant"))
    }
}

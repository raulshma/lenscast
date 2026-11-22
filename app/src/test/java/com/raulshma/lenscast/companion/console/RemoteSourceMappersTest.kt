package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.companion.client.dto.GalleryItem
import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import com.raulshma.lenscast.capture.DetectionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure wire→domain projections behind the P4 remote sources (both ends
 * are Moshi data classes, so these are plain JVM asserts — no server, no
 * Robolectric): a gallery item lands on the local gallery model with every
 * field carried (and the absent/nullable ones defaulted, never thrown), the
 * unknown `type` noise reads as a photo, and a detection event round-trips
 * its clip linkage / labels / zones while dropping the dashboard-only deep
 * link. The newest-first seam sort is pinned because it is the
 * [com.raulshma.lenscast.capture.EventsSource] order contract's remote half.
 */
class RemoteSourceMappersTest {

    // ── GalleryItem → CaptureHistory ──

    private fun galleryItem(
        id: String = "m1",
        type: String = "PHOTO",
        favorite: Boolean = false,
        durationMs: Long = 0,
    ) = GalleryItem(
        id = id,
        type = type,
        fileName = "cap-2026-01-02.jpg",
        timestamp = 1_760_000_000_000L,
        fileSizeBytes = 123_456L,
        durationMs = durationMs,
        favorite = favorite,
        thumbnailUrl = "/api/media/$id/thumbnail",
        url = "/api/media/$id",
        downloadUrl = "/api/media/$id?download=1",
    )

    @Test
    fun `a photo item maps every field and carries the resolved media url as its path`() {
        val mapped = galleryItem().toCaptureHistory(mediaUrl = "http://127.0.0.1:8080/api/media/m1")

        assertEquals("m1", mapped.id)
        assertEquals(CaptureType.PHOTO, mapped.type)
        assertEquals("cap-2026-01-02.jpg", mapped.fileName)
        assertEquals(1_760_000_000_000L, mapped.timestamp)
        assertEquals(123_456L, mapped.fileSizeBytes)
        assertEquals(0L, mapped.durationMs)
        assertEquals(false, mapped.favorite)
        // The slot seam's one media address: the caller-resolved absolute
        // route (the wire's url/thumbnailUrl are server-relative paths).
        assertEquals("http://127.0.0.1:8080/api/media/m1", mapped.filePath)
        // Provenance never crossed the wire; the timeline reconstructs it.
        assertNull(mapped.trigger)
    }

    @Test
    fun `a video item maps to the video type and carries duration and favorite`() {
        val mapped = galleryItem(type = "VIDEO", favorite = true, durationMs = 65_000)
            .toCaptureHistory(mediaUrl = "http://d/api/media/m2")

        assertEquals(CaptureType.VIDEO, mapped.type)
        assertEquals(65_000L, mapped.durationMs)
        // Read-only here: the wire carries the flag but defines no toggle.
        assertEquals(true, mapped.favorite)
    }

    @Test
    fun `an unknown wire type degrades to a photo, never a crash`() {
        val mapped = galleryItem(type = "HOLOGRAM").toCaptureHistory(mediaUrl = "http://d/api/media/m3")
        assertEquals(CaptureType.PHOTO, mapped.type)
    }

    // ── RemoteDetectionEvent → DetectionEvent ──

    @Test
    fun `a detection event maps all telemetry including clip linkage`() {
        val remote = RemoteDetectionEvent(
            id = "e1",
            type = "motion",
            source = "camera",
            timestampMs = 42L,
            snapshotJpegBase64 = "aGVsbG8=",
            dispatchedActions = listOf("siren", "webhook"),
            zones = listOf("left", "gate"),
            labels = listOf("person"),
            clipMediaId = 918_273L,
            clipFileName = "clip-0042.mp4",
            url = "#/gallery/918273",
        )
        val mapped = remote.toDetectionEvent()

        assertEquals("e1", mapped.id)
        assertEquals("motion", mapped.type)
        assertEquals("camera", mapped.source)
        assertEquals(42L, mapped.timestampMs)
        assertEquals("aGVsbG8=", mapped.snapshotJpegBase64)
        assertEquals(listOf("siren", "webhook"), mapped.dispatchedActions)
        assertEquals(listOf("left", "gate"), mapped.zones)
        assertEquals(listOf("person"), mapped.labels)
        assertEquals(918_273L, mapped.clipMediaId)
        assertEquals("clip-0042.mp4", mapped.clipFileName)
    }

    @Test
    fun `an unlinked event keeps its nullable fields absent and drops the dashboard deep link`() {
        val mapped = RemoteDetectionEvent(
            id = "e2",
            type = "sound",
            source = "mic",
            timestampMs = 7L,
        ).toDetectionEvent()

        assertNull(mapped.snapshotJpegBase64)
        assertNull(mapped.clipMediaId)
        assertNull(mapped.clipFileName)
        assertEquals(emptyList<String>(), mapped.dispatchedActions)
        assertEquals(emptyList<String>(), mapped.zones)
        assertEquals(emptyList<String>(), mapped.labels)
    }

    // ── the newest-first seam sort ──

    @Test
    fun `the events list normalizes to newest-first regardless of arrival order`() {
        val mixed = listOf(
            DetectionEvent(id = "old", type = "motion", source = "s", timestampMs = 1L),
            DetectionEvent(id = "new", type = "motion", source = "s", timestampMs = 3L),
            DetectionEvent(id = "mid", type = "motion", source = "s", timestampMs = 2L),
        )
        assertEquals(listOf("new", "mid", "old"), mixed.newestFirst().map { it.id })
    }
}

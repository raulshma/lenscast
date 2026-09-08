package com.raulshma.lenscast.gallery

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The gallery's pure math: overview counts, day grouping with an injected
 * "today", and the byte/duration formatters — no Android dependencies.
 * java.time is allowed here (JVM-only) and doubles as the oracle for the
 * epoch-day arithmetic the main code can no longer use.
 */
class GalleryMediaTest {

    private val todayDate: LocalDate = LocalDate.of(2026, 9, 7)
    private val today: Long = todayDate.toEpochDay()

    private fun atDay(day: LocalDate, plusMillis: Long = 0): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + plusMillis

    private fun item(
        id: String,
        timestamp: Long,
        type: CaptureType = CaptureType.PHOTO,
        sizeBytes: Long = 100L,
    ) = CaptureHistory(
        id = id,
        type = type,
        fileName = "$id.jpg",
        filePath = "/tmp/$id.jpg",
        timestamp = timestamp,
        fileSizeBytes = sizeBytes,
    )

    // ── buildGalleryOverview ──

    @Test
    fun `overview counts photos, videos, bytes and distinct days`() {
        val overview = buildGalleryOverview(
            listOf(
                item("a", atDay(todayDate)),
                item("b", atDay(todayDate), type = CaptureType.VIDEO, sizeBytes = 50L),
                item("c", atDay(todayDate.minusDays(3))),
                item("d", atDay(todayDate.minusDays(3))),
            )
        )
        assertEquals(4, overview.totalCount)
        assertEquals(3, overview.photoCount)
        assertEquals(1, overview.videoCount)
        assertEquals(350L, overview.totalBytes)
        assertEquals(2, overview.dayCount)
    }

    @Test
    fun `negative sizes never reduce the total`() {
        val overview = buildGalleryOverview(
            listOf(item("a", atDay(todayDate), sizeBytes = 100L), item("b", atDay(todayDate), sizeBytes = -5L))
        )
        assertEquals(100L, overview.totalBytes)
    }

    // ── buildGallerySections + formatGallerySectionTitle ──

    @Test
    fun `sections group by day, newest first, items newest first within a day`() {
        val sections = buildGallerySections(
            listOf(
                item("early", atDay(todayDate, plusMillis = 1_000), sizeBytes = 2000L),
                item("late", atDay(todayDate, plusMillis = 2_000), sizeBytes = 1000L),
                item("old", atDay(todayDate.minusDays(9))),
            ),
            todayEpochDay = today,
        )
        assertEquals(2, sections.size)
        assertEquals("Today", sections[0].title)
        assertEquals(listOf("late", "early"), sections[0].items.map { it.id })
        assertEquals(2000L + 1000L + 100L, sections[0].totalBytes + sections[1].totalBytes)
        assertEquals(todayDate.toString(), sections[0].key)
    }

    @Test
    fun `today and yesterday titles derive from the injected today`() {
        assertEquals("Today", formatGallerySectionTitle(today, today))
        assertEquals("Yesterday", formatGallerySectionTitle(today - 1, today))
        assertEquals("Today", formatGallerySectionTitle(LocalDate.of(2020, 1, 2).toEpochDay(), LocalDate.of(2020, 1, 2).toEpochDay()))
    }

    @Test
    fun `older days fall back to the formatted date, never Today or Yesterday`() {
        val title = formatGallerySectionTitle(today - 9, today)
        assertNotEquals("Today", title)
        assertNotEquals("Yesterday", title)
        assertTrue(title.isNotBlank())
    }

    // ── formatFileSize ──

    @Test
    fun `non-positive sizes are unknown`() {
        assertEquals("Unknown size", formatFileSize(0L))
        assertEquals("Unknown size", formatFileSize(-1L))
    }

    @Test
    fun `byte-range sizes stay unrounded with the B unit`() {
        assertEquals("512 B", formatFileSize(512L))
        assertEquals("999 B", formatFileSize(999L))
    }

    @Test
    fun `larger sizes climb the unit ladder`() {
        assertEquals("10 KB", formatFileSize(10L * 1024))
        assertEquals("10 MB", formatFileSize(10L * 1024 * 1024))
        assertEquals("10 GB", formatFileSize(10L * 1024 * 1024 * 1024))
    }

    @Test
    fun `sub-10 scaled values keep one decimal`() {
        val text = formatFileSize(2048L)
        assertTrue("unexpected formatting: $text", text.startsWith("2.0 KB") || text.startsWith("2,0 KB"))
    }

    // ── formatDuration ──

    @Test
    fun `durations format as m s or h m s`() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:00", formatDuration(-100L))
        assertEquals("0:59", formatDuration(59_999L))
        assertEquals("1:01", formatDuration(61_000L))
        assertEquals("1:01:01", formatDuration(3_661_000L))
    }
}

package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.model.DetectionEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The export's CSV escaping, pinned without a store: quotes, commas, and
 * newlines inside a zone label or file name must survive the round trip. The
 * JSON export's snapshot strip rides the same mechanism this file pins — a
 * null `snapshotJpegBase64` never serializes — so the megabytes of base64 the
 * feed carries cannot ride a download.
 */
class DetectionEventsExportTest {

    @Test
    fun `plain fields pass through unquoted`() {
        assertEquals("Driveway", DetectionEventCsv.escape("Driveway"))
    }

    @Test
    fun `commas quotes and newlines are rfc4180 quoted`() {
        assertEquals("\"Front, Yard\"", DetectionEventCsv.escape("Front, Yard"))
        assertEquals("\"He said \"\"hi\"\"\"", DetectionEventCsv.escape("He said \"hi\""))
        assertEquals("\"line1\nline2\"", DetectionEventCsv.escape("line1\nline2"))
        assertEquals("\"cr\r\"", DetectionEventCsv.escape("cr\r"))
    }

    @Test
    fun `formula trigger prefixes are guard quoted`() {
        // A user-authored label beginning with a formula trigger would execute
        // as a formula when the export opens in Excel/LibreOffice.
        assertEquals("'=SUM(A1)", DetectionEventCsv.escape("=SUM(A1)"))
        assertEquals("'@cmd", DetectionEventCsv.escape("@cmd"))
        assertEquals("'+1", DetectionEventCsv.escape("+1"))
        assertEquals("'-1", DetectionEventCsv.escape("-1"))
        assertEquals("'\tTAB", DetectionEventCsv.escape("\tTAB"))
        // Mid-field triggers are inert — only the first character guards.
        assertEquals("1+1", DetectionEventCsv.escape("1+1"))
        assertEquals("Driveway", DetectionEventCsv.escape("Driveway"))
    }

    @Test
    fun `guard and quoting compose for hostile leading fields`() {
        assertEquals("\"'=SUM(A1),B1\"", DetectionEventCsv.escape("=SUM(A1),B1"))
    }

    @Test
    fun `a null snapshot serializes to nothing in the export json`() {
        val adapter = AppJson.moshi.adapter(DetectionEventDto::class.java)
        val json = adapter.toJson(
            DetectionEventDto(
                id = "id-1",
                type = "motion",
                source = "lenscast",
                timestampMs = 1_788_825_600_000,
                snapshotJpegBase64 = null,
            ),
        )
        assertFalse(json.contains("snapshotJpegBase64"))
    }
}

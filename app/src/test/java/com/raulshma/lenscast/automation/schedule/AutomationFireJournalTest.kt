package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/**
 * The fire journal's persistence contract, mirroring
 * [com.raulshma.lenscast.streaming.StreamStateJournalTest]: total decode
 * (missing/malformed file → empty), atomic round-trip, and the per-rule
 * key overwrite — the dedup state the scheduler's boot catch-up trusts —
 * plus the load-time sanitization ([JournalEntry.decode]'s verdicts over
 * the two Long encodings the map carries).
 */
class AutomationFireJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun journal(): AutomationFireJournal =
        AutomationFireJournal(File(tmp.root, "automation/fire_journal.json"))

    @Test
    fun `missing file loads an empty journal`() {
        assertEquals(emptyMap<String, Long>(), journal().load())
    }

    @Test
    fun `record and load round-trip`() {
        val target = journal()
        target.record("rule-1", 100L * 1_440 + 600)
        assertEquals(mapOf("rule-1" to 100L * 1_440 + 600), target.load())
    }

    @Test
    fun `record overwrites the previous key for the same rule`() {
        val target = journal()
        target.record("rule-1", 1L)
        target.record("rule-1", 2L)
        assertEquals(mapOf("rule-1" to 2L), target.load())
    }

    @Test
    fun `record keeps other rules' keys`() {
        val target = journal()
        target.record("rule-1", 1L)
        target.record("rule-2", 2L)
        assertEquals(mapOf("rule-1" to 1L, "rule-2" to 2L), target.load())
    }

    @Test
    fun `malformed file loads empty and the next record rebuilds it`() {
        val file = File(tmp.root, "automation/fire_journal.json")
        file.parentFile.mkdirs()
        file.writeText("{not json")
        assertEquals(emptyMap<String, Long>(), journal().load())
        journal().record("rule-1", 5L)
        assertEquals(mapOf("rule-1" to 5L), journal().load())
    }

    @Test
    fun `a fresh instance sees a prior instance's records (restart semantics)`() {
        journal().record("rule-1", 7L)
        assertEquals(mapOf("rule-1" to 7L), journal().load())
    }

    // ── JournalEntry.decode ──

    @Test
    fun `decode splits an occurrence key into epoch day and minute`() {
        val zone = TimeZone.getDefault()
        assertEquals(
            JournalEntry.Occurrence(epochDay = 100L, minute = 600),
            JournalEntry.decode(100L * 1_440 + 600, zone),
        )
    }

    @Test
    fun `decode keeps event-range values as-is`() {
        val zone = TimeZone.getDefault()
        assertEquals(
            JournalEntry.EventFired(atMs = 1_800_000_000_000L),
            JournalEntry.decode(1_800_000_000_000L, zone),
        )
    }

    @Test
    fun `decode rejects negative values`() {
        assertNull(JournalEntry.decode(-1L, TimeZone.getDefault()))
    }

    @Test
    fun `decode treats the threshold value as the event range`() {
        assertEquals(
            JournalEntry.EventFired(atMs = AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD),
            JournalEntry.decode(AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD, TimeZone.getDefault()),
        )
    }

    // ── Load-time sanitization ──

    @Test
    fun `load drops negative values and keeps both decodable kinds`() {
        val file = File(tmp.root, "automation/fire_journal.json")
        file.parentFile.mkdirs()
        val occurrenceKey = 100L * 1_440 + 600
        val eventFireMs = 1_800_000_000_000L
        file.writeText("""{"bad": -5, "occurrence": $occurrenceKey, "event": $eventFireMs}""")

        assertEquals(
            mapOf("occurrence" to occurrenceKey, "event" to eventFireMs),
            journal().load(),
        )
    }

    @Test
    fun `a sanitized load is not rewritten by the next record`() {
        val file = File(tmp.root, "automation/fire_journal.json")
        file.parentFile.mkdirs()
        file.writeText("""{"bad": -5, "occurrence": ${100L * 1_440 + 600}}""")
        journal().record("new-rule", 7L)

        assertEquals(
            mapOf("occurrence" to 100L * 1_440 + 600, "new-rule" to 7L),
            journal().load(),
        )
    }
}

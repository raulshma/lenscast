package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The fire journal's persistence contract, mirroring
 * [com.raulshma.lenscast.streaming.StreamStateJournalTest]: total decode
 * (missing/malformed file → empty), atomic round-trip, and the per-rule
 * key overwrite — the dedup state the scheduler's boot catch-up trusts.
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
}

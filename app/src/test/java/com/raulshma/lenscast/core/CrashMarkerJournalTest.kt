package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashMarkerJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun journal(): CrashMarkerJournal =
        CrashMarkerJournal(File(tmp.root, "crash/crash_marker.json"))

    @Test
    fun `missing file loads a zero counter`() {
        assertEquals(CrashMarkerJournal.State(), journal().load())
    }

    @Test
    fun `recordCrash persists the counter and the timestamp`() {
        val target = journal()
        target.recordCrash(crashCount = 2, atMs = 1_700_000_000_000)
        assertEquals(
            CrashMarkerJournal.State(consecutiveCrashes = 2, lastCrashAtMs = 1_700_000_000_000),
            target.load(),
        )
    }

    @Test
    fun `recordCrash overwrites the previous counter`() {
        val target = journal()
        target.recordCrash(1)
        target.recordCrash(3)
        assertEquals(3, target.load().consecutiveCrashes)
    }

    @Test
    fun `reset zeroes the counter`() {
        val target = journal()
        target.recordCrash(CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES + 1)
        target.reset()
        assertEquals(CrashMarkerJournal.State(), target.load())
    }

    @Test
    fun `corrupt file falls back to a zero counter`() {
        val file = File(tmp.root, "crash/crash_marker.json")
        file.parentFile.mkdirs()
        file.writeText("{not json")
        assertEquals(CrashMarkerJournal.State(), journal().load())
    }

    @Test
    fun `legacy json without the timestamp loads with the default`() {
        val file = File(tmp.root, "crash/crash_marker.json")
        file.parentFile.mkdirs()
        file.writeText("""{"consecutiveCrashes":2}""")
        val state = journal().load()
        assertEquals(2, state.consecutiveCrashes)
        assertEquals(0L, state.lastCrashAtMs)
    }

    @Test
    fun `gaveUp derives from the counter against the policy max`() {
        journal().recordCrash(CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES + 1)
        assertTrue(journal().load().gaveUp)
    }
}

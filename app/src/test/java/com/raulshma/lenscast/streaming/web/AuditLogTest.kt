package com.raulshma.lenscast.streaming.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The audit trail's file-backed behavior: append newest-first, the cap,
 * persistence across instances, and the clear. No Android types — the log is
 * a plain file store like the detection event log.
 */
class AuditLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var nowMs = 1_000L

    private fun newLog(): AuditLog = AuditLog(
        file = java.io.File(tmp.root, "audit_log.json"),
        nowMs = { nowMs },
    )

    @Test
    fun `entries append newest-first with stamped time and outcome`() {
        val log = newLog()
        log.record("PUT /api/settings")
        nowMs = 2_000L
        log.record("login.failed", detail = "192.168.1.20", outcome = AuditEntry.OUTCOME_ERROR)

        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals("login.failed", entries[0].action)
        assertEquals(2_000L, entries[0].timestampMs)
        assertEquals("192.168.1.20", entries[0].detail)
        assertEquals(AuditEntry.OUTCOME_ERROR, entries[0].outcome)
        assertEquals("PUT /api/settings", entries[1].action)
        assertEquals(AuditEntry.OUTCOME_OK, entries[1].outcome)
    }

    @Test
    fun `the cap evicts the oldest`() {
        val log = newLog()
        repeat(AuditLog.MAX_ENTRIES + 10) { index ->
            log.record("action-$index")
        }
        val entries = log.entries()
        assertEquals(AuditLog.MAX_ENTRIES, entries.size)
        // Oldest ten evicted: the first surviving entry is action-10.
        assertEquals("action-10", entries.last().action)
        assertEquals("action-${AuditLog.MAX_ENTRIES + 9}", entries.first().action)
    }

    @Test
    fun `entries persist and reload across instances`() {
        val file = java.io.File(tmp.root, "audit_log.json")
        val first = AuditLog(file = file, nowMs = { 5_000L })
        first.record("POST /api/capture")
        first.record("DELETE /api/audit")

        val second = AuditLog(file = file, nowMs = { 6_000L })
        val entries = second.entries()
        assertEquals(2, entries.size)
        assertEquals("DELETE /api/audit", entries[0].action)
        assertEquals("POST /api/capture", entries[1].action)
    }

    @Test
    fun `limit bounds the read, not the store`() {
        val log = newLog()
        repeat(5) { index -> log.record("action-$index") }
        assertEquals(3, log.entries(limit = 3).size)
        assertEquals(5, log.count())
    }

    @Test
    fun `non-positive limits mean the whole trail`() {
        val log = newLog()
        repeat(3) { index -> log.record("action-$index") }
        assertEquals(3, log.entries(limit = 0).size)
        assertEquals(3, log.entries(limit = -1).size)
    }

    @Test
    fun `clear empties the trail and persists the empty state`() {
        val file = java.io.File(tmp.root, "audit_log.json")
        val first = newLog().let { log ->
            log.record("PUT /api/settings")
            log
        }
        first.clear()
        assertEquals(0, AuditLog(file = file).count())
        assertTrue(first.entries().isEmpty())
    }
}

package com.raulshma.lenscast.data

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {

    private val now = 1_000_000_000L

    @Test
    fun `zero days means keep forever - no cutoff`() {
        assertNull(RetentionPolicy.cutoffMs(now, StreamDefaults.RETENTION_DAYS_DISABLED))
        assertNull(RetentionPolicy.cutoffMs(now, 0))
    }

    @Test
    fun `a negative window is treated like disabled - fail closed to keep`() {
        assertNull(RetentionPolicy.cutoffMs(now, -7))
    }

    @Test
    fun `cutoff is exactly now minus days`() {
        assertEquals(now - RetentionPolicy.MS_PER_DAY, RetentionPolicy.cutoffMs(now, 1))
        assertEquals(now - 30 * RetentionPolicy.MS_PER_DAY, RetentionPolicy.cutoffMs(now, 30))
        assertEquals(now - 365 * RetentionPolicy.MS_PER_DAY, RetentionPolicy.cutoffMs(now, 365))
    }

    @Test
    fun `entries older than the window delete - boundary entries stay`() {
        assertTrue(RetentionPolicy.shouldDelete(now - 8 * RetentionPolicy.MS_PER_DAY, now, 7))
        // Exactly at the cutoff is not "older than" — it survives.
        assertFalse(RetentionPolicy.shouldDelete(now - 7 * RetentionPolicy.MS_PER_DAY, now, 7))
        assertFalse(RetentionPolicy.shouldDelete(now, now, 7))
    }

    @Test
    fun `disabled retention never deletes`() {
        assertFalse(RetentionPolicy.shouldDelete(0L, now, 0))
    }

    @Test
    fun `prune keeps only in-window entries and reports them`() {
        val entries = listOf("old", "boundary", "new")
        val cutoffBoundary = now - 7 * RetentionPolicy.MS_PER_DAY
        val createdAt = mapOf(
            "old" to cutoffBoundary - 1,
            "boundary" to cutoffBoundary,
            "new" to now,
        )
        val pruned = RetentionPolicy.pruneEntries(entries, { createdAt.getValue(it) }, now, 7)
        assertEquals(listOf("boundary", "new"), pruned)
    }

    @Test
    fun `prune answers null when nothing aged out so callers skip the rewrite`() {
        val entries = listOf("a", "b")
        assertNull(RetentionPolicy.pruneEntries(entries, { now }, now, 30))
    }

    @Test
    fun `prune answers null when retention is disabled`() {
        assertNull(RetentionPolicy.pruneEntries(listOf("a"), { 0L }, now, 0))
    }

    @Test
    fun `prune works generically over the event and capture shapes`() {
        data class E(val t: Long)
        val pruned = RetentionPolicy.pruneEntries(listOf(E(1L), E(now)), { it.t }, now, 1)
        assertEquals(listOf(E(now)), pruned)
    }
}

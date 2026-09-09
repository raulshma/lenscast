package com.raulshma.lenscast.data

import com.raulshma.lenscast.core.StreamDefaults

/**
 * Pure time-based retention verdicts for the capture history and the
 * detection-event log. A retention window of
 * [StreamDefaults.RETENTION_DAYS_DISABLED] (0) means "keep forever": every
 * verdict below answers conservatively (no cutoff, nothing deleted). Callers
 * supply `nowMs` so the whole ladder stays JVM-testable; the persistence
 * layers ([CaptureHistoryStore], [DetectionEventStore]) consult it on open,
 * on prune, and after each append.
 */
object RetentionPolicy {

    /** Milliseconds in one retention day. */
    const val MS_PER_DAY: Long = 24L * 60 * 60 * 1000

    /**
     * The epoch-ms boundary entries must be newer than to survive, or null
     * when retention is disabled — the one sentinel decode of
     * [StreamDefaults.RETENTION_DAYS_DISABLED] and any negative value.
     */
    fun cutoffMs(nowMs: Long, days: Int): Long? {
        if (days <= StreamDefaults.RETENTION_DAYS_DISABLED) return null
        return nowMs - days.toLong() * MS_PER_DAY
    }

    /** True when an entry created at [createdAtMs] is older than the window. */
    fun shouldDelete(createdAtMs: Long, nowMs: Long, days: Int): Boolean {
        val cutoff = cutoffMs(nowMs, days) ?: return false
        return createdAtMs < cutoff
    }

    /**
     * The pruned list, or null when nothing should change: retention off, or
     * every entry still inside the window. The null-vs-empty distinction lets
     * callers skip a persistence rewrite when retention is a no-op.
     */
    fun <T> pruneEntries(
        entries: List<T>,
        createdAtMs: (T) -> Long,
        nowMs: Long,
        days: Int,
    ): List<T>? {
        val cutoff = cutoffMs(nowMs, days) ?: return null
        val kept = entries.filter { createdAtMs(it) >= cutoff }
        return if (kept.size == entries.size) null else kept
    }
}

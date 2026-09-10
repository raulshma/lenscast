package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Types
import java.io.File

/**
 * One audited action: a mutating Web API dispatch (`PUT /api/settings`) or an
 * auth outcome (`login.failed` from 192.168.1.20). Newest first, capped — the
 * same shape the detection event log popularized, minus the snapshots.
 */
data class AuditEntry(
    val timestampMs: Long,
    /** `"$method $path"` for a route dispatch, `login.success` / `login.failed` for auth. */
    val action: String,
    val detail: String = "",
    val outcome: String = OUTCOME_OK,
) {
    companion object {
        const val OUTCOME_OK = "ok"
        const val OUTCOME_ERROR = "error"
    }
}

/**
 * The file-backed audit trail for the Web API's mutating surface. The writer
 * set is the ApiRouter (every POST/PUT/DELETE it dispatches) and the
 * StreamingServer (login outcomes); the reader is the dashboard's audit card
 * through [AuditWebHandler]. One instance per process — the manager's
 * WebApiStack builds it and hands the same reference to both, like every
 * other shared store. Writes are atomic (tmp + rename), so a crash mid-write
 * never corrupts the log; losing the persisted copy only costs the history.
 *
 * The audit must never break the audited path: every append is wrapped by the
 * callers' runCatching, and the class itself only does synchronous,
 * allocation-bounded work — string fields, a capped list, one file write.
 */
class AuditLog(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    // Declared before [entries]: load() runs in the entries initializer, and
    // declaration-order init (not lazy) is what keeps load() seeing it.
    private val listAdapter = AppJson.moshi.adapter<List<AuditEntry>>(
        Types.newParameterizedType(List::class.java, AuditEntry::class.java),
    )

    private var entries: List<AuditEntry> = load()

    /** Append newest-first, evict past the cap, persist. */
    fun record(action: String, detail: String = "", outcome: String = AuditEntry.OUTCOME_OK) {
        synchronized(lock) {
            entries = (listOf(AuditEntry(timestampMs = nowMs(), action = action, detail = detail, outcome = outcome)) + entries)
                .take(maxEntries)
            persistLocked()
        }
    }

    /** Newest-first read, at most [limit] entries (null, zero, or negative = all). */
    fun entries(limit: Int? = null): List<AuditEntry> = synchronized(lock) {
        val list = entries
        if (limit == null || limit <= 0 || limit >= list.size) list else list.take(limit)
    }

    fun count(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries = emptyList()
            persistLocked()
        }
    }

    private fun load(): List<AuditEntry> = file.readJsonOrDefault(
        listAdapter,
        emptyList(),
        warn = "Failed to read audit log; starting clean",
    )

    private fun persistLocked() {
        file.writeAtomicallyOrWarn(
            listAdapter.toJson(entries),
            warn = "Failed to persist audit log",
        )
    }

    companion object {
        /** Audit history cap: plenty for a review window, bounded on disk. */
        const val MAX_ENTRIES = 200
    }
}

package com.raulshma.lenscast.automation.schedule

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Types
import java.io.File
import java.util.TimeZone

/**
 * One journaled value's decoded shape. The on-disk map (`rule id → Long`)
 * carries two encodings in the one Long, split by
 * [AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD] the same way the
 * schedule policy reads it:
 *
 * - [Occurrence] — a time-rule occurrence key
 *   (`epochDay * [StreamDefaults.MINUTES_PER_DAY] + minute`), below the
 *   threshold; [decode] splits it back into its day/minute fields.
 * - [EventFired] — an event rule's last-fire epoch millisecond, at or above
 *   the threshold; a real instant cannot be told apart from garbage of the
 *   same magnitude, so it is kept as-is.
 *
 * The writers are [AutomationFireJournal.record] (occurrence keys) and the
 * scheduler's event path (fire timestamps); the readers are the schedule
 * policy's two evaluation paths. [decode] is total: a negative value —
 * the one corruption the encoding can actually refute — decodes to null and
 * is dropped on load.
 */
sealed interface JournalEntry {
    /** A time-rule occurrence key split back into its wall-clock fields. */
    data class Occurrence(val epochDay: Long, val minute: Int) : JournalEntry

    /** An event rule's last-fire epoch millisecond, verbatim. */
    data class EventFired(val atMs: Long) : JournalEntry

    companion object {
        /**
         * The total decode of one journaled value, or null when the value is
         * refutable corruption (negative). Occurrence-range values gate
         * through [AutomationSchedulePolicy.decodeOccurrenceKey] — the same
         * decoder the "last fired" surface uses — so anything it refuses
         * never becomes an entry; event-range values pass through as
         * [EventFired] untouched.
         */
        fun decode(value: Long, zone: TimeZone): JournalEntry? {
            if (value < 0) return null
            if (value >= AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD) {
                return EventFired(value)
            }
            // The occurrence half: the schedule policy's occurrence decode is
            // the validity gate (it nulls what it cannot decode); a pass
            // splits the key back into the fields it was minted from.
            if (AutomationSchedulePolicy.decodeOccurrenceKey(value, zone) == null) return null
            return Occurrence(
                epochDay = value / StreamDefaults.MINUTES_PER_DAY,
                minute = (value % StreamDefaults.MINUTES_PER_DAY).toInt(),
            )
        }
    }
}

/**
 * The automation fire journal: an atomic-write file in app-private filesDir
 * (the [com.raulshma.lenscast.core.writeAtomically] shape, mirroring
 * [com.raulshma.lenscast.streaming.StreamStateJournal]) recording, per rule
 * id, the [AutomationSchedulePolicy.occurrenceKey] of the last occurrence
 * that actually fired.
 *
 * Why the journal exists: the scheduler is an in-process poller, so its
 * dedup state would otherwise die with the process. The rules themselves
 * live in the DataStore and survive every process start — process start runs
 * MainApplication.onCreate → the scheduler starts → its immediate first
 * evaluation reads the persisted rules — so without a journal every rule
 * whose minute had already passed would fire all over again on that first
 * tick (a reboot, a crash, or the user swiping the app away would replay the
 * whole day). The journal is that missing half of the dedup: it survives the
 * process, so the boot-time catch-up sees "this occurrence already ran" and
 * blocks the double-fire. Rule edits are covered the same way — the journal
 * keys on the stable rule id, so renaming or re-timing a rule never erases
 * the fact that its occurrence fired. That is also why no boot receiver is
 * needed: the catch-up IS the first poll evaluation, and the journal is what
 * keeps it honest.
 *
 * [load] is a disk read on purpose (the file is tiny — one small long per
 * rule id) and cheap enough to call per evaluation, so a caller never works
 * off a stale in-memory copy. [record] merges over the freshly-read disk
 * state under a lock and persists atomically.
 */
class AutomationFireJournal(private val file: File) {

    constructor(context: Context) : this(File(File(context.filesDir, DIRECTORY), FILE_NAME))

    private val lock = Any()

    private val adapter by lazy {
        AppJson.moshi.adapter<Map<String, Long>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Long::class.javaObjectType),
        )
    }

    /**
     * The rule id → last fired value map, straight from disk, sanitized:
     * every value gates through [JournalEntry.decode] and refutable
     * corruption (negatives, occurrence-range values the occurrence decode
     * refuses) is dropped — the schedule policy would otherwise read a
     * corrupt occurrence-range value as a real "already fired" minute.
     * Event-range values are kept verbatim (a real epoch-ms cannot be told
     * apart from garbage of the same magnitude — see [JournalEntry]). A
     * missing or malformed file still means "nothing has fired" — a corrupt
     * journal costs at most one re-fire of an occurrence, never a lost one.
     */
    fun load(): Map<String, Long> {
        val raw = file.readJsonOrDefault(
            adapter,
            emptyMap(),
            warn = "Failed to read automation fire journal; rules may re-fire once",
        )
        val zone = TimeZone.getDefault()
        return raw.filterValues { value -> JournalEntry.decode(value, zone) != null }
    }

    /**
     * Records [ruleId]'s last fired [occurrenceKey]: merges over the current
     * disk state under the journal lock (so two near-simultaneous firings
     * serialize and neither key is lost) and persists atomically. Losing the
     * write costs one re-fire after a restart — logged, never thrown.
     */
    fun record(ruleId: String, occurrenceKey: Long) {
        synchronized(lock) {
            val merged = LinkedHashMap(load())
            merged[ruleId] = occurrenceKey
            file.writeAtomicallyOrWarn(
                adapter.toJson(merged),
                warn = "Failed to persist automation fire journal",
            )
        }
    }

    companion object {
        private const val DIRECTORY = "automation"
        private const val FILE_NAME = "fire_journal.json"
    }
}

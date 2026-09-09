package com.raulshma.lenscast.capture

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Types
import java.io.File

/**
 * One persisted detection event: what fired, when, the snapshot at trigger
 * time, and the actions the coordinator dispatched. Serialized through App
 * Json into `filesDir/detection_events.json`, newest first, capped by
 * [DetectionEventLogPolicy].
 */
data class DetectionEvent(
    val id: String,
    val type: String,
    val source: String,
    val timestampMs: Long,
    val snapshotJpegBase64: String? = null,
    val dispatchedActions: List<String> = emptyList(),
)

/**
 * File-backed detection-event log for the Web API event feed and the webhook
 * payloads. Resolved per process through [get] — the writer
 * ([DetectionCoordinator]) and the reader (`DetectionEventsWebHandler`) must
 * share one in-memory list, since every mutation persists the whole log; two
 * instances would fork it. Writes are atomic (tmp file + rename, via
 * [com.raulshma.lenscast.core.writeAtomically]), so a crash mid-write never
 * corrupts the log.
 */
class DetectionEventStore private constructor(private val file: File) {

    private val lock = Any()
    private var events: List<DetectionEvent> = load()

    /** Append one event (newest first), evict the oldest past the cap, persist. */
    fun record(event: DetectionEvent) {
        synchronized(lock) {
            events = DetectionEventLogPolicy.append(events, event)
            persistLocked()
        }
    }

    /** Newest-first read, at most [limit] entries (clamped by the policy). */
    fun events(limit: Int? = null): List<DetectionEvent> = synchronized(lock) {
        DetectionEventLogPolicy.readNewestFirst(events, limit)
    }

    fun count(): Int = synchronized(lock) { events.size }

    fun clear() {
        synchronized(lock) {
            events = emptyList()
            persistLocked()
        }
    }

    private val listAdapter by lazy {
        AppJson.moshi.adapter<List<DetectionEvent>>(
            Types.newParameterizedType(List::class.java, DetectionEvent::class.java),
        )
    }

    private fun load(): List<DetectionEvent> = file.readJsonOrDefault(
        listAdapter,
        emptyList(),
        warn = "Failed to read detection events; starting clean",
    )

    private fun persistLocked() {
        // Losing the persisted copy only costs the event history.
        file.writeAtomicallyOrWarn(
            listAdapter.toJson(events),
            warn = "Failed to persist detection events",
        )
    }

    companion object {
        private const val FILE_NAME = "detection_events.json"

        @Volatile
        private var instance: DetectionEventStore? = null

        fun get(context: Context): DetectionEventStore = instance ?: synchronized(this) {
            instance ?: DetectionEventStore(
                File(context.applicationContext.filesDir, FILE_NAME),
            ).also { instance = it }
        }
    }
}

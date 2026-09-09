package com.raulshma.lenscast.capture

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.raulshma.lenscast.data.RetentionPolicy
import com.squareup.moshi.Types
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One persisted detection event: what fired, when, the snapshot at trigger
 * time, the actions the coordinator dispatched, and — for a motion event that
 * triggered a bounded recording — the resulting clip reference once the
 * recording finalized. Serialized through App Json into
 * `filesDir/detection_events.json`, newest first, capped by
 * [DetectionEventLogPolicy].
 */
data class DetectionEvent(
    val id: String,
    val type: String,
    val source: String,
    val timestampMs: Long,
    val snapshotJpegBase64: String? = null,
    val dispatchedActions: List<String> = emptyList(),
    /** Labels of the motion zones that fired; empty for whole-frame or non-motion events. */
    val zones: List<String> = emptyList(),
    /**
     * ML class labels (person, dog, car...) the object-detection gate attached
     * to this motion event; empty when the gate is off, unavailable, or the
     * event was not motion-gated.
     */
    val labels: List<String> = emptyList(),
    /** MediaStore numeric id of the motion clip, once the bounded recording finalized; null until (or unless) linked. */
    val clipMediaId: Long? = null,
    /** File name of the motion clip, linked together with [clipMediaId]. */
    val clipFileName: String? = null,
) {
    companion object {
        /**
         * The MediaStore numeric id behind a content URI (`.../media/<id>`),
         * or null for non-content paths and non-numeric tails — the pure half
         * of the event→clip linkage the coordinator performs.
         */
        fun clipMediaIdFromContentUri(filePath: String?): Long? {
            if (filePath == null || !filePath.startsWith("content://")) return null
            return filePath.substringAfterLast('/').toLongOrNull()
        }
    }
}

/**
 * File-backed detection-event log for the Web API event feed, the SSE stream,
 * and the webhook payloads. Resolved per process through [get] — the writer
 * ([DetectionCoordinator]) and the readers (`DetectionEventsWebHandler`,
 * the SSE pump) must share one in-memory list, since every mutation persists
 * the whole log; two instances would fork it. Writes are atomic (tmp file +
 * rename, via [com.raulshma.lenscast.core.writeAtomically]), so a crash
 * mid-write never corrupts the log.
 *
 * Two live surfaces ride every mutation: the retention sweep
 * ([RetentionPolicy] over the injected days provider — consulted on open and
 * after each append) and the [eventsFlow] hot stream (drop-oldest, no
 * replay) the SSE endpoint tails.
 */
class DetectionEventStore private constructor(
    private val file: File,
    /** The live event-retention window in days (0 = keep forever). */
    private val retentionDays: () -> Int = { StreamDefaults.RETENTION_DAYS_DISABLED },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var events: List<DetectionEvent> = load()

    private val _eventsFlow = MutableSharedFlow<DetectionEvent>(
        replay = 0,
        extraBufferCapacity = SSE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every recorded or updated event, live. No replay: SSE clients read the backlog through [events]. */
    val eventsFlow: SharedFlow<DetectionEvent> = _eventsFlow

    init {
        // The open-time sweep: entries the window already aged out never
        // reach the feed.
        synchronized(lock) {
            val kept = RetentionPolicy.pruneEntries(events, { it.timestampMs }, nowMs(), retentionDays())
            if (kept != null) {
                events = kept
                persistLocked()
            }
        }
    }

    /** Append one event (newest first), evict the oldest past the cap and the window, persist, publish. */
    fun record(event: DetectionEvent) {
        synchronized(lock) {
            events = DetectionEventLogPolicy.append(events, event)
            pruneRetentionLocked()
            persistLocked()
        }
        _eventsFlow.tryEmit(event)
    }

    /**
     * The post-event update path (the event→clip linkage): applies
     * [transform] to the event with [id] under the lock, persists, and
     * publishes the updated event. No-op when no event carries that id.
     */
    fun updateEvent(id: String, transform: (DetectionEvent) -> DetectionEvent) {
        var updated: DetectionEvent? = null
        synchronized(lock) {
            val index = events.indexOfFirst { it.id == id }
            if (index >= 0) {
                val newEvent = transform(events[index])
                events = events.toMutableList().also { it[index] = newEvent }
                persistLocked()
                updated = newEvent
            }
        }
        // Same publish-outside-the-lock shape as [record] — tryEmit never
        // blocks, but the lock should not span the fan-out.
        updated?.let(_eventsFlow::tryEmit)
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

    /** Drops window-aged events; caller holds [lock]. Only rewrites when something actually aged out. */
    private fun pruneRetentionLocked() {
        val kept = RetentionPolicy.pruneEntries(events, { it.timestampMs }, nowMs(), retentionDays())
        if (kept != null) events = kept
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

        /** Live-stream buffer: a stalled SSE reader drops old events, never blocks a writer. */
        const val SSE_BUFFER_CAPACITY = 16

        @Volatile
        private var instance: DetectionEventStore? = null

        fun get(context: Context, retentionDays: () -> Int = { StreamDefaults.RETENTION_DAYS_DISABLED }): DetectionEventStore =
            instance ?: synchronized(this) {
                instance ?: DetectionEventStore(
                    File(context.applicationContext.filesDir, FILE_NAME),
                    retentionDays,
                ).also { instance = it }
            }
    }
}

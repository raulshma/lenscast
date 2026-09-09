package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.DetectionEvent
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.model.DetectionEventDto
import com.raulshma.lenscast.streaming.model.DetectionEventsResponseDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import kotlinx.coroutines.flow.SharedFlow

/**
 * /api/detection/events — the detection event feed: GET lists the persisted
 * events newest first (bounded by the `limit` query param), DELETE clears the
 * log. Reads and writes go through the shared [DetectionEventStore]; the
 * store's own policy owns the cap and the limit clamp. The per-event JSON
 * serializer ([eventJson]) is the one the SSE stream reuses, so both the
 * polling GET and the live stream carry the exact same event object shape.
 */
class DetectionEventsWebHandler(private val eventStore: DetectionEventStore) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(DetectionEventsResponseDto::class.java) }
    private val eventAdapter by lazy { AppJson.moshi.adapter(DetectionEventDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    fun list(limit: Int?): String {
        val events = eventStore.events(limit)
        return responseAdapter.toJson(
            DetectionEventsResponseDto(
                events = events.map(::toDto),
                total = eventStore.count(),
            ),
        )
    }

    fun clear(): String {
        eventStore.clear()
        return successAdapter.toJson(SuccessResponse())
    }

    /** The one DetectionEvent → wire-JSON mapping, shared by the poll and the SSE stream. */
    fun eventJson(event: DetectionEvent): String = eventAdapter.toJson(toDto(event))

    /** The SSE connect-time backlog: the latest [limit] events, chronological (oldest first). */
    fun replayBacklog(limit: Int): List<DetectionEvent> = eventStore.events(limit).reversed()

    /** The store's live event stream (record + clip-link updates), the SSE tail. */
    fun eventFlow(): SharedFlow<DetectionEvent> = eventStore.eventsFlow

    private fun toDto(event: DetectionEvent) = DetectionEventDto(
        id = event.id,
        type = event.type,
        source = event.source,
        timestampMs = event.timestampMs,
        snapshotJpegBase64 = event.snapshotJpegBase64,
        dispatchedActions = event.dispatchedActions,
        zones = event.zones,
        labels = event.labels,
        clipMediaId = event.clipMediaId,
        clipFileName = event.clipFileName,
    )
}

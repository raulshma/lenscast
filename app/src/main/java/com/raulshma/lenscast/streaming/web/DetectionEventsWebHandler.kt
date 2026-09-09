package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.DetectionEvent
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.model.DetectionEventDto
import com.raulshma.lenscast.streaming.model.DetectionEventsResponseDto
import com.raulshma.lenscast.streaming.model.SuccessResponse

/**
 * /api/detection/events — the detection event feed: GET lists the persisted
 * events newest first (bounded by the `limit` query param), DELETE clears the
 * log. Reads and writes go through the shared [DetectionEventStore]; the
 * store's own policy owns the cap and the limit clamp.
 */
class DetectionEventsWebHandler(private val eventStore: DetectionEventStore) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(DetectionEventsResponseDto::class.java) }
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

    private fun toDto(event: DetectionEvent) = DetectionEventDto(
        id = event.id,
        type = event.type,
        source = event.source,
        timestampMs = event.timestampMs,
        snapshotJpegBase64 = event.snapshotJpegBase64,
        dispatchedActions = event.dispatchedActions,
    )
}

package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.DetectionCoordinator
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.model.DetectionTestResponseDto

/**
 * POST /api/detection/test — the dashboard's "send test alert" route: one
 * synthetic [com.raulshma.lenscast.core.EventKind.TEST] alert through the
 * real alert sinks (webhook, MQTT, local notification), answering with the
 * sinks that actually dispatched. Never recorded to the event log and never
 * armed against the schedule, the ML gate, or the deterrence automation —
 * that all stays the real events' business.
 */
class DetectionTestWebHandler(
    /** Lazy so the stack can be built before the coordinator is composed. */
    private val coordinator: () -> DetectionCoordinator?,
) {

    private val adapter by lazy { AppJson.moshi.adapter(DetectionTestResponseDto::class.java) }

    suspend fun test(): String {
        val dispatched = coordinator()?.dispatchTestAlert()
            ?: return adapter.toJson(DetectionTestResponseDto(success = false))
        return adapter.toJson(DetectionTestResponseDto(dispatchedActions = dispatched))
    }
}

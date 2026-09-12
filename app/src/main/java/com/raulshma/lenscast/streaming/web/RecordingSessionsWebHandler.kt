package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.capture.model.RecordingSessionIndex
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.streaming.model.RecordingSessionDto
import com.raulshma.lenscast.streaming.model.RecordingSessionsResponseDto

/**
 * GET /api/recordings/sessions?day=YYYY-MM-DD — the NVR day timeline behind
 * the web dashboard's recording timeline. Sessions are the day's video
 * captures (day boundary in device-local time), each with an inferred end and
 * an attributed trigger: the capture's persisted provenance stamp when it
 * carries a known wire name, else reconstruction from the detection events.
 * The grouping and attribution decisions live in the pure
 * [RecordingSessionIndex], this handler only joins the two stores and
 * serializes. A missing, malformed, or impossible `day` answers today — the
 * timeline degrades, it never errors.
 */
class RecordingSessionsWebHandler(
    private val captureHistoryStore: CaptureHistoryStore,
    private val eventStore: DetectionEventStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val adapter by lazy { AppJson.moshi.adapter(RecordingSessionsResponseDto::class.java) }

    fun sessions(day: String?): String {
        val window = RecordingSessionIndex.dayWindow(day, nowMs())
        val sessions = RecordingSessionIndex.buildSessions(
            captures = captureHistoryStore.history.value,
            events = eventStore.events(),
            window = window,
        )
        return adapter.toJson(
            RecordingSessionsResponseDto(
                sessions = sessions.map { session ->
                    RecordingSessionDto(
                        id = session.id,
                        startMs = session.startMs,
                        endMs = session.endMs,
                        trigger = session.trigger,
                        mediaId = session.mediaId,
                    )
                },
            ),
        )
    }
}

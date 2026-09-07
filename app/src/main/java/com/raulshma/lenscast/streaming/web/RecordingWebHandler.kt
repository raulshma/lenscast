package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.capture.RecordingClock
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.streaming.model.RecordingStatusDto
import com.raulshma.lenscast.streaming.model.SuccessResponse

/**
 * /api/recording/... — observes the Recording Controller's state; the service's
 * truth (recording / scheduled / idle) is all this handler reports.
 */
class RecordingWebHandler(private val recordingController: RecordingController) {

    private val statusAdapter by lazy { AppJson.moshi.adapter(RecordingStatusDto::class.java) }
    private val configAdapter by lazy { AppJson.moshi.adapter(RecordingConfig::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    fun status(): String {
        val state = recordingController.state.value
        val dto = when (state) {
            is RecordingState.Recording -> RecordingStatusDto(
                isRecording = true,
                elapsedSeconds = (RecordingClock.elapsedMsSince(state.startedAtMs) / 1000).toInt(),
                isScheduled = false,
                scheduledStartTimeMs = null,
            )
            is RecordingState.Scheduled -> RecordingStatusDto(
                isRecording = false,
                elapsedSeconds = 0,
                isScheduled = true,
                scheduledStartTimeMs = state.startAtMs,
            )
            RecordingState.Idle -> RecordingStatusDto(
                isRecording = false,
                elapsedSeconds = 0,
                isScheduled = false,
                scheduledStartTimeMs = null,
            )
        }
        return statusAdapter.toJson(dto)
    }

    suspend fun start(body: String): String {
        val config = configAdapter.fromJson(if (body.isNotEmpty()) body else "{}")
            ?: RecordingConfig()
        recordingController.start(config)
        return successAdapter.toJson(SuccessResponse())
    }

    suspend fun stop(): String {
        recordingController.stop()
        return successAdapter.toJson(SuccessResponse())
    }
}

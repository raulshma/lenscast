package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.model.StreamActionResponse

/** /api/stream/... — live-stream lifecycle, delegating the start ladder and session choreography to the Stream Toggle. */
class StreamWebHandler(
    private val streamingManager: StreamingManager,
    private val streamingSession: StreamingSession,
) {

    private val actionAdapter by lazy { AppJson.moshi.adapter(StreamActionResponse::class.java) }

    // The gate → start → session begin → rollback ladder is the Stream
    // Toggle's; the handler only maps outcomes onto the wire payloads.
    private val streamToggle = StreamToggle(
        transports = object : StreamToggle.Transports {
            override val webEnabled: Boolean get() = streamingManager.isWebEnabled.value
            override val rtspEnabled: Boolean get() = streamingManager.isRtspEnabled.value
            override val webActive: Boolean get() = streamingManager.isWebStreamingActive.value
            override val rtspActive: Boolean get() = streamingManager.isRtspRunning.value
            override fun startWeb(): Boolean = streamingManager.startWebStreaming()
            override fun stopWeb() = streamingManager.stopWebStreaming()
            override fun startRtsp(): Boolean = streamingManager.startRtspStreaming()
            override fun stopRtsp() = streamingManager.stopRtspStreaming()
            override fun stopServer() = streamingManager.stopStreaming()
            override suspend fun beginSession() = streamingSession.begin()
            override suspend fun endSession() = streamingSession.end()
        },
    )

    suspend fun startAll(): String {
        val success = streamingManager.startStreaming()
        if (!success) {
            return actionAdapter.toJson(
                StreamActionResponse(success = false, error = "Failed to start streaming server")
            )
        }
        try {
            // Never answer "failed" while the stream is still live.
            streamingSession.begin()
        } catch (e: Exception) {
            streamingManager.stopStreaming()
            throw e
        }
        return actionAdapter.toJson(
            StreamActionResponse(success = true, isActive = true, url = streamingManager.streamUrl.value)
        )
    }

    suspend fun startWeb(): String =
        startOutput(StreamKind.WEB, url = { streamingManager.streamUrl.value })

    suspend fun startRtsp(): String =
        startOutput(StreamKind.RTSP, url = { streamingManager.rtspUrl.value })

    suspend fun stopWeb(): String = stopOutput { streamingManager.stopWebStreaming() }

    suspend fun stopRtsp(): String = stopOutput { streamingManager.stopRtspStreaming() }

    suspend fun stopAll(): String {
        streamingManager.pauseStreaming()
        streamingSession.end()
        return actionAdapter.toJson(StreamActionResponse(success = true, isActive = false))
    }

    /** Maps the Stream Toggle's start outcome onto the existing DTO/error payloads. */
    private suspend fun startOutput(kind: StreamKind, url: () -> String): String =
        when (val outcome = streamToggle.start(kind)) {
            is StreamStartOutcome.Started -> actionAdapter.toJson(
                StreamActionResponse(
                    success = true,
                    isActive = streamingManager.isLiveStreaming(),
                    url = url(),
                )
            )
            // Unreachable from start(); the toggle only returns Stopped from its stop paths.
            is StreamStartOutcome.Stopped -> actionAdapter.toJson(
                StreamActionResponse(success = true, isActive = streamingManager.isLiveStreaming(), url = url())
            )
            is StreamStartOutcome.Disabled -> actionAdapter.toJson(
                StreamActionResponse(success = false, error = "${kind.displayName} streaming is disabled")
            )
            is StreamStartOutcome.StartFailed -> actionAdapter.toJson(
                StreamActionResponse(success = false, error = "Failed to start ${kind.slug} streaming")
            )
            // Never answer "failed" while the stream is still live — the
            // toggle already rolled it back; rethrow so the transport reports.
            is StreamStartOutcome.BeginFailedRolledBack -> throw outcome.cause
        }

    private suspend fun stopOutput(stop: () -> Unit): String {
        stop()
        streamingSession.end()
        return actionAdapter.toJson(
            StreamActionResponse(success = true, isActive = streamingManager.isLiveStreaming())
        )
    }
}

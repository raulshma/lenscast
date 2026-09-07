package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.StreamingTransports
import com.raulshma.lenscast.streaming.model.StreamActionResponse

/** /api/stream/... — live-stream lifecycle, delegating the start ladder and session choreography to the Stream Toggle. */
class StreamWebHandler(
    private val streamingManager: StreamingManager,
    private val streamingSession: StreamingSession,
) {

    private val actionAdapter by lazy { AppJson.moshi.adapter(StreamActionResponse::class.java) }

    // The gate → start → session begin → rollback ladder is the Stream
    // Toggle's; the handler only maps outcomes onto the wire payloads. The
    // transports are the one shared adapter over the manager and the session
    // — the same one the camera screen's ViewModel toggles through.
    private val streamToggle = StreamToggle(
        transports = StreamingTransports(streamingManager, streamingSession),
    )

    /**
     * Starts both outputs through the Stream Toggle's ladder — web first
     * (which brings the server up when needed), then RTSP; a failed RTSP
     * start after the web started rolls the web output back per-output, not
     * the whole server. The aggregate verdict keeps the historical payload:
     * any Started output answers success with the web URL; nothing started
     * answers the "Failed to start streaming server" error.
     */
    suspend fun startAll(): String {
        val (web, rtsp) = streamToggle.startBoth()
        // Never answer "failed" while a stream is live — the toggle already
        // rolled the output back; rethrow so the transport reports, exactly
        // like the per-output start paths.
        (web as? StreamStartOutcome.BeginFailedRolledBack)?.let { throw it.cause }
        (rtsp as? StreamStartOutcome.BeginFailedRolledBack)?.let { throw it.cause }
        val anyStarted = web is StreamStartOutcome.Started || rtsp is StreamStartOutcome.Started
        return if (anyStarted) {
            actionAdapter.toJson(
                StreamActionResponse(
                    success = true,
                    isActive = streamingManager.isLiveStreaming(),
                    url = streamingManager.streamUrl.value,
                )
            )
        } else {
            actionAdapter.toJson(
                StreamActionResponse(success = false, error = "Failed to start streaming server")
            )
        }
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

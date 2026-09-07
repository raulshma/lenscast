package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.model.StreamActionResponse

/** /api/stream/... — live-stream lifecycle, delegating session choreography to the Streaming Session. */
class StreamWebHandler(
    private val streamingManager: StreamingManager,
    private val streamingSession: StreamingSession,
) {

    private val actionAdapter by lazy { WebJson.moshi.adapter(StreamActionResponse::class.java) }

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
        startOutput(
            enabledCheck = { streamingManager.isWebEnabled.value to "Web streaming is disabled" },
            start = { streamingManager.startWebStreaming() to "Failed to start web streaming" },
            rollback = { streamingManager.stopWebStreaming() },
            url = { streamingManager.streamUrl.value },
        )

    suspend fun startRtsp(): String =
        startOutput(
            enabledCheck = { streamingManager.isRtspEnabled.value to "RTSP streaming is disabled" },
            start = { streamingManager.startRtspStreaming() to "Failed to start RTSP streaming" },
            rollback = { streamingManager.stopRtspStreaming() },
            url = { streamingManager.rtspUrl.value },
        )

    suspend fun stopWeb(): String = stopOutput { streamingManager.stopWebStreaming() }

    suspend fun stopRtsp(): String = stopOutput { streamingManager.stopRtspStreaming() }

    suspend fun stopAll(): String {
        streamingManager.pauseStreaming()
        streamingSession.end()
        return actionAdapter.toJson(StreamActionResponse(success = true, isActive = false))
    }

    /** Shared start shape: gate → start → session begin → rollback on failure. */
    private suspend fun startOutput(
        enabledCheck: () -> Pair<Boolean, String>,
        start: () -> Pair<Boolean, String>,
        rollback: () -> Unit,
        url: () -> String,
    ): String {
        val (enabled, disabledError) = enabledCheck()
        if (!enabled) {
            return actionAdapter.toJson(StreamActionResponse(success = false, error = disabledError))
        }
        val (started, startError) = start()
        if (!started) {
            return actionAdapter.toJson(StreamActionResponse(success = false, error = startError))
        }
        try {
            streamingSession.begin()
        } catch (e: Exception) {
            rollback()
            throw e
        }
        return actionAdapter.toJson(
            StreamActionResponse(
                success = true,
                isActive = streamingManager.isLiveStreaming(),
                url = url(),
            )
        )
    }

    private suspend fun stopOutput(stop: () -> Unit): String {
        stop()
        streamingSession.end()
        return actionAdapter.toJson(
            StreamActionResponse(success = true, isActive = streamingManager.isLiveStreaming())
        )
    }
}

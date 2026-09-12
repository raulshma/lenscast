package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.StreamingTransports
import com.raulshma.lenscast.streaming.model.RtspClientDto
import com.raulshma.lenscast.streaming.model.StreamActionResponse
import com.raulshma.lenscast.streaming.model.StreamClientsResponseDto

/** /api/stream/... — live-stream lifecycle, delegating the start ladder and session choreography to the Stream Toggle. */
class StreamWebHandler(
    private val streamingManager: StreamingManager,
    private val streamingSession: StreamingSession,
) {

    private val actionAdapter by lazy { AppJson.moshi.adapter(StreamActionResponse::class.java) }
    private val clientsAdapter by lazy { AppJson.moshi.adapter(StreamClientsResponseDto::class.java) }

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

    /**
     * Starts the RTMP push output through the shared push ladder
     * ([startPushOutput]) — false from [StreamingManager.startRtmpStreaming]
     * means the synchronous validation ladder refused (disabled, unusable
     * URL, or the H.265 codec); the readable reason is already on the RTMP
     * status, and the response carries the generic failure.
     */
    suspend fun startRtmp(): String = startPushOutput(
        name = "RTMP",
        enabled = { streamingManager.isRtmpEnabled.value },
        start = { streamingManager.startRtmpStreaming() },
        stop = { streamingManager.stopRtmpStreaming() },
    )

    suspend fun stopRtmp(): String = stopOutput { streamingManager.stopRtmpStreaming() }

    /**
     * Starts the WHIP push output through the shared push ladder
     * ([startPushOutput]) — false from [StreamingManager.startWhipStreaming]
     * means the synchronous validation ladder refused (disabled or an
     * unusable URL — no codec gate, libwebrtc encodes its own H.264); the
     * readable reason is already on the WHIP status, and the response
     * carries the generic failure.
     */
    suspend fun startWhip(): String = startPushOutput(
        name = "WHIP",
        enabled = { streamingManager.isWhipEnabled.value },
        start = { streamingManager.startWhipStreaming() },
        stop = { streamingManager.stopWhipStreaming() },
    )

    suspend fun stopWhip(): String = stopOutput { streamingManager.stopWhipStreaming() }

    /**
     * Starts the SRT push output through the shared push ladder
     * ([startPushOutput]) — false from [StreamingManager.startSrtStreaming]
     * means the synchronous validation ladder refused (disabled, an unusable
     * URL, or the H.265 codec); the readable reason is already on the SRT
     * status, and the response carries the generic failure.
     */
    suspend fun startSrt(): String = startPushOutput(
        name = "SRT",
        enabled = { streamingManager.isSrtEnabled.value },
        start = { streamingManager.startSrtStreaming() },
        stop = { streamingManager.stopSrtStreaming() },
    )

    suspend fun stopSrt(): String = stopOutput { streamingManager.stopSrtStreaming() }

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

    /**
     * The one push-output start ladder the RTMP and WHIP routes share: the
     * enabled gate, the synchronous refusal verdict (the connect itself is
     * asynchronous — a false [start] means the output's validation ladder
     * refused, with the readable reason already on its status), the session
     * attach, and the per-output rollback when the attach throws. The
     * response carries no URL for either push: the RTMP target embeds the
     * stream key and the WHIP endpoint is the device's own publish
     * resource — neither round-trips over the Web API.
     */
    private suspend fun startPushOutput(
        name: String,
        enabled: () -> Boolean,
        start: () -> Boolean,
        stop: () -> Unit,
    ): String {
        if (!enabled()) {
            return actionAdapter.toJson(
                StreamActionResponse(success = false, error = "$name push is disabled"),
            )
        }
        if (!start()) {
            return actionAdapter.toJson(
                StreamActionResponse(
                    success = false,
                    error = "Failed to start $name push — see the $name status for the reason",
                ),
            )
        }
        return try {
            streamingSession.begin()
            actionAdapter.toJson(
                StreamActionResponse(success = true, isActive = streamingManager.isLiveStreaming()),
            )
        } catch (e: Exception) {
            // Roll the just-started push back — never a live push without its session.
            stop()
            throw e
        }
    }

    private suspend fun stopOutput(stop: () -> Unit): String {
        stop()
        streamingSession.end()
        return actionAdapter.toJson(
            StreamActionResponse(success = true, isActive = streamingManager.isLiveStreaming())
        )
    }

    fun listClients(): String {
        val info = streamingManager.getConnectInfo()
        return clientsAdapter.toJson(
            StreamClientsResponseDto(
                httpClients = streamingManager.getHttpClientIds(),
                httpCount = info.httpClients,
                rtspCount = info.rtspClients,
                maxHttp = StreamDefaults.MAX_HTTP_CLIENTS,
                rtspClients = streamingManager.getRtspClients().map { client ->
                    RtspClientDto(
                        id = client.id,
                        remoteAddress = client.remoteAddress,
                        connectedAtMs = client.connectedAtMs,
                        transport = client.transport,
                        media = client.media,
                        playing = client.playing,
                        framesSent = client.framesSent,
                    )
                },
            )
        )
    }

    /**
     * True kick across both transports: the MJPEG pump first (its ids are
     * `mjpeg_*`), then the RTSP session registry — one route, one
     * session/token policy (the route's own), either backend.
     */
    fun kickClient(id: String): String {
        val ok = streamingManager.kickHttpClient(id) || streamingManager.kickRtspClient(id)
        if (!ok) throw IllegalArgumentException("Client not found: $id")
        return actionAdapter.toJson(StreamActionResponse(success = true, isActive = streamingManager.isLiveStreaming()))
    }
}

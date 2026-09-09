package com.raulshma.lenscast.streaming.web

import android.util.Log

/**
 * The Web API seam: one suspend dispatch from the transport (StreamingServer)
 * to the per-domain handlers. Handlers suspend; the server's worker thread
 * awaits — no handler ever blocks a thread pool on Main-dispatched work.
 */
class ApiRouter(
    private val settings: SettingsWebHandler,
    private val status: StatusWebHandler,
    private val stream: StreamWebHandler,
    private val capture: CaptureWebHandler,
    private val lens: LensWebHandler,
    private val interval: IntervalCaptureWebHandler,
    private val recording: RecordingWebHandler,
    private val gallery: GalleryWebHandler,
    private val deterrence: DeterrenceWebHandler,
    private val detectionEvents: DetectionEventsWebHandler,
    private val auth: AuthWebHandler,
) {

    suspend fun dispatch(request: ApiRequest): ApiResponse = try {
        route(request) ?: ApiResponse.notFound()
    } catch (e: Exception) {
        Log.e(TAG, "API request failed: ${request.method} ${request.path}", e)
        ApiResponse.ok(ApiResponse.error(e))
    }

    private suspend fun route(r: ApiRequest): ApiResponse? = when (r.method) {
        ApiMethod.GET -> routeGet(r)
        ApiMethod.PUT, ApiMethod.POST -> routeWrite(r)
        ApiMethod.DELETE -> routeDelete(r)
    }

    private suspend fun routeGet(r: ApiRequest): ApiResponse? = when (r.path) {
        "/api/settings" -> ApiResponse.ok(settings.get())
        "/api/status" -> ApiResponse.ok(status.get())
        "/api/camera/lenses" -> ApiResponse.ok(lens.getLenses())
        "/api/stream/clients" -> ApiResponse.ok(stream.listClients())
        "/api/capture/interval/status" -> ApiResponse.ok(interval.status())
        "/api/recording/status" -> ApiResponse.ok(recording.status())
        "/api/gallery" -> ApiResponse.ok(
            gallery.getGallery(
                type = r.query["type"],
                page = r.query["page"]?.toIntOrNull() ?: 0,
                pageSize = r.query["pageSize"]?.toIntOrNull() ?: 0,
            )
        )
        "/api/detection/events" -> ApiResponse.ok(detectionEvents.list(r.query["limit"]?.toIntOrNull()))
        "/api/auth/config" -> ApiResponse.ok(auth.get())
        "/api/auth/sessions" -> ApiResponse.ok(auth.listSessions())
        else -> null
    }

    private suspend fun routeWrite(r: ApiRequest): ApiResponse? = when (r.path) {
        "/api/settings" -> ApiResponse.ok(settings.put(r.body))
        "/api/settings/ml-model/download" -> ApiResponse.ok(settings.downloadModel())
        "/api/stream/start", "/api/stream/resume" -> ApiResponse.ok(stream.startAll())
        "/api/stream/stop" -> ApiResponse.ok(stream.stopAll())
        "/api/stream/web/start" -> ApiResponse.ok(stream.startWeb())
        "/api/stream/web/stop" -> ApiResponse.ok(stream.stopWeb())
        "/api/stream/rtsp/start" -> ApiResponse.ok(stream.startRtsp())
        "/api/stream/rtsp/stop" -> ApiResponse.ok(stream.stopRtsp())
        "/api/capture" -> ApiResponse.ok(capture.capturePhoto())
        "/api/camera/lens" -> ApiResponse.ok(lens.selectLens(r.body))
        "/api/camera/focus" -> ApiResponse.ok(lens.tapFocus(r.body))
        "/api/camera/zoom" -> ApiResponse.ok(lens.setZoom(r.body))
        "/api/camera/torch" -> ApiResponse.ok(lens.setTorch(r.body))
        "/api/capture/interval/start" -> ApiResponse.ok(interval.start(r.body))
        "/api/capture/interval/stop" -> ApiResponse.ok(interval.stop())
        "/api/recording/start" -> ApiResponse.ok(recording.start(r.body))
        "/api/recording/stop" -> ApiResponse.ok(recording.stop())
        "/api/deterrence/siren" -> ApiResponse.ok(deterrence.setSiren(r.body))
        "/api/auth/config" -> ApiResponse.ok(auth.put(r.body))
        "/api/media/batch-delete" -> ApiResponse.ok(gallery.batchDelete(r.body))
        else -> null
    }

    private suspend fun routeDelete(r: ApiRequest): ApiResponse? = when {
        r.path == "/api/detection/events" ->
            ApiResponse.ok(detectionEvents.clear())
        r.path.startsWith("/api/stream/clients/") ->
            ApiResponse.ok(stream.kickClient(r.path.removePrefix("/api/stream/clients/")))
        r.path.startsWith("/api/auth/sessions/") ->
            ApiResponse.ok(auth.revokeSession(r.path.removePrefix("/api/auth/sessions/")))
        r.path.startsWith("/api/media/") ->
            ApiResponse.ok(gallery.deleteMedia(r.path.removePrefix("/api/media/")))
        else -> null
    }

    companion object {
        private const val TAG = "ApiRouter"
    }
}

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
    private val audit: AuditWebHandler,
    private val detectionTest: DetectionTestWebHandler,
    /** The read-only /api/system diagnostics snapshot. */
    private val system: SystemWebHandler,
    /** The audit trail for mutating dispatches. */
    private val auditLog: AuditLog,
) {

    suspend fun dispatch(request: ApiRequest): ApiResponse {
        var failure: String? = null
        val response = try {
            route(request) ?: run {
                failure = "not found"
                ApiResponse.notFound()
            }
        } catch (e: Exception) {
            failure = e.message?.take(200) ?: "internal error"
            Log.e(TAG, "API request failed: ${request.method} ${request.path}", e)
            ApiResponse.ok(ApiResponse.error(e))
        }
        auditDispatch(request, failure)
        return response
    }

    private suspend fun route(r: ApiRequest): ApiResponse? = when (r.method) {
        ApiMethod.GET -> routeGet(r)
        ApiMethod.PUT, ApiMethod.POST -> routeWrite(r)
        ApiMethod.DELETE -> routeDelete(r)
    }

    private suspend fun routeGet(r: ApiRequest): ApiResponse? = when (r.path) {
        "/api/settings" -> ApiResponse.ok(settings.get())
        "/api/settings/export" -> ApiResponse.ok(settings.export())
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
        "/api/detection/events" -> ApiResponse.ok(
            detectionEvents.list(r.query["limit"]?.toIntOrNull(), r.query["type"])
        )
        "/api/detection/events/export" -> detectionEvents.export(r.query["format"], r.query["type"])
        "/api/detection/stats" -> ApiResponse.ok(detectionEvents.stats())
        "/api/system" -> ApiResponse.ok(system.get())
        "/api/audit" -> ApiResponse.ok(audit.list(r.query["limit"]?.toIntOrNull()))
        "/api/auth/config" -> ApiResponse.ok(auth.get())
        "/api/auth/sessions" -> ApiResponse.ok(auth.listSessions())
        else -> null
    }

    private suspend fun routeWrite(r: ApiRequest): ApiResponse? = when (r.path) {
        "/api/settings" -> ApiResponse.ok(settings.put(r.body))
        "/api/settings/import" -> ApiResponse.ok(settings.import(r.body))
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
        "/api/detection/test" -> ApiResponse.ok(detectionTest.test())
        "/api/auth/config" -> ApiResponse.ok(auth.put(r.body))
        "/api/media/batch-delete" -> ApiResponse.ok(gallery.batchDelete(r.body))
        else -> null
    }

    private suspend fun routeDelete(r: ApiRequest): ApiResponse? = when {
        r.path == "/api/detection/events" ->
            ApiResponse.ok(detectionEvents.clear())
        r.path == "/api/audit" -> ApiResponse.ok(audit.clear())
        r.path.startsWith("/api/stream/clients/") ->
            ApiResponse.ok(stream.kickClient(r.path.removePrefix("/api/stream/clients/")))
        r.path.startsWith("/api/auth/sessions/") ->
            ApiResponse.ok(auth.revokeSession(r.path.removePrefix("/api/auth/sessions/")))
        r.path.startsWith("/api/media/") ->
            ApiResponse.ok(gallery.deleteMedia(r.path.removePrefix("/api/media/")))
        else -> null
    }

    /**
     * The audit half of a mutating dispatch: every POST/PUT/DELETE this router
     * answers — dispatched or unknown-route, success or handler error — lands
     * in the trail as `"$method $path"`, the one place a config change from
     * any client (dashboard, API token, automation) is visible. Failures
     * carry the handler's message. Never let a broken audit write fail the
     * audited request.
     */
    private fun auditDispatch(request: ApiRequest, failure: String?) {
        if (request.method == ApiMethod.GET) return
        runCatching {
            auditLog.record(
                action = "${request.method.name} ${request.path}",
                detail = failure.orEmpty(),
                outcome = if (failure == null) AuditEntry.OUTCOME_OK else AuditEntry.OUTCOME_ERROR,
            )
        }
    }

    companion object {
        private const val TAG = "ApiRouter"
    }
}

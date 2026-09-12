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
    /** GET /api/recordings/sessions — the NVR day timeline. */
    private val recordingSessions: RecordingSessionsWebHandler,
    private val gallery: GalleryWebHandler,
    private val deterrence: DeterrenceWebHandler,
    private val detectionEvents: DetectionEventsWebHandler,
    private val auth: AuthWebHandler,
    private val audit: AuditWebHandler,
    private val detectionTest: DetectionTestWebHandler,
    /** The read-only /api/system diagnostics snapshot. */
    private val system: SystemWebHandler,
    /** The /api/push Web Push subscription routes (session-only). */
    private val push: PushWebHandler,
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
        "/api/recordings/sessions" -> ApiResponse.ok(recordingSessions.sessions(r.query["day"]))
        "/api/gallery" -> ApiResponse.ok(
            gallery.getGallery(
                type = r.query["type"],
                page = r.query["page"]?.toIntOrNull() ?: 0,
                pageSize = r.query["pageSize"]?.toIntOrNull() ?: 0,
                query = r.query["q"],
            )
        )
        "/api/detection/events" -> ApiResponse.ok(
            detectionEvents.list(r.query["limit"]?.toIntOrNull(), r.query["type"], r.query["day"])
        )
        "/api/detection/events/export" -> detectionEvents.export(r.query["format"], r.query["type"])
        "/api/detection/stats" -> ApiResponse.ok(detectionEvents.stats())
        "/api/system" -> ApiResponse.ok(system.get())
        "/api/audit" -> ApiResponse.ok(audit.list(r.query["limit"]?.toIntOrNull()))
        "/api/openapi.json" -> ApiResponse(200, "application/json", OpenApiSpec.json())
        // The config read is role-aware: viewer sessions get the redacted view
        // (the filter already 403s them; this is defense in depth at the DTO).
        "/api/auth/config" -> ApiResponse.ok(auth.get(r.sessionRole))
        "/api/auth/sessions" -> ApiResponse.ok(auth.listSessions())
        "/api/push/vapid-public" -> ApiResponse.ok(push.vapidPublicKey())
        "/api/push/subscriptions" -> ApiResponse.ok(push.list())
        else -> null
    }

    private suspend fun routeWrite(r: ApiRequest): ApiResponse? = when (r.path) {
        "/api/settings" -> ApiResponse.ok(settings.put(r.body))
        "/api/settings/import" -> ApiResponse.ok(settings.import(r.body))
        "/api/settings/ml-model/download" -> ApiResponse.ok(settings.downloadModel())
        "/api/settings/audio-model/download" -> ApiResponse.ok(settings.downloadAudioModel())
        "/api/stream/start", "/api/stream/resume" -> ApiResponse.ok(stream.startAll())
        "/api/stream/stop" -> ApiResponse.ok(stream.stopAll())
        "/api/stream/web/start" -> ApiResponse.ok(stream.startWeb())
        "/api/stream/web/stop" -> ApiResponse.ok(stream.stopWeb())
        "/api/stream/rtsp/start" -> ApiResponse.ok(stream.startRtsp())
        "/api/stream/rtsp/stop" -> ApiResponse.ok(stream.stopRtsp())
        "/api/stream/rtmp/start" -> ApiResponse.ok(stream.startRtmp())
        "/api/stream/rtmp/stop" -> ApiResponse.ok(stream.stopRtmp())
        "/api/stream/whip/start" -> ApiResponse.ok(stream.startWhip())
        "/api/stream/whip/stop" -> ApiResponse.ok(stream.stopWhip())
        "/api/stream/srt/start" -> ApiResponse.ok(stream.startSrt())
        "/api/stream/srt/stop" -> ApiResponse.ok(stream.stopSrt())
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
        "/api/push/subscriptions" -> ApiResponse.ok(push.subscribe(r.body))
        else -> null
    }

    private suspend fun routeDelete(r: ApiRequest): ApiResponse? = when {
        r.path == "/api/detection/events" ->
            ApiResponse.ok(detectionEvents.clear())
        r.path == "/api/audit" -> ApiResponse.ok(audit.clear())
        r.path == "/api/push/subscriptions" ->
            ApiResponse.ok(push.unsubscribe(r.query["endpoint"]))
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

        /**
         * One registered route as data: [method] and the literal [path] the
         * dispatch `when` answers, plus the [specPath] template the OpenAPI
         * document names it by (they differ only for the id-suffix DELETE
         * routes, which the router matches by prefix and the spec by
         * `{param}` template).
         */
        data class Route(val method: ApiMethod, val path: String, val specPath: String = path)

        /**
         * The routing table as data, next to the dispatch `when`s that
         * implement it. This is the drift guard's hub: [OpenApiSpec] builds
         * its paths from it, and the parity test cross-checks it against the
         * router source, the live dispatch (a stub-router probe — a declared
         * route that 404s fails the test), and the spec document, in both
         * directions. A new route is one `when` branch plus one entry here;
         * forgetting either side fails a JVM test.
         */
        val ROUTE_TABLE: List<Route> = listOf(
            // GET reads
            Route(ApiMethod.GET, "/api/settings"),
            Route(ApiMethod.GET, "/api/settings/export"),
            Route(ApiMethod.GET, "/api/status"),
            Route(ApiMethod.GET, "/api/camera/lenses"),
            Route(ApiMethod.GET, "/api/stream/clients"),
            Route(ApiMethod.GET, "/api/capture/interval/status"),
            Route(ApiMethod.GET, "/api/recording/status"),
            Route(ApiMethod.GET, "/api/recordings/sessions"),
            Route(ApiMethod.GET, "/api/gallery"),
            Route(ApiMethod.GET, "/api/detection/events"),
            Route(ApiMethod.GET, "/api/detection/events/export"),
            Route(ApiMethod.GET, "/api/detection/stats"),
            Route(ApiMethod.GET, "/api/system"),
            Route(ApiMethod.GET, "/api/audit"),
            Route(ApiMethod.GET, "/api/openapi.json"),
            Route(ApiMethod.GET, "/api/auth/config"),
            Route(ApiMethod.GET, "/api/auth/sessions"),
            Route(ApiMethod.GET, "/api/push/vapid-public"),
            Route(ApiMethod.GET, "/api/push/subscriptions"),
            // Writes (PUT shares the POST dispatch; both verbs are accepted)
            Route(ApiMethod.PUT, "/api/settings"),
            Route(ApiMethod.POST, "/api/settings"),
            Route(ApiMethod.POST, "/api/settings/import"),
            Route(ApiMethod.POST, "/api/settings/ml-model/download"),
            Route(ApiMethod.POST, "/api/settings/audio-model/download"),
            Route(ApiMethod.POST, "/api/stream/start"),
            Route(ApiMethod.POST, "/api/stream/resume"),
            Route(ApiMethod.POST, "/api/stream/stop"),
            Route(ApiMethod.POST, "/api/stream/web/start"),
            Route(ApiMethod.POST, "/api/stream/web/stop"),
            Route(ApiMethod.POST, "/api/stream/rtsp/start"),
            Route(ApiMethod.POST, "/api/stream/rtsp/stop"),
            Route(ApiMethod.POST, "/api/stream/rtmp/start"),
            Route(ApiMethod.POST, "/api/stream/rtmp/stop"),
            Route(ApiMethod.POST, "/api/stream/whip/start"),
            Route(ApiMethod.POST, "/api/stream/whip/stop"),
            Route(ApiMethod.POST, "/api/stream/srt/start"),
            Route(ApiMethod.POST, "/api/stream/srt/stop"),
            Route(ApiMethod.POST, "/api/capture"),
            Route(ApiMethod.POST, "/api/camera/lens"),
            Route(ApiMethod.POST, "/api/camera/focus"),
            Route(ApiMethod.POST, "/api/camera/zoom"),
            Route(ApiMethod.POST, "/api/camera/torch"),
            Route(ApiMethod.POST, "/api/capture/interval/start"),
            Route(ApiMethod.POST, "/api/capture/interval/stop"),
            Route(ApiMethod.POST, "/api/recording/start"),
            Route(ApiMethod.POST, "/api/recording/stop"),
            Route(ApiMethod.POST, "/api/deterrence/siren"),
            Route(ApiMethod.POST, "/api/detection/test"),
            Route(ApiMethod.PUT, "/api/auth/config"),
            Route(ApiMethod.POST, "/api/auth/config"),
            Route(ApiMethod.POST, "/api/media/batch-delete"),
            Route(ApiMethod.POST, "/api/push/subscriptions"),
            // Deletes
            Route(ApiMethod.DELETE, "/api/detection/events"),
            Route(ApiMethod.DELETE, "/api/audit"),
            Route(ApiMethod.DELETE, "/api/push/subscriptions"),
            Route(ApiMethod.DELETE, "/api/stream/clients/", "/api/stream/clients/{clientId}"),
            Route(ApiMethod.DELETE, "/api/auth/sessions/", "/api/auth/sessions/{sessionId}"),
            Route(ApiMethod.DELETE, "/api/media/", "/api/media/{mediaId}"),
        )
    }
}

package com.raulshma.lenscast.streaming.web

/**
 * The OpenAPI 3.1 document behind GET /api/openapi.json — hand-maintained
 * (no codegen dependency), kept honest from both sides by
 * [OpenApiSpecParityTest]:
 *
 *  - every [ApiRouter.ROUTE_TABLE] entry must appear in the document, and
 *    every document path must be a route the router registers;
 *  - the table itself is cross-checked against the router source and the
 *    live dispatch (a stub-router probe), so a route added without a table
 *    entry — or a table entry without a `when` branch — fails the test;
 *  - every schema pinned with `x-fixture` (the contract fixtures in
 *    web/contract/) must name real fields on both sides: the fixture's keys
 *    must appear in the schema's `properties`, and the schema's `required`
 *    fields must be present in the fixture.
 *
 * Schemas describe the wire surface at the level the contract fixtures pin
 * (field names + the required core), not every nullable nuance — the fixtures
 * remain the exact shape source; this document is the machine-readable map of
 * it.
 */
object OpenApiSpec {

    /** The document, built once. Values are only String/Boolean/Int/List/Map. */
    val document: Map<String, Any?> by lazy { build() }

    private val jsonAdapter by lazy {
        com.raulshma.lenscast.core.AppJson.moshi.adapter<Map<String, Any?>>(
            com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
    }

    /** The serialized document served by the router. */
    fun json(): String = jsonAdapter.toJson(document)

    /** Every spec path the document declares (the parity test's right side). */
    fun paths(): Map<String, Map<String, Any?>> =
        @Suppress("UNCHECKED_CAST")
        (document["paths"] as Map<String, Map<String, Any?>>)

    /** The `components/schemas` block (the fixture-sanity test's source). */
    fun schemas(): Map<String, Map<String, Any?>> =
        @Suppress("UNCHECKED_CAST")
        (document["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>

    // ── builder helpers ──

    private fun ref(name: String): Map<String, Any?> = mapOf("\$ref" to "#/components/schemas/$name")

    /** A 200 JSON response carrying a schema reference. */
    private fun ok(schema: String?): Map<String, Any?> = mapOf(
        "description" to "The handler answer: HTTP 200 with the outcome encoded in the payload " +
            "(`{\"success\":false,\"error\":\"…\"}` on handler failure — non-200 is reserved for routing)",
        "content" to (schema?.let { mapOf("application/json" to mapOf("schema" to ref(it))) } ?: mapOf<String, Any?>()),
    )

    private fun operation(
        tags: List<String>,
        summary: String,
        responses: Map<String, Any?>,
        parameters: List<Map<String, Any?>> = emptyList(),
        requestBody: Map<String, Any?>? = null,
        security: List<Map<String, Any?>>? = null,
    ): Map<String, Any?> = buildMap {
        put("tags", tags)
        put("summary", summary)
        put("security", security ?: listOf(mapOf("sessionCookie" to emptyList<String>()), mapOf("bearerToken" to emptyList<String>())))
        if (parameters.isNotEmpty()) put("parameters", parameters)
        requestBody?.let { put("requestBody", it) }
        put("responses", responses)
    }

    private fun queryParam(name: String, description: String, schema: Map<String, Any?>): Map<String, Any?> = mapOf(
        "name" to name,
        "in" to "query",
        "required" to false,
        "description" to description,
        "schema" to schema,
    )

    private fun pathParam(name: String, description: String): Map<String, Any?> = mapOf(
        "name" to name,
        "in" to "path",
        "required" to true,
        "description" to description,
        "schema" to mapOf("type" to "string"),
    )

    private fun jsonBody(schema: String, description: String? = null): Map<String, Any?> =
        mapOf(
            "required" to true,
            "description" to description,
            "content" to mapOf("application/json" to mapOf("schema" to ref(schema))),
        ).filterValues { it != null }

    /** A named object schema with properties, required core, and optional fixture pin. */
    private fun schema(
        vararg properties: Pair<String, Map<String, Any?>>,
        required: List<String> = emptyList(),
        fixture: String? = null,
        description: String? = null,
    ): Map<String, Any?> = buildMap {
        description?.let { put("description", it) }
        put("type", "object")
        put("properties", properties.toMap())
        if (required.isNotEmpty()) put("required", required)
        fixture?.let { put("x-fixture", it) }
    }

    private fun stringSchema(description: String? = null): Map<String, Any?> =
        mapOf("type" to "string").let { if (description != null) it + mapOf("description" to description) else it }

    private fun intSchema(description: String? = null): Map<String, Any?> =
        mapOf("type" to "integer").let { if (description != null) it + mapOf("description" to description) else it }

    private fun boolSchema(description: String? = null): Map<String, Any?> =
        mapOf("type" to "boolean").let { if (description != null) it + mapOf("description" to description) else it }

    private fun arrayOf(refOrType: Map<String, Any?>): Map<String, Any?> = mapOf("type" to "array", "items" to refOrType)

    // ── the document ──

    private fun build(): Map<String, Any?> {
        // path → method → operation, assembled from per-path blocks.
        val paths = LinkedHashMap<String, Map<String, Any?>>()

        fun path(specPath: String, vararg operations: Pair<String, Map<String, Any?>>) {
            paths[specPath] = operations.toMap()
        }

        // ── settings ──
        path(
            "/api/settings",
            "get" to operation(
                listOf("settings"),
                "Read the full settings document",
                mapOf("200" to ok("SettingsResponseDto")),
            ),
            "put" to operation(
                listOf("settings"),
                "Save settings (sections present persist wholesale)",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("SettingsUpdateRequestDto"),
            ),
            "post" to operation(
                listOf("settings"),
                "Save settings (alias of PUT)",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("SettingsUpdateRequestDto"),
            ),
        )
        path(
            "/api/settings/export",
            "get" to operation(
                listOf("settings"),
                "Download the versioned settings export envelope",
                mapOf("200" to ok("SettingsExportDto")),
            ),
        )
        path(
            "/api/settings/import",
            "post" to operation(
                listOf("settings"),
                "Import a settings export envelope (or a bare settings document)",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("SettingsExportDto"),
            ),
        )
        path(
            "/api/settings/ml-model/download",
            "post" to operation(
                listOf("settings"),
                "Request the on-demand object-detection model download (idempotent)",
                mapOf("200" to ok("SuccessResponse")),
            ),
        )
        path(
            "/api/settings/audio-model/download",
            "post" to operation(
                listOf("settings"),
                "Request the on-demand sound-classification (YAMNet) model download (idempotent)",
                mapOf("200" to ok("SuccessResponse")),
            ),
        )

        // ── status / system ──
        path(
            "/api/status",
            "get" to operation(
                listOf("status"),
                "The live device snapshot (streaming, thermal, battery, camera, watchdog, network)",
                mapOf("200" to ok("StatusResponseDto")),
            ),
        )
        path(
            "/api/system",
            "get" to operation(
                listOf("system"),
                "The read-only triage snapshot (build, device, uptimes, battery and storage facts)",
                mapOf("200" to ok("SystemInfoResponseDto")),
            ),
        )

        // ── camera ──
        path(
            "/api/camera/lenses",
            "get" to operation(
                listOf("camera"),
                "List the device's lenses and the selected one",
                mapOf("200" to ok("LensesResponseDto")),
            ),
        )
        path(
            "/api/camera/lens",
            "post" to operation(
                listOf("camera"),
                "Select a lens by index",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("LensSelectRequest"),
            ),
        )
        path(
            "/api/camera/focus",
            "post" to operation(
                listOf("camera"),
                "Tap to focus at normalized coordinates",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("TapFocusRequest"),
            ),
        )
        path(
            "/api/camera/zoom",
            "post" to operation(
                listOf("camera"),
                "Set the zoom ratio",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("ZoomRequest"),
            ),
        )
        path(
            "/api/camera/torch",
            "post" to operation(
                listOf("camera"),
                "Toggle the torch (omitted body toggles)",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("TorchRequest"),
            ),
        )

        // ── stream ──
        path(
            "/api/stream/clients",
            "get" to operation(
                listOf("stream"),
                "List connected HTTP and RTSP clients",
                mapOf("200" to ok("StreamClientsResponseDto")),
            ),
        )
        path(
            "/api/stream/clients/{clientId}",
            "delete" to operation(
                listOf("stream"),
                "Kick one connected client by id",
                mapOf("200" to ok("StreamActionResponse")),
                parameters = listOf(pathParam("clientId", "The client id (`mjpeg_*` or an RTSP session id)")),
            ),
        )
        for ((routePath, summary) in listOf(
            "/api/stream/start" to "Start the enabled outputs (web + RTSP)",
            "/api/stream/resume" to "Resume the enabled outputs (alias of /api/stream/start)",
            "/api/stream/stop" to "Stop the live outputs and the session",
            "/api/stream/web/start" to "Start the web (MJPEG/HLS) output",
            "/api/stream/web/stop" to "Stop the web output",
            "/api/stream/rtsp/start" to "Start the RTSP output",
            "/api/stream/rtsp/stop" to "Stop the RTSP output",
            "/api/stream/rtmp/start" to "Start the RTMP push output",
            "/api/stream/rtmp/stop" to "Stop the RTMP push output",
            "/api/stream/whip/start" to "Start the WHIP push output",
            "/api/stream/whip/stop" to "Stop the WHIP push output",
            "/api/stream/srt/start" to "Start the SRT push output",
            "/api/stream/srt/stop" to "Stop the SRT push output",
        )) {
            path(
                routePath,
                "post" to operation(
                    listOf("stream"),
                    summary,
                    mapOf("200" to ok("StreamActionResponse")),
                ),
            )
        }

        // ── capture / recording ──
        path(
            "/api/capture",
            "post" to operation(
                listOf("capture"),
                "Capture one photo to the gallery",
                mapOf("200" to ok("CaptureResponse")),
            ),
        )
        path(
            "/api/capture/interval/status",
            "get" to operation(
                listOf("capture"),
                "The interval-capture series' status",
                mapOf("200" to ok("IntervalCaptureStatusDto")),
            ),
        )
        path(
            "/api/capture/interval/start",
            "post" to operation(
                listOf("capture"),
                "Start an interval-capture series",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("IntervalCaptureConfig"),
            ),
        )
        path(
            "/api/capture/interval/stop",
            "post" to operation(
                listOf("capture"),
                "Stop the running interval-capture series",
                mapOf("200" to ok("SuccessResponse")),
            ),
        )
        path(
            "/api/recording/status",
            "get" to operation(
                listOf("recording"),
                "The recording state (live, elapsed, scheduled)",
                mapOf("200" to ok("RecordingStatusDto")),
            ),
        )
        path(
            "/api/recording/start",
            "post" to operation(
                listOf("recording"),
                "Start (or schedule) a recording",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("RecordingConfig"),
            ),
        )
        path(
            "/api/recording/stop",
            "post" to operation(
                listOf("recording"),
                "Stop the live recording",
                mapOf("200" to ok("SuccessResponse")),
            ),
        )
        path(
            "/api/recordings/sessions",
            "get" to operation(
                listOf("recording"),
                "The NVR day timeline: the requested local day's recordings as sessions",
                mapOf("200" to ok("RecordingSessionsResponseDto")),
                parameters = listOf(queryParam("day", "Local calendar day, `YYYY-MM-DD`; absent means today", mapOf("type" to "string", "pattern" to "^\\d{4}-\\d{2}-\\d{2}$"))),
            ),
        )

        // ── gallery / media ──
        path(
            "/api/gallery",
            "get" to operation(
                listOf("gallery"),
                "The paginated media gallery",
                mapOf("200" to ok("GalleryResponseDto")),
                parameters = listOf(
                    queryParam("type", "`PHOTO` or `VIDEO`", mapOf("type" to "string")),
                    queryParam("page", "Zero-based page", mapOf("type" to "integer")),
                    queryParam("pageSize", "Items per page", mapOf("type" to "integer")),
                    queryParam("q", "File-name substring filter", mapOf("type" to "string")),
                ),
            ),
        )
        path(
            "/api/media/{mediaId}",
            "delete" to operation(
                listOf("gallery"),
                "Delete one media item (and its store row)",
                mapOf("200" to ok("SuccessResponse")),
                parameters = listOf(pathParam("mediaId", "The media id (the gallery item id)")),
            ),
        )
        path(
            "/api/media/batch-delete",
            "post" to operation(
                listOf("gallery"),
                "Delete many media items",
                mapOf("200" to ok("BatchDeleteResponse")),
                requestBody = jsonBody("BatchDeleteRequest"),
            ),
        )

        // ── detection ──
        path(
            "/api/detection/events",
            "get" to operation(
                listOf("detection"),
                "The persisted detection-event feed (newest first)",
                mapOf("200" to ok("DetectionEventsResponseDto")),
                parameters = listOf(
                    queryParam("limit", "Page size (clamped 1..200, default 50)", mapOf("type" to "integer")),
                    queryParam("type", "`motion` | `sound` | `tamper`", mapOf("type" to "string")),
                    queryParam("day", "Local calendar day filter, `YYYY-MM-DD`", mapOf("type" to "string", "pattern" to "^\\d{4}-\\d{2}-\\d{2}$")),
                ),
            ),
            "delete" to operation(
                listOf("detection"),
                "Clear the whole event log",
                mapOf("200" to ok("SuccessResponse")),
            ),
        )
        path(
            "/api/detection/events/export",
            "get" to operation(
                listOf("detection"),
                "Download the event log as CSV (default) or a bare JSON array; snapshots omitted",
                mapOf(
                    "200" to mapOf(
                        "description" to "`text/csv` (default) or a JSON array for `format=json`",
                        "content" to mapOf(
                            "text/csv" to mapOf("schema" to stringSchema("RFC 4180 CSV with a header row")),
                            "application/json" to mapOf("schema" to arrayOf(ref("DetectionEventDto"))),
                        ),
                    ),
                ),
                parameters = listOf(
                    queryParam("format", "`csv` (default) or `json`", mapOf("type" to "string")),
                    queryParam("type", "`motion` | `sound` | `tamper`", mapOf("type" to "string")),
                ),
            ),
        )
        path(
            "/api/detection/stats",
            "get" to operation(
                listOf("detection"),
                "Aggregate event counts (windows, per-day series, top zones/labels)",
                mapOf("200" to ok("DetectionStatsResponseDto")),
            ),
        )
        path(
            "/api/detection/test",
            "post" to operation(
                listOf("detection"),
                "Fire one synthetic test alert through the alert sinks (never logged)",
                mapOf("200" to ok("DetectionTestResponseDto")),
            ),
        )

        // ── deterrence ──
        path(
            "/api/deterrence/siren",
            "post" to operation(
                listOf("deterrence"),
                "Turn the siren on or off",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("SirenRequest"),
            ),
        )

        // ── auth ──
        path(
            "/api/auth/config",
            "get" to operation(
                listOf("auth"),
                "The auth configuration (admin sessions only; secrets blank)",
                mapOf("200" to ok("AuthConfigDto")),
                security = listOf(mapOf("sessionCookie" to emptyList<String>())),
            ),
            "put" to operation(
                listOf("auth"),
                "Save the auth configuration",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("AuthConfigDto"),
                security = listOf(mapOf("sessionCookie" to emptyList<String>())),
            ),
            "post" to operation(
                listOf("auth"),
                "Save the auth configuration (alias of PUT)",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("AuthConfigDto"),
                security = listOf(mapOf("sessionCookie" to emptyList<String>())),
            ),
        )
        path(
            "/api/auth/sessions",
            "get" to operation(
                listOf("auth"),
                "The live sessions (admin sessions only)",
                mapOf("200" to ok("SessionListResponse")),
                security = listOf(mapOf("sessionCookie" to emptyList<String>())),
            ),
        )
        path(
            "/api/auth/sessions/{sessionId}",
            "delete" to operation(
                listOf("auth"),
                "Revoke one session",
                mapOf("200" to ok("SuccessResponse")),
                parameters = listOf(pathParam("sessionId", "The session token id")),
                security = listOf(mapOf("sessionCookie" to emptyList<String>())),
            ),
        )

        // ── audit ──
        path(
            "/api/audit",
            "get" to operation(
                listOf("audit"),
                "The audit trail (newest first; admin sessions only)",
                mapOf("200" to ok("AuditLogResponseDto")),
                parameters = listOf(queryParam("limit", "Page size", mapOf("type" to "integer"))),
            ),
            "delete" to operation(
                listOf("audit"),
                "Clear the audit trail",
                mapOf("200" to ok("SuccessResponse")),
            ),
        )

        // ── push ──
        path(
            "/api/push/vapid-public",
            "get" to operation(
                listOf("push"),
                "The VAPID public key for `pushManager.subscribe` (public by design)",
                mapOf("200" to ok("VapidPublicKeyDto")),
            ),
        )
        path(
            "/api/push/subscriptions",
            "get" to operation(
                listOf("push"),
                "The stored browser subscriptions (key material redacted)",
                mapOf("200" to ok("PushSubscriptionsResponseDto")),
            ),
            "post" to operation(
                listOf("push"),
                "Subscribe this browser's push endpoint (session-only; never token-writable)",
                mapOf("200" to ok("SuccessResponse")),
                requestBody = jsonBody("PushSubscribeRequest"),
            ),
            "delete" to operation(
                listOf("push"),
                "Unsubscribe an endpoint",
                mapOf("200" to ok("SuccessResponse")),
                parameters = listOf(queryParam("endpoint", "The endpoint URL to remove", mapOf("type" to "string"))),
            ),
        )

        // ── the spec itself ──
        path(
            "/api/openapi.json",
            "get" to operation(
                listOf("spec"),
                "This document",
                mapOf("200" to mapOf("description" to "The OpenAPI 3.1 document itself", "content" to mapOf("application/json" to mapOf("schema" to stringSchema())))),
            ),
        )

        return mapOf(
            "openapi" to "3.1.0",
            "info" to mapOf(
                "title" to "LensCast Web API",
                "version" to "1",
                "description" to "The JSON surface the phone serves at `/api/*`. Handler answers ride HTTP 200 " +
                    "with the outcome encoded in the payload (`success:false` + `error`); non-200 codes are " +
                    "reserved for routing and transport. Auth: a browser session cookie (`lenscast_session`, " +
                    "minted by POST /api/auth/login outside this router) or a read-mostly API token as a " +
                    "Bearer header (GET everywhere; POST only on the token write allow-list). When auth is off, " +
                    "every route answers without credentials.",
                "summary" to "Turn a phone into a streaming camera — live view, recording, detection events, push outputs.",
            ),
            "servers" to listOf(mapOf("url" to "/", "description" to "The device (http://<phone-ip>:8080 by default)")),
            "tags" to listOf(
                mapOf("name" to "settings"), mapOf("name" to "status"), mapOf("name" to "stream"),
                mapOf("name" to "camera"), mapOf("name" to "capture"), mapOf("name" to "recording"),
                mapOf("name" to "gallery"), mapOf("name" to "detection"), mapOf("name" to "deterrence"),
                mapOf("name" to "auth"), mapOf("name" to "audit"), mapOf("name" to "system"),
                mapOf("name" to "push"), mapOf("name" to "spec"),
            ),
            "paths" to paths,
            "components" to mapOf(
                "securitySchemes" to mapOf(
                    "sessionCookie" to mapOf(
                        "type" to "apiKey",
                        "in" to "cookie",
                        "name" to "lenscast_session",
                        "description" to "Browser session token, minted by POST /api/auth/login (outside this " +
                            "router: the login/logout contract differs from the JSON handlers).",
                    ),
                    "bearerToken" to mapOf(
                        "type" to "http",
                        "scheme" to "bearer",
                        "description" to "The read-mostly API token (when armed in settings): GET/HEAD everywhere " +
                            "protected, POST only on the fixed token write allow-list (stream/recording lifecycle, " +
                            "capture, siren/torch, model downloads, detection test), never /api/auth/* or /api/push/*.",
                    ),
                ),
                "schemas" to schemasBlock(),
            ),
        )
    }

    /** The named schemas; `x-fixture` pins one to a checked-in contract fixture. */
    private fun schemasBlock(): Map<String, Map<String, Any?>> = mapOf(
        "SuccessResponse" to schema(
            "success" to boolSchema(),
            required = listOf("success"),
            fixture = null,
        ),
        "ErrorResponse" to schema(
            "success" to boolSchema("Always false"),
            "error" to stringSchema("The readable failure reason"),
            required = listOf("success", "error"),
        ),
        "StreamActionResponse" to schema(
            "success" to boolSchema(),
            "isActive" to boolSchema(),
            "url" to stringSchema("The web stream URL where one applies"),
            "error" to stringSchema("Set when success is false"),
            required = listOf("success"),
        ),
        "CaptureResponse" to schema(
            "success" to boolSchema(),
            "fileName" to stringSchema("The stored capture's file name"),
            "error" to stringSchema(),
            required = listOf("success"),
        ),
        "DetectionTestResponseDto" to schema(
            "success" to boolSchema(),
            "dispatchedActions" to arrayOf(stringSchema()),
            required = listOf("success", "dispatchedActions"),
            fixture = "detection-test.json",
        ),
        "SettingsResponseDto" to schema(
            "camera" to ref("CameraSettingsDto"),
            "streaming" to ref("StreamingSettingsDto"),
            required = listOf("camera", "streaming"),
            fixture = "settings.json",
        ),
        "SettingsUpdateRequestDto" to schema(
            "camera" to ref("CameraSettingsDto"),
            "streaming" to ref("StreamingSettingsDto"),
            description = "Both sections optional; a present section persists wholesale (every field, including omitted ones at their defaults).",
        ),
        "SettingsExportDto" to schema(
            "schemaVersion" to intSchema("Bumped only on a breaking settings shape change"),
            "exportedAtMs" to intSchema(),
            "app" to stringSchema(),
            "settings" to ref("SettingsResponseDto"),
            required = listOf("schemaVersion", "exportedAtMs", "app", "settings"),
            fixture = "settings-export.json",
        ),
        "CameraSettingsDto" to schema(
            "exposureCompensation" to intSchema(),
            "iso" to intSchema(),
            "exposureTime" to intSchema(),
            "focusMode" to stringSchema(),
            "focusDistance" to mapOf("type" to "number"),
            "whiteBalance" to stringSchema(),
            "colorTemperature" to intSchema(),
            "zoomRatio" to mapOf("type" to "number"),
            "frameRate" to intSchema(),
            "resolution" to stringSchema(),
            "stabilization" to boolSchema(),
            "hdrMode" to stringSchema(),
            "sceneMode" to stringSchema(),
            "nightVisionMode" to stringSchema(),
            required = listOf(
                "exposureCompensation", "focusMode", "whiteBalance", "zoomRatio",
                "frameRate", "resolution", "stabilization", "hdrMode", "nightVisionMode",
            ),
        ),
        "StreamingSettingsDto" to schema(
            "port" to intSchema(),
            "webStreamingEnabled" to boolSchema(),
            "jpegQuality" to intSchema(),
            "showPreview" to boolSchema(),
            "streamAudioEnabled" to boolSchema(),
            "streamAudioBitrateKbps" to intSchema(),
            "streamAudioChannels" to intSchema(),
            "streamAudioEchoCancellation" to boolSchema(),
            "recordingAudioEnabled" to boolSchema(),
            "rtspEnabled" to boolSchema(),
            "rtspPort" to intSchema(),
            "rtspInputFormat" to stringSchema(),
            "rtspResolution" to stringSchema("`480p` | `720p` | `1080p`"),
            "rtspVideoCodec" to stringSchema("`h264` | `h265`"),
            "rtmpEnabled" to boolSchema(),
            "rtmpUrl" to stringSchema("Write-only: responses are blank"),
            "whipEnabled" to boolSchema(),
            "whipUrl" to stringSchema(),
            "whipToken" to stringSchema("Write-only: responses are blank"),
            "whipStunServer" to stringSchema(),
            "srtEnabled" to boolSchema(),
            "srtUrl" to stringSchema("Write-only: responses are blank"),
            "mqttEnabled" to boolSchema(),
            "mqttBrokerHost" to stringSchema(),
            "mqttBrokerPort" to intSchema(),
            "mqttUsername" to stringSchema(),
            "mqttPassword" to stringSchema("Write-only: responses are blank"),
            "mqttTls" to boolSchema(),
            "mqttDiscoveryPrefix" to stringSchema(),
            "mqttTelemetryEnabled" to boolSchema(),
            "pushEnabled" to boolSchema(),
            "motionDetectionEnabled" to boolSchema(),
            "motionRecordingEnabled" to boolSchema(),
            "soundDetectionEnabled" to boolSchema(),
            "tamperDetectionEnabled" to boolSchema(),
            "webhookEnabled" to boolSchema(),
            "webhookUrl" to stringSchema(),
            "watchdogEnabled" to boolSchema(),
            "onvifEnabled" to boolSchema(),
            "httpsEnabled" to boolSchema(),
            "hlsDvrSegments" to intSchema(),
            required = listOf("port", "webStreamingEnabled", "rtspEnabled", "rtspPort", "mqttEnabled", "pushEnabled"),
            description = "The settings document's streaming half; the full field set is pinned by the contract fixture.",
        ),
        "StatusResponseDto" to schema(
            "streaming" to ref("StreamingStatusDto"),
            "thermal" to stringSchema("`NORMAL` | `LIGHT` | `MODERATE` | `SEVERE` | `CRITICAL`"),
            "camera" to stringSchema(),
            "battery" to ref("BatteryStatusDto"),
            "torchOn" to boolSchema(),
            "sirenActive" to boolSchema(),
            "zoomRatio" to mapOf("type" to "number"),
            "lensId" to stringSchema(),
            "lensLabel" to stringSchema(),
            "zoomRange" to ref("RangeDto"),
            "exposureCompensationRange" to ref("RangeDto"),
            "isoRange" to ref("RangeDto"),
            "adaptiveBitrate" to mapOf("type" to "object", "description" to "Present while the MJPEG adaptive bitrate is enabled"),
            "connectionQuality" to mapOf("type" to "object", "description" to "Present while any stream is live"),
            "watchdog" to mapOf("type" to "object"),
            "encodedVideoBitrate" to intSchema("The encoded pipeline's live target bitrate in bps"),
            required = listOf("streaming", "thermal", "camera", "battery"),
            fixture = "status.json",
        ),
        "RangeDto" to schema(
            "min" to mapOf("type" to "number"),
            "max" to mapOf("type" to "number"),
            required = listOf("min", "max"),
        ),
        "StreamingStatusDto" to schema(
            "isActive" to boolSchema(),
            "url" to stringSchema(),
            "webStreamingEnabled" to boolSchema(),
            "webStreamingActive" to boolSchema(),
            "clientCount" to intSchema(),
            "audioEnabled" to boolSchema(),
            "audioUrl" to stringSchema(),
            "rtspEnabled" to boolSchema(),
            "rtspStreamingActive" to boolSchema(),
            "rtspUrl" to stringSchema(),
            "rtmpEnabled" to boolSchema(),
            "rtmpActive" to boolSchema(),
            "rtmpStatus" to stringSchema("`idle` | `connecting` | `connected` | `error`"),
            "rtmpError" to stringSchema(),
            "whipEnabled" to boolSchema(),
            "whipActive" to boolSchema(),
            "whipStatus" to stringSchema("`idle` | `connecting` | `connected` | `error`"),
            "whipError" to stringSchema(),
            "srtEnabled" to boolSchema(),
            "srtActive" to boolSchema(),
            "srtStatus" to stringSchema("`idle` | `connecting` | `connected` | `error`"),
            "srtError" to stringSchema(),
            "whepClients" to intSchema("The live WHEP (WebRTC viewer) session count"),
            required = listOf("isActive", "url", "clientCount", "audioEnabled", "audioUrl"),
        ),
        "BatteryStatusDto" to schema(
            "level" to intSchema(),
            "isCharging" to boolSchema(),
            "isPowerSaveMode" to boolSchema(),
            required = listOf("level", "isCharging", "isPowerSaveMode"),
        ),
        "LensesResponseDto" to schema(
            "lenses" to arrayOf(ref("LensDto")),
            "selectedIndex" to intSchema(),
            required = listOf("lenses", "selectedIndex"),
            fixture = "lenses.json",
        ),
        "LensDto" to schema(
            "index" to intSchema(),
            "id" to stringSchema(),
            "label" to stringSchema(),
            "focalLength" to mapOf("type" to "number"),
            "isFront" to boolSchema(),
            "selected" to boolSchema(),
            required = listOf("index", "id", "label", "focalLength", "isFront", "selected"),
        ),
        "LensSelectRequest" to schema("index" to intSchema(), required = listOf("index")),
        "TapFocusRequest" to schema(
            "x" to mapOf("type" to "number"),
            "y" to mapOf("type" to "number"),
            required = listOf("x", "y"),
        ),
        "ZoomRequest" to schema(
            "zoomRatio" to mapOf("type" to "number"),
            "ratio" to mapOf("type" to "number"),
        ),
        "TorchRequest" to schema("enabled" to boolSchema("Omitted toggles")),
        "SirenRequest" to schema("enabled" to boolSchema("Omitted toggles")),
        "IntervalCaptureStatusDto" to schema(
            "isRunning" to boolSchema(),
            "completedCaptures" to intSchema(),
            required = listOf("isRunning", "completedCaptures"),
            fixture = "interval-capture-status.json",
        ),
        "IntervalCaptureConfig" to schema(
            "intervalSeconds" to intSchema("1..3600"),
            "totalCaptures" to intSchema(),
            "flashMode" to stringSchema("`ON` | `OFF` | `AUTO`"),
            required = listOf("intervalSeconds", "totalCaptures", "flashMode"),
        ),
        "RecordingStatusDto" to schema(
            "isRecording" to boolSchema(),
            "elapsedSeconds" to intSchema(),
            "isScheduled" to boolSchema(),
            "scheduledStartTimeMs" to intSchema(),
            required = listOf("isRecording", "elapsedSeconds"),
            fixture = "recording-status.json",
        ),
        "RecordingConfig" to schema(
            "durationSeconds" to intSchema(),
            "repeatIntervalSeconds" to intSchema(),
            "quality" to stringSchema("`HIGH` | `MEDIUM` | `LOW`"),
            "includeAudio" to boolSchema(),
            "startTimeMs" to intSchema("Schedule: start at this wall-clock moment"),
            required = listOf("durationSeconds", "repeatIntervalSeconds", "quality", "includeAudio"),
        ),
        "RecordingSessionsResponseDto" to schema(
            "sessions" to arrayOf(ref("RecordingSessionDto")),
            required = listOf("sessions"),
            fixture = "recording-sessions.json",
        ),
        "RecordingSessionDto" to schema(
            "id" to stringSchema(),
            "startMs" to intSchema(),
            "endMs" to intSchema(),
            "trigger" to stringSchema("`manual` | `motion` | `sound` | `continuous` | `scheduled` | `interval`"),
            "mediaId" to stringSchema("The capture-history id; null when nothing is linked"),
            required = listOf("id", "startMs", "endMs", "trigger"),
        ),
        "GalleryResponseDto" to schema(
            "items" to arrayOf(ref("GalleryItemDto")),
            "total" to intSchema(),
            "page" to intSchema(),
            "pageSize" to intSchema(),
            "hasMore" to boolSchema(),
            required = listOf("items", "total", "page", "pageSize", "hasMore"),
            fixture = "gallery.json",
        ),
        "GalleryItemDto" to schema(
            "id" to stringSchema(),
            "type" to stringSchema("`PHOTO` | `VIDEO`"),
            "fileName" to stringSchema(),
            "timestamp" to intSchema(),
            "fileSizeBytes" to intSchema(),
            "durationMs" to intSchema(),
            "favorite" to boolSchema(),
            "thumbnailUrl" to stringSchema(),
            "url" to stringSchema(),
            "downloadUrl" to stringSchema(),
            required = listOf("id", "type", "fileName", "timestamp", "fileSizeBytes", "durationMs", "thumbnailUrl", "url", "downloadUrl"),
        ),
        "BatchDeleteRequest" to schema(
            "ids" to arrayOf(stringSchema()),
            required = listOf("ids"),
        ),
        "BatchDeleteResponse" to schema(
            "success" to boolSchema(),
            "deleted" to arrayOf(stringSchema()),
            required = listOf("success", "deleted"),
        ),
        "DetectionEventsResponseDto" to schema(
            "events" to arrayOf(ref("DetectionEventDto")),
            "total" to intSchema(),
            required = listOf("events", "total"),
            fixture = "detection-events.json",
        ),
        "DetectionEventDto" to schema(
            "id" to stringSchema(),
            "type" to stringSchema("`motion` | `sound` | `tamper`"),
            "source" to stringSchema(),
            "timestampMs" to intSchema(),
            "snapshotJpegBase64" to stringSchema("Downscaled trigger frame; omitted on the export routes"),
            "dispatchedActions" to arrayOf(stringSchema()),
            "zones" to arrayOf(stringSchema()),
            "labels" to arrayOf(stringSchema("ML/YAMNet class labels")),
            "clipMediaId" to intSchema("MediaStore id once the bounded recording finalized"),
            "clipFileName" to stringSchema(),
            "url" to stringSchema("Dashboard deep link: `#/gallery/<clipMediaId>` or `#/events`"),
            required = listOf("id", "type", "source", "timestampMs", "dispatchedActions", "zones"),
        ),
        "DetectionStatsResponseDto" to schema(
            "last24h" to mapOf("type" to "object", "additionalProperties" to intSchema()),
            "last7d" to mapOf("type" to "object", "additionalProperties" to intSchema()),
            "allTime" to mapOf("type" to "object", "additionalProperties" to intSchema()),
            "perDay" to arrayOf(ref("DailyCountDto")),
            "totalEvents" to intSchema(),
            "topZones" to arrayOf(ref("LabeledCountDto")),
            "topLabels" to arrayOf(ref("LabeledCountDto")),
            required = listOf("last24h", "last7d", "allTime", "perDay", "totalEvents", "topZones", "topLabels"),
            fixture = "detection-stats.json",
        ),
        "DailyCountDto" to schema(
            "day" to stringSchema("`YYYY-MM-DD`, UTC"),
            "count" to intSchema(),
            required = listOf("day", "count"),
        ),
        "LabeledCountDto" to schema(
            "label" to stringSchema(),
            "count" to intSchema(),
            required = listOf("label", "count"),
        ),
        "SystemInfoResponseDto" to schema(
            "appVersion" to stringSchema(),
            "deviceModel" to stringSchema(),
            "deviceManufacturer" to stringSchema(),
            "androidVersion" to stringSchema(),
            "sdkInt" to intSchema(),
            "osUptimeMs" to intSchema(),
            "processUptimeMs" to intSchema(),
            "battery" to ref("BatteryDetailDto"),
            "storage" to ref("StorageInfoDto"),
            required = listOf("appVersion", "deviceModel", "deviceManufacturer", "androidVersion", "sdkInt", "osUptimeMs", "processUptimeMs", "battery", "storage"),
            fixture = "system.json",
        ),
        "BatteryDetailDto" to schema(
            "level" to intSchema(),
            "isCharging" to boolSchema(),
            "temperatureTenthsC" to intSchema(),
            "voltageMillivolts" to intSchema(),
            "health" to stringSchema(),
            "batteryChargeCounterMah" to intSchema(),
            "batteryCurrentMicroAmps" to intSchema(),
            "batteryCycleCount" to intSchema(),
            required = listOf("level", "isCharging"),
        ),
        "StorageInfoDto" to schema(
            "usedBytes" to intSchema(),
            "quotaBytes" to intSchema(),
            "freeBytes" to intSchema(),
            "totalBytes" to intSchema(),
            required = listOf("usedBytes", "quotaBytes", "freeBytes", "totalBytes"),
        ),
        "AuditLogResponseDto" to schema(
            "entries" to arrayOf(ref("AuditEntryDto")),
            "total" to intSchema(),
            required = listOf("entries", "total"),
            fixture = "audit-log.json",
        ),
        "AuditEntryDto" to schema(
            "timestampMs" to intSchema(),
            "action" to stringSchema("`\"POST /api/settings\"`, `login.failed`, …"),
            "detail" to stringSchema(),
            "outcome" to stringSchema("`ok` | `error`"),
            required = listOf("timestampMs", "action", "detail", "outcome"),
        ),
        "AuthConfigDto" to schema(
            "enabled" to boolSchema(),
            "username" to stringSchema("Admin sessions only on GET; viewer sessions get the redacted view"),
            "password" to stringSchema("Write-only on PUT; always blank on GET"),
            "viewerEnabled" to boolSchema(),
            "viewerUsername" to stringSchema(),
            "viewerPassword" to stringSchema("Write-only on PUT; always blank on GET"),
            "viewerConfigured" to boolSchema(),
            required = listOf("enabled", "username", "password", "viewerEnabled", "viewerPassword", "viewerConfigured"),
            fixture = "auth-config.json",
        ),
        "SessionListResponse" to schema(
            "sessions" to arrayOf(
                schema(
                    "id" to stringSchema(),
                    "role" to stringSchema("`admin` | `viewer`"),
                    "createdAtMs" to intSchema(),
                    "expiresAtMs" to intSchema(),
                    "current" to boolSchema(),
                ),
            ),
            description = "The live session list (admin sessions only).",
        ),
        "VapidPublicKeyDto" to schema(
            "publicKey" to stringSchema("base64url, 65-byte uncompressed P-256 point"),
            required = listOf("publicKey"),
        ),
        "PushSubscriptionsResponseDto" to schema(
            "subscriptions" to arrayOf(ref("PushSubscriptionDto")),
            "count" to intSchema(),
            required = listOf("subscriptions", "count"),
        ),
        "PushSubscriptionDto" to schema(
            "endpoint" to stringSchema("The push-service endpoint URL"),
            "createdAtMs" to intSchema(),
            required = listOf("endpoint", "createdAtMs"),
        ),
        "PushSubscribeRequest" to schema(
            "endpoint" to stringSchema(),
            "keys" to mapOf(
                "type" to "object",
                "description" to "`p256dh` and `auth`, base64url — never echoed back by any response",
                "additionalProperties" to stringSchema(),
            ),
            required = listOf("endpoint"),
        ),
    )
}

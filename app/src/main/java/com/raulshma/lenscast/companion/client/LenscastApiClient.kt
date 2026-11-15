package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.client.dto.AuditResponse
import com.raulshma.lenscast.companion.client.dto.AuthSessionInfo
import com.raulshma.lenscast.companion.client.dto.AuthStatusInfo
import com.raulshma.lenscast.companion.client.dto.BatchDeleteRequest
import com.raulshma.lenscast.companion.client.dto.BatchDeleteResponse
import com.raulshma.lenscast.companion.client.dto.CommandResponse
import com.raulshma.lenscast.companion.client.dto.DetectionEventsResponse
import com.raulshma.lenscast.companion.client.dto.DetectionStats
import com.raulshma.lenscast.companion.client.dto.DetectionTestResponse
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.AuthConfigInfo
import com.raulshma.lenscast.companion.client.dto.AuthConfigPutRequest
import com.raulshma.lenscast.companion.client.dto.AuthSessionsResponse
import com.raulshma.lenscast.companion.client.dto.FocusRequest
import com.raulshma.lenscast.companion.client.dto.GalleryResponse
import com.raulshma.lenscast.companion.client.dto.IntervalCaptureStartRequest
import com.raulshma.lenscast.companion.client.dto.IntervalCaptureStatus
import com.raulshma.lenscast.companion.client.dto.LensSelectRequest
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.LoginRequest
import com.raulshma.lenscast.companion.client.dto.LoginResponse
import com.raulshma.lenscast.companion.client.dto.RecordingStartRequest
import com.raulshma.lenscast.companion.client.dto.RecordingStatus
import com.raulshma.lenscast.companion.client.dto.RecordingsResponse
import com.raulshma.lenscast.companion.client.dto.SettingsUpdate
import com.raulshma.lenscast.companion.client.dto.SirenRequest
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.companion.client.dto.StreamClientsInfo
import com.raulshma.lenscast.companion.client.dto.SystemInfo
import com.raulshma.lenscast.companion.client.dto.DeviceStatus
import com.raulshma.lenscast.companion.client.dto.TorchRequest
import com.raulshma.lenscast.companion.client.dto.ZoomRequest
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.WebAuthGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One paired device's JSON API client — the Kotlin twin of the web
 * dashboard's fetch layer (web/src/api/client.ts), giving the app parity with
 * the embedded console: status polls, system diagnostics, lens enumeration,
 * client rosters, detection events, gallery/recording/interval-capture
 * control, camera commands, stream toggles, and the auth surface.
 *
 * One shared OkHttp engine per device ([buildEngine], connection pool + TLS
 * session reuse across the poll/SSE/media lanes — wave-2 sessions pull the
 * same engine via [sharedEngine] for SSE, talkback, and media). Auth follows
 * the remote's ladder per request from the live [credentials] provider: a
 * stored token sends `X-Api-Token`, basic credentials send RFC 7617 —
 * re-read per call so a re-pair takes effect without rebuilding the client.
 * Every non-GET carries `X-Requested-With: XMLHttpRequest` (the CSRF header
 * the server's write gate demands of cookie-authenticated callers). A
 * `lenscast_session` cookie captured from a [login] answer rides later
 * requests ONLY while no stored credential answers the provider — explicit
 * credentials must win the ladder alone, because the server only treats
 * `Authorization: Bearer`/`X-Api-Token` as token presentations and a Basic
 * header beside a stale cookie would silently authenticate as the cookie's
 * (possibly viewer/revoked) session instead. With a pinned fingerprint
 * ([CompanionDevice.tlsFingerprint], from the QR's `#fp=` fragment) the
 * engine trusts exactly that cert — see [buildEngine] for the MITM model.
 *
 * Failures are the [CompanionApiException] taxonomy: 401 → [CompanionApiException.Auth]
 * (re-pair cue), 403 → [CompanionApiException.Forbidden] (read-only banner),
 * other non-2xx → [CompanionApiException.Http], transport errors →
 * [CompanionApiException.Network], and a failed pin check →
 * [CompanionApiException.PinMismatch]. Response bodies are always consumed
 * and closed before resumption, success or not.
 *
 * Endpoint routes mirror streaming/web/ApiRouter.kt exactly; implemented in
 * wave 1 (signatures beyond the frozen four are additive).
 */
class LenscastApiClient(
    private val device: CompanionDevice,
    private val credentials: () -> StoredCredentials?,
) {

    private val moshi = AppJson.moshi

    /** The per-device engine; the shared pool the SSE and media lanes reuse. */
    private val http: OkHttpClient = device.buildEngine()

    /**
     * The session cookie captured from the latest [login] (bare
     * `lenscast_session` value, no attributes). Cleared when the device
     * revokes it (401 while riding the cookie) or [logout] answers.
     */
    @Volatile
    private var sessionCookie: String? = null

    /** The device snapshot this client dials; wave-2 lanes re-read it for URLs. */
    fun device(): CompanionDevice = device

    /**
     * The shared OkHttp engine (pool + pinning + budgets) — SSE, talkback,
     * and media lanes build their long-lived connections from it so every
     * lane of one device shares connections and the same trust decision.
     */
    fun sharedEngine(): OkHttpClient = http

    // ── Status & diagnostics ──

    /** GET /api/status — the dashboard poll. */
    suspend fun getStatus(): DeviceStatus = decode(send(Verb.GET, "/api/status"))

    /** GET /api/system — the diagnostics snapshot. */
    suspend fun getSystemInfo(): SystemInfo = decode(send(Verb.GET, "/api/system"))

    /** GET /api/camera/lenses — the lens list plus selection. */
    suspend fun getLenses(): LensesResponse = decode(send(Verb.GET, "/api/camera/lenses"))

    /** GET /api/stream/clients — the HTTP + RTSP client rosters. */
    suspend fun getStreamClients(): StreamClientsInfo =
        decode(send(Verb.GET, "/api/stream/clients"))

    // ── Detection ──

    /**
     * GET /api/detection/events — the event-log page. `limit <= 0` omitted
     * (server's default page size); `type` is `motion`|`sound`|`tamper`;
     * `day` (YYYY-MM-DD) narrows to one day on newer servers and is omitted
     * otherwise — older devices still answer the unfiltered route.
     */
    suspend fun getDetectionEvents(limit: Int? = null, type: String? = null, day: String? = null): DetectionEventsResponse =
        decode(
            send(
                Verb.GET,
                queryUrl(
                    "/api/detection/events",
                    "limit" to limit?.takeIf { it > 0 }?.toString(),
                    "type" to type,
                    "day" to day?.takeIf { DAY_REGEX.matches(it) },
                ),
            ),
        )

    /** GET /api/detection/stats — aggregate counts over the persisted event log. */
    suspend fun getDetectionStats(): DetectionStats =
        decode(send(Verb.GET, "/api/detection/stats"))

    /** DELETE /api/detection/events — clear the whole event log. */
    suspend fun clearDetectionEvents(): CommandResponse =
        decode(send(Verb.DELETE, "/api/detection/events"))

    /** POST /api/detection/test — one synthetic alert through the real alert sinks. */
    suspend fun detectionTest(): DetectionTestResponse =
        decode(send(Verb.POST, "/api/detection/test"))

    // ── Media ──

    /**
     * GET /api/gallery — one page of the remote capture library, mirroring
     * the web client's param semantics: `page`/`pageSize` ride only when
     * positive (0 = server default page), `type` filters PHOTO|VIDEO, `q`
     * is a filename substring (trimmed; server-side filter on newer builds).
     */
    suspend fun getGallery(page: Int, pageSize: Int, type: String? = null, q: String? = null): GalleryResponse =
        decode(
            send(
                Verb.GET,
                queryUrl(
                    "/api/gallery",
                    "type" to type,
                    "page" to page.takeIf { it > 0 }?.toString(),
                    "pageSize" to pageSize.takeIf { it > 0 }?.toString(),
                    "q" to q?.trim()?.takeIf { it.isNotEmpty() },
                ),
            ),
        )

    /** DELETE /api/media/{id} — one capture off the remote device. */
    suspend fun deleteMedia(id: String): CommandResponse =
        decode(send(Verb.DELETE, "/api/media/${encodeSegment(id)}"))

    /** POST /api/media/batch-delete — bulk remove; the answer names what went. */
    suspend fun batchDeleteMedia(ids: List<String>): BatchDeleteResponse =
        decode(send(Verb.POST, "/api/media/batch-delete", BatchDeleteRequest(ids)))

    /** GET /api/recordings/sessions?day=YYYY-MM-DD — the NVR day timeline. */
    suspend fun recordingsSessions(day: String? = null): RecordingsResponse =
        decode(send(Verb.GET, queryUrl("/api/recordings/sessions", "day" to day)))

    /** GET /api/recording/status — the manual/scheduled recording state. */
    suspend fun recordingStatus(): RecordingStatus =
        decode(send(Verb.GET, "/api/recording/status"))

    /**
     * POST /api/recording/start — omitted arguments take the device's
     * defaults (unbounded duration, HIGH quality, audio on, start now).
     */
    suspend fun recordingStart(
        durationSeconds: Long? = null,
        repeatIntervalSeconds: Long? = null,
        quality: String? = null,
        includeAudio: Boolean? = null,
        startTimeMs: Long? = null,
    ): CommandResponse =
        decode(
            send(
                Verb.POST,
                "/api/recording/start",
                RecordingStartRequest(
                    durationSeconds = durationSeconds,
                    repeatIntervalSeconds = repeatIntervalSeconds,
                    quality = quality,
                    includeAudio = includeAudio,
                    startTimeMs = startTimeMs,
                ),
            ),
        )

    /** POST /api/recording/stop. */
    suspend fun recordingStop(): CommandResponse =
        decode(send(Verb.POST, "/api/recording/stop"))

    /** GET /api/capture/interval/status — the interval-capture scheduler state. */
    suspend fun intervalCaptureStatus(): IntervalCaptureStatus =
        decode(send(Verb.GET, "/api/capture/interval/status"))

    /** POST /api/capture/interval/start — periodic photos on the remote device. */
    suspend fun intervalCaptureStart(
        intervalSeconds: Int,
        totalCaptures: Int? = null,
        flashMode: String? = null,
    ): CommandResponse =
        decode(
            send(
                Verb.POST,
                "/api/capture/interval/start",
                IntervalCaptureStartRequest(
                    intervalSeconds = intervalSeconds,
                    totalCaptures = totalCaptures,
                    flashMode = flashMode,
                ),
            ),
        )

    /** POST /api/capture/interval/stop. */
    suspend fun intervalCaptureStop(): CommandResponse =
        decode(send(Verb.POST, "/api/capture/interval/stop"))

    // ── Camera controls ──

    /** POST /api/capture — one photo through the device's capture pipeline. */
    suspend fun capturePhoto(): CommandResponse =
        decode(send(Verb.POST, "/api/capture"))

    /** POST /api/camera/torch — the device's live torch state. */
    suspend fun setTorch(enabled: Boolean): CommandResponse =
        decode(send(Verb.POST, "/api/camera/torch", TorchRequest(enabled)))

    /** POST /api/camera/zoom — zoom ratio within the device's live zoom range. */
    suspend fun setZoom(zoomRatio: Double): CommandResponse =
        decode(send(Verb.POST, "/api/camera/zoom", ZoomRequest(zoomRatio)))

    /** POST /api/camera/focus — normalized (0..1) tap coordinates. */
    suspend fun setFocus(x: Double, y: Double): CommandResponse =
        decode(send(Verb.POST, "/api/camera/focus", FocusRequest(x, y)))

    /** PUT /api/camera/lens — bind the enumerated lens [index] (the router accepts POST too; PUT is the browser parity verb). */
    suspend fun setLens(index: Int): CommandResponse =
        decode(send(Verb.PUT, "/api/camera/lens", LensSelectRequest(index)))

    /** POST /api/deterrence/siren — the device's siren; the wire field is `siren`. */
    suspend fun setSiren(on: Boolean): CommandResponse =
        decode(send(Verb.POST, "/api/deterrence/siren", SirenRequest(on)))

    // ── Streams ──

    /**
     * POST /api/stream/web/start|stop — the MJPEG output toggle; on start the
     * answer's [CommandResponse.url] carries the MJPEG stream URL the viewer
     * lane opens.
     */
    suspend fun setWebStreamRunning(start: Boolean): CommandResponse =
        decode(send(Verb.POST, if (start) "/api/stream/web/start" else "/api/stream/web/stop"))

    /** POST /api/stream/rtsp/start|stop — the RTSP output toggle. */
    suspend fun setRtspStreamRunning(start: Boolean): CommandResponse =
        decode(send(Verb.POST, if (start) "/api/stream/rtsp/start" else "/api/stream/rtsp/stop"))

    /** DELETE /api/stream/clients/{id} — drop one HTTP viewer's connection. */
    suspend fun kickClient(id: String): CommandResponse =
        decode(send(Verb.DELETE, "/api/stream/clients/${encodeSegment(id)}"))

    // ── Auth ──

    /** GET /api/auth/status — whether the remote requires a sign-in at all. */
    suspend fun authStatus(): AuthStatusInfo =
        decode(send(Verb.GET, "/api/auth/status"))

    /** GET /api/auth/session — whether the held session cookie is live, and its role. */
    suspend fun authSession(): AuthSessionInfo =
        decode(send(Verb.GET, "/api/auth/session"))

    /**
     * POST /api/auth/login — sign in and capture the minted
     * `lenscast_session` cookie for later cookie-authed requests (the
     * companion's answer to the browser session, used whenever no stored
     * token/basic credential answers the provider).
     */
    suspend fun login(username: String, password: String): LoginResponse {
        val exchange = exchange(request(Verb.POST, "/api/auth/login", LoginRequest(username, password)))
        captureSessionCookie(exchange.headers)
        return decode(exchange.body)
    }

    /** POST /api/auth/logout — drop the session server-side and forget the cookie. */
    suspend fun logout(): CommandResponse {
        val response = decode<CommandResponse>(send(Verb.POST, "/api/auth/logout"))
        sessionCookie = null
        return response
    }

    /** GET /api/auth/config — the (server-redacted) credential config read. */
    suspend fun authConfig(): AuthConfigInfo =
        decode(send(Verb.GET, "/api/auth/config"))

    /**
     * PUT /api/auth/config with a pre-built body — the escape hatch for the
     * raw JSON surface (the typed overload below is the normal path). The
     * write-only-password semantics live in the body's omitted fields.
     */
    suspend fun putAuthConfig(json: RequestBody?): CommandResponse =
        decode(send(Verb.PUT, "/api/auth/config", json))

    /**
     * PUT /api/auth/config, typed. Pass passwords ONLY when rotating them
     * (a null password is omitted = "keep the stored hash"). Passing
     * `viewerEnabled = false` (or omitting it) DISABLES the viewer pair
     * server-side — preserve the device's current viewer section when the
     * change touches only the admin pair (see [AuthConfigPutRequest]).
     */
    suspend fun putAuthConfig(
        enabled: Boolean,
        username: String,
        password: String? = null,
        viewerEnabled: Boolean? = null,
        viewerUsername: String? = null,
        viewerPassword: String? = null,
    ): CommandResponse =
        putAuthConfig(
            encodeBody(
                AuthConfigPutRequest(
                    enabled = enabled,
                    username = username,
                    password = password,
                    viewerEnabled = viewerEnabled,
                    viewerUsername = viewerUsername,
                    viewerPassword = viewerPassword,
                ),
            ),
        )

    /** GET /api/auth/sessions — the live session roster (admin-only). */
    suspend fun authSessions(): AuthSessionsResponse =
        decode(send(Verb.GET, "/api/auth/sessions"))

    /** DELETE /api/auth/sessions/{prefix} — revoke one session by its token prefix. */
    suspend fun revokeSession(prefix: String): CommandResponse =
        decode(send(Verb.DELETE, "/api/auth/sessions/${encodeSegment(prefix)}"))

    // ── Audit ──

    /** GET /api/audit — the audit trail page; `limit <= 0` means the server default. */
    suspend fun auditEntries(limit: Int = 100): AuditResponse =
        decode(send(Verb.GET, queryUrl("/api/audit", "limit" to limit.takeIf { it > 0 }?.toString())))

    // ── Pure URL builders (no network) ──
    // The playback/media/UI lanes open these directly (media player, image
    // loader, WebSocket) — they need the addresses without a JSON round trip.
    // All ride the same auth the request headers carry: cookie consumers add
    // `Cookie: lenscast_session=…` themselves; token/basic lanes append their
    // header. See sharedAuthHeaders() for the header set.

    /**
     * GET /api/events — the status/transport SSE push lane (the device emits
     * a named `status` event with the newest [DeviceStatus] every ~1 s plus a
     * named `transport` event when the listener rebinds to another scheme).
     * Wave-2 sessions open it through [com.raulshma.lenscast.companion.client.sse.SseEventSource]
     * with [sharedAuthHeaders]; this builder exists so every lane's URL
     * semantics (IPv6 bracketing, scheme) stay in this one class.
     */
    fun statusEventsUrl(): String = url("/api/events")

    /** GET /api/detection/events/stream — the detection-event SSE push lane (data-only frames). */
    fun detectionEventsStreamUrl(): String = url("/api/detection/events/stream")

    /** The MJPEG stream — `<img>`-renderable multipart feed. */
    fun streamUrl(): String = url("/stream")

    /** The preview snapshot; `highres` pulls the full-res still (`?highres=1`). */
    fun snapshotUrl(highres: Boolean = false): String =
        url(if (highres) "/snapshot?highres=1" else "/snapshot")

    /** The live audio feed (AAC/ADTS). */
    fun audioUrl(): String = url("/audio")

    /** The HLS muxed-A/V playlist the player lane segments from. */
    fun hlsPlaylistUrl(): String = url("/hls/playlist.m3u8")

    /** The WHEP (WebRTC egress) offer endpoint. */
    fun whepUrl(): String = url("/whep")

    /** A gallery capture's media route (image loader / player source). */
    fun mediaUrl(id: String): String = url("/api/media/${encodeSegment(id)}")

    /** The same media, forced download (`?download=1` — Content-Disposition). */
    fun mediaDownloadUrl(id: String): String = "${mediaUrl(id)}?download=1"

    /** The encoded-hub WebSocket video fan-out (`ws(s)://…/ws/video`). */
    fun videoWsUrl(): String = wsUrl("/ws/video")

    /** The talkback uplink WebSocket (`ws(s)://…/ws/talkback`). */
    fun talkbackWsUrl(): String = wsUrl("/ws/talkback")

    /**
     * The request headers the JSON lane authenticates with, for the lanes
     * that build their own connections from [sharedEngine] (SSE EventSource
     * builder, WebSocket upgrade, media fetch) — the same token/basic/cookie
     * ladder, evaluated live so a re-pair applies to new connections.
     */
    fun sharedAuthHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val creds = credentials()
        when {
            creds == null -> sessionCookie?.let { headers["Cookie"] = "$COOKIE_NAME=$it" }
            creds.kind == CompanionAuthKind.TOKEN ->
                creds.token?.takeIf { it.isNotEmpty() }?.let { headers["X-Api-Token"] = it }
            else -> creds.username?.let { user ->
                headers["Authorization"] = Credentials.basic(user, creds.password.orEmpty())
            }
        }
        return headers
    }

    // ── Internals ──

    private enum class Verb { GET, POST, PUT, DELETE }

    /** One completed exchange: the body text plus the response headers. */
    private class Exchange(val body: String, val headers: Headers)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val emptyBody = ByteArray(0).toRequestBody()

    /** The device's base URL; hosts carrying `:` (bare IPv6) get bracketed. */
    private val baseUrl: String = run {
        val host = if (device.host.contains(':') && !device.host.startsWith("[")) {
            "[${device.host}]"
        } else {
            device.host
        }
        "${if (device.useTls) "https" else "http"}://$host:${device.port}"
    }

    private fun url(path: String): String = "$baseUrl$path"

    private fun wsUrl(path: String): String {
        val scheme = if (device.useTls) "wss" else "ws"
        val httpScheme = if (device.useTls) "https" else "http"
        return "$scheme://${url(path).removePrefix("$httpScheme://")}"
    }

    /** Percent-encodes a path segment (URLEncoder is form-encoding, so `+` → `%20`). */
    private fun encodeSegment(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /** Joins non-null `key=value` pairs onto [path]; no pairs → [path] bare. */
    private fun queryUrl(path: String, vararg params: Pair<String, String?>): String {
        val query = params.filter { it.second != null }
            .joinToString("&") { (key, value) -> "$key=${encodeSegment(value!!)}" }
        return if (query.isEmpty()) path else "$path?$query"
    }

    private fun encodeBody(payload: Any?): RequestBody? = when (payload) {
        null -> null
        is RequestBody -> payload
        else -> moshi.adapter(payload.javaClass).toJson(payload).toRequestBody(jsonMediaType)
    }

    /**
     * One request around the auth ladder. Returns the request and whether it
     * rides the captured cookie — the 401 handler clears that stale cookie
     * only when the failure happened on the cookie path (a 401 against
     * stored credentials means re-pair, not "cookie expired").
     */
    private fun request(verb: Verb, path: String, payload: Any? = null): Pair<Request, Boolean> {
        val builder = Request.Builder().url(url(path))
        var rodeCookie = false
        val creds = credentials()
        when {
            creds == null ->
                sessionCookie?.let {
                    builder.header("Cookie", "$COOKIE_NAME=$it")
                    rodeCookie = true
                }
            creds.kind == CompanionAuthKind.TOKEN ->
                creds.token?.takeIf { it.isNotEmpty() }?.let { builder.header("X-Api-Token", it) }
            else ->
                creds.username?.let {
                    builder.header("Authorization", Credentials.basic(it, creds.password.orEmpty()))
                }
        }
        // The CSRF gate: cookie-authenticated writes must carry the header or
        // the device 403s. Header-authenticated (token/basic) requests pass
        // the gate regardless; carrying it always is the browser-parity shape.
        if (verb != Verb.GET) builder.header(CSRF_HEADER, CSRF_VALUE)
        when (verb) {
            Verb.GET -> builder.get()
            Verb.POST -> builder.post(encodeBody(payload) ?: emptyBody)
            Verb.PUT -> builder.put(encodeBody(payload) ?: emptyBody)
            Verb.DELETE -> builder.delete(encodeBody(payload) ?: emptyBody)
        }
        return builder.build() to rodeCookie
    }

    /** Builds and runs one request, handing back the raw body. */
    private suspend fun send(verb: Verb, path: String, payload: Any? = null): String =
        exchange(request(verb, path, payload)).body

    /**
     * The single execution seam: enqueue + suspend (cancellation aborts the
     * in-flight call), body consumed and response closed ALWAYS, then the
     * answer translated to the [CompanionApiException] taxonomy — 401 (and a
     * stale-cookie cleanup), 403, other non-2xx with the raw envelope, pin
     * mismatch out of the transport error's cause chain, everything else
     * Network. Off Main: the TLS init and body drain run on Dispatchers.IO.
     */
    private suspend fun exchange(prepared: Pair<Request, Boolean>): Exchange =
        withContext(Dispatchers.IO) {
            val (request_, rodeCookie) = prepared
            val response = try {
                http.newCall(request_).await()
            } catch (e: IOException) {
                if (e.hasPinMismatchCause()) {
                    throw CompanionApiException.PinMismatch(
                        normalizeFingerprint(device.tlsFingerprint.orEmpty()),
                    )
                }
                throw CompanionApiException.Network(e)
            }
            response.use { answered ->
                val bodyText = answered.body?.string().orEmpty()
                when {
                    answered.code == 401 -> {
                        // A cookie-path 401 means the session died (rotated
                        // or revoked) — forget it so the next login mint is
                        // the only source of a fresh one.
                        if (rodeCookie) sessionCookie = null
                        throw CompanionApiException.Auth()
                    }
                    answered.code == 403 -> throw CompanionApiException.Forbidden(device.role)
                    !answered.isSuccessful -> throw CompanionApiException.Http(answered.code, bodyText)
                }
                Exchange(bodyText, answered.headers)
            }
        }

    /** Harvests `lenscast_session` off the login/logout answer's Set-Cookie headers. */
    private fun captureSessionCookie(headers: Headers) {
        val cookie = headers.values("Set-Cookie")
            .firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.removePrefix("$COOKIE_NAME=")
            ?.substringBefore(';')
            ?.trim()
        sessionCookie = cookie?.takeIf { it.isNotEmpty() }
    }

    /** Moshi decode through the app's one instance; null body is a bug, not a state. */
    private inline fun <reified T : Any> decode(body: String): T =
        moshi.adapter(T::class.java).fromJson(body)
            ?: error("${T::class.simpleName} decoded to null: ${body.take(200)}")

    private companion object {
        /**
         * The CSRF header the server's write gate demands (`X-Requested-With:
         * XMLHttpRequest` — the same constant value the web client sends).
         */
        const val CSRF_HEADER = "X-Requested-With"
        const val CSRF_VALUE = "XMLHttpRequest"

        /**
         * The session cookie's name, lifted from the gate itself so a server
         * rename cannot silently desync the companion client.
         */
        const val COOKIE_NAME = WebAuthGate.COOKIE_NAME

        /** The calendar-day shape the device's event filter accepts. */
        val DAY_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }

    // ── Settings (wave 3, the system tab) ──
    // Appended additively after the companion object; the members above are
    // the frozen wave-0/1 surface. See SettingsDtos.kt for the wire contract:
    // a PUT section persists wholesale, so callers MUST merge their patch
    // into a freshly fetched document (the web console's GET-merge-PUT rule)
    // rather than PUT a hand-built partial section.

    /** GET /api/settings — the whole settings document (camera + streaming). */
    suspend fun getSettings(): AllSettings =
        decode(send(Verb.GET, "/api/settings"))

    /**
     * PUT /api/settings — persists every section present in [update]
     * wholesale; a null section is omitted from the wire and leaves that
     * section untouched server-side. Carries only configuration: the auth
     * lane rides this class's normal credential ladder, and the body itself
     * never contains the local app's credentials (the write-only secrets in
     * a fetched document are blank, and a blank echo is the server's
     * keep-stored-value contract).
     */
    suspend fun putSettings(update: SettingsUpdate): CommandResponse =
        decode(send(Verb.PUT, "/api/settings", update))
}

/**
 * The cancellable suspension point for one OkHttp call: resumes with the
 * response on success, the failure on error, and cancels the underlying call
 * (closing the socket) when the calling coroutine is cancelled — leaving a
 * device screen mid-fetch kills the connection instead of leaking it.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

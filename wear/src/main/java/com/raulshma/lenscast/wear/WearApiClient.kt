package com.raulshma.lenscast.wear

import android.graphics.BitmapFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's HTTP API, one OkHttp client: status polls, stream/photo
 * commands, the snapshot JPEG, and the settings test. All calls suspend on
 * Dispatchers.IO and are cancellable end-to-end — each enqueued call is
 * wired to the coroutine's cancellation, so leaving the screen mid-fetch
 * kills the socket instead of leaking it (see [Call.await]).
 *
 * Auth mirrors the phone's WebAuthGate ladder per request from the live
 * [WearSettingsStore] snapshot: token mode sends `X-Api-Token`, basic mode
 * sends RFC 7617 credentials. Timeouts are tight (watch radio, LAN hop):
 * 3s connect / 8s read, so a dead phone surfaces as an error state in
 * seconds, never a hung spinner.
 *
 * JSON stays on the platform org.json parser — the payloads are two small
 * documents, and the module deliberately skips a reflection codec (Moshi)
 * to keep the watch APK lean.
 */
class WearApiClient(private val settingsStore: WearSettingsStore) {

    /** The shared client; per-call variance is expressed in the Request. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * GET /api/status → the decoded poll. Throws [IOException]-family errors
     * upward; the controller is the translator into [RequestState.Error].
     */
    suspend fun fetchStatus(): WearStatus = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val body = executeForText(requestBuilder(WearRequestUrls.status(settings.host, settings.port), settings).build())
        val json = JSONObject(body)
        val streaming = json.optJSONObject("streaming") ?: JSONObject()
        val battery = json.optJSONObject("battery") ?: JSONObject()
        WearStatus(
            streamingActive = streaming.optBoolean("isActive", false),
            clientCount = streaming.optInt("clientCount", 0),
            batteryLevel = battery.optInt("level", 0),
            batteryCharging = battery.optBoolean("isCharging", false),
            cameraState = json.optString("camera", "unknown"),
            thermalState = json.optString("thermal", "unknown"),
        )
    }

    /**
     * GET /api/system → device model + app version for the test verdict.
     * Null on any failure: the test already succeeded on /api/status, so the
     * identity line is a bonus, never the verdict.
     */
    suspend fun fetchSystemInfo(): WearSystemInfo? = runCatching {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.current()
            val json = JSONObject(
                executeForText(requestBuilder(WearRequestUrls.system(settings.host, settings.port), settings).build())
            )
            WearSystemInfo(
                deviceModel = json.optString("deviceModel", ""),
                appVersion = json.optString("appVersion", ""),
            )
        }
    }.getOrNull()

    /** POST /api/stream/start — success flag decoded from the 200 payload. */
    suspend fun startStream(): CommandResult = post(WearRequestUrls::streamStart)

    /** POST /api/stream/stop — the toggle's other half. */
    suspend fun stopStream(): CommandResult = post(WearRequestUrls::streamStop)

    /** POST /api/capture — one photo through the phone's capture pipeline. */
    suspend fun capturePhoto(): CommandResult = post(WearRequestUrls::capture)

    /**
     * GET /snapshot → the current preview frame, decoded off the JPEG bytes.
     * The frame timestamp is the server's Last-Modified when present (the
     * phone stamps the last rendered frame), else the wall-clock instant the
     * bytes arrived — the header check keeps an honest "frame age" either way.
     */
    suspend fun fetchSnapshot(): SnapshotFrame = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val (bytes, lastModifiedMs) = executeForBytes(
            requestBuilder(WearRequestUrls.snapshot(settings.host, settings.port), settings).build()
        )
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Snapshot is not a decodable image")
        SnapshotFrame(
            bitmap = bitmap,
            fetchedAtMs = lastModifiedMs ?: System.currentTimeMillis(),
        )
    }

    /**
     * The settings test: /api/status is the verdict (any 200 body means
     * reachable + authorized), /api/system is the identity bonus line.
     */
    suspend fun testConnection(): Pair<WearStatus, WearSystemInfo?> =
        fetchStatus() to fetchSystemInfo()

    // ── Internals ──

    /**
     * One POST with the shared command decoding: LensCast's JSON contract
     * answers 200 with the outcome in the payload (`success` / `error`), so
     * both layers are surfaced to the UI.
     */
    private suspend fun post(urlFrom: (host: String, port: Int) -> String): CommandResult =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.current()
            val request = requestBuilder(urlFrom(settings.host, settings.port), settings)
                .post(EMPTY_BODY.toRequestBody())
                .build()
            val body = executeForText(request)
            val json = runCatching { JSONObject(body) }.getOrNull()
            CommandResult(
                success = json?.optBoolean("success", true) ?: true,
                error = json?.optString("error")?.takeIf { it.isNotEmpty() },
            )
        }

    /** Adds the auth presentation for the current settings to a request builder. */
    private fun requestBuilder(url: String, settings: WearSettings): Request.Builder =
        Request.Builder().url(url).apply {
            when (settings.authMode) {
                AuthMode.API_TOKEN -> header("X-Api-Token", settings.apiToken)
                AuthMode.BASIC -> {
                    val credentials = Base64.getEncoder().encodeToString(
                        "${settings.username}:${settings.password}".toByteArray(Charsets.UTF_8)
                    )
                    header("Authorization", "Basic $credentials")
                }
            }
        }

    /** Executes the call, hands back the body string, always closes the response. */
    private suspend fun executeForText(request: Request): String {
        val (bodyBytes, _) = executeForBytes(request)
        return String(bodyBytes, Charsets.UTF_8)
    }

    /**
     * The single execution seam: enqueue + suspend, cancellation aborts the
     * in-flight call, non-2xx becomes an [IOException] with the code, and
     * the response is closed before resumption. The Last-Modified header
     * rides along (epoch ms) for the snapshot's frame timestamp; it is an
     * HTTP-date, decoded through the RFC 1123 formatter java.time ships —
     * safe at minSdk 26, null when absent or unparseable.
     */
    private suspend fun executeForBytes(request: Request): Pair<ByteArray, Long?> {
        val call = http.newCall(request)
        val response = call.await()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            // OkHttp 4's body is nullable; an empty 2xx body decodes as empty.
            val bytes = it.body?.bytes() ?: ByteArray(0)
            val lastModifiedMs = it.header("Last-Modified")?.let(::parseHttpDate)
            return bytes to lastModifiedMs
        }
    }

    /** RFC 1123 HTTP-date → epoch ms, or null when absent/unparseable. */
    private fun parseHttpDate(raw: String): Long? = runCatching {
        java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private companion object {
        const val CONNECT_TIMEOUT_S = 3L
        const val READ_TIMEOUT_S = 8L
        const val CALL_TIMEOUT_S = 12L
        const val EMPTY_BODY = ""
    }
}

/**
 * The cancellable suspension point for one OkHttp call: resumes with the
 * response on success, the failure on error, and cancels the underlying
 * call (closing the socket) when the calling coroutine is cancelled.
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

package com.raulshma.lenscast.streaming

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.HttpResult.ResponseBody
import com.raulshma.lenscast.streaming.web.ApiMethod
import com.raulshma.lenscast.streaming.web.ApiRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * The HTTP transport: a thin dispatch table over deep responder modules.
 * Auth translation lives in [HttpAuthFilter], static assets in
 * [StaticAssetStore], the MJPEG pump in [MjpegStreamPump], and media /
 * snapshots / audio in [MediaResponder] — all received or built here at
 * construction, never grown per request. The `/api/` JSON routes stay on
 * the [WebApiStack] seam below.
 */
class StreamingServer(
    private val port: Int = StreamDefaults.WEB_PORT,
    context: Context,
    audioStreamingManager: AudioStreamingManager,
    // Received at the seam, never manufactured here: the transport layer must
    // not grow its own Web API modules per instance.
    private val webApi: WebApiStack,
    networkQualityMonitor: NetworkQualityMonitor,
    // Auth policy (sessions, rate limiting, CSRF) lives behind this seam; the
    // transport only translates requests to it.
    webAuthGate: WebAuthGate,
) : NanoHTTPD(port) {

    private val authFilter = HttpAuthFilter(webAuthGate, port)
    private val assetStore = StaticAssetStore(context)
    private val mjpegPump = MjpegStreamPump(networkQualityMonitor, BOUNDARY_MARKER)
    private val mediaResponder = MediaResponder(
        webApi.gallery,
        webApi.capture,
        audioStreamingManager,
        com.raulshma.lenscast.streaming.hls.HlsManager,
    )
    private val audioManager = audioStreamingManager

    private var isRunning = false

    fun updateFrame(jpegData: ByteArray) = mjpegPump.updateFrame(jpegData)

    fun setWebStreamingEnabled(enabled: Boolean) = mjpegPump.setEnabled(enabled)

    fun getClientCount(): Int = mjpegPump.getClientCount()

    fun httpClientIds(): List<String> = mjpegPump.clientIds()

    fun kickHttpClient(clientId: String): Boolean = mjpegPump.kickClient(clientId)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.substringBefore("?")
        val method = session.method

        if (authFilter.isLoginRoute(method.name, uri)) {
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val body = readRequestBody(session, LOGIN_BODY_MAX_BYTES)
                ?: return translate(tooLargeResult(LOGIN_BODY_MAX_BYTES)).apply { addSecurityHeaders() }
            val result = authFilter.handleLogin(
                remoteIp = session.remoteIpAddress,
                contentLength = contentLength.toInt(),
                body = body,
            )
            return translate(result).apply { addSecurityHeaders() }
        }

        authFilter.handleBodylessAuthRoute(method.name, uri, session.headers)?.let {
            return translate(it).apply { addSecurityHeaders() }
        }

        if (!authFilter.isProtectedRoute(uri)) {
            // Public static assets deliberately skip the security headers,
            // preserving the client's caching contract.
            return translateStatic(assetStore.load(uri), secure = false)
        }

        authFilter.authorize(method.name, uri, session.headers)?.let {
            return translate(it).apply { addSecurityHeaders() }
        }

        val response = when {
            uri == "/stream" -> translate(mjpegPump.openStream())
            uri == "/audio" -> translate(
                mediaResponder.serveAudio(mjpegPump.isEnabled()),
            )
            // Half-duplex talkback uplink: binary PCM16 mono bypasses the JSON router.
            uri == "/api/audio/uplink" && method == Method.POST -> {
                val pcm = readRequestBody(session, MAX_BODY_BYTES)
                    ?: return translate(tooLargeResult(MAX_BODY_BYTES)).apply { addSecurityHeaders() }
                val ok = try {
                    audioManager.playUplink(pcm)
                } catch (_: Exception) {
                    false
                }
                translate(if (ok) HttpResult.jsonError(200, "Talkback played") else HttpResult.jsonError(503, "Speaker unavailable"))
            }
            uri == "/hls/playlist.m3u8" -> translate(
                mediaResponder.serveHlsPlaylist(mjpegPump.isEnabled()),
            )
            uri.startsWith("/hls/seg") && uri.endsWith(".ts") -> translate(
                mediaResponder.serveHlsSegment(
                    name = uri.substringAfterLast("/"),
                    enabled = mjpegPump.isEnabled(),
                ),
            )
            uri.startsWith("/snapshot") -> translate(
                mediaResponder.serveSnapshot(
                    query = session.queryParameterString,
                    latestFrame = mjpegPump.latestFrame(),
                    enabled = mjpegPump.isEnabled(),
                ),
            )
            uri.startsWith("/api/media/") && method == Method.GET -> translate(
                mediaResponder.serveMediaFile(
                    uri = uri,
                    hasDownloadParam = session.parameters?.containsKey("download") == true,
                    rangeHeader = session.headers["range"],
                ),
            )
            uri.startsWith("/api/") -> handleApiRoute(uri, method, session)
            else -> return translateStatic(assetStore.load(uri), secure = true)
        }

        response.addSecurityHeaders()
        return response
    }

    private fun translate(result: HttpResult): Response {
        val status = Response.Status.lookup(result.statusCode) ?: Response.Status.OK
        val response = when (val body = result.body) {
            is ResponseBody.Text -> newFixedLengthResponse(status, result.mimeType, body.text)
            is ResponseBody.Bytes -> newFixedLengthResponse(
                status,
                result.mimeType,
                ByteArrayInputStream(body.bytes),
                body.bytes.size.toLong(),
            )
            is ResponseBody.Stream -> if (body.contentLength != null) {
                newFixedLengthResponse(status, result.mimeType, body.stream, body.contentLength)
            } else {
                newChunkedResponse(status, result.mimeType, body.stream)
            }
        }
        result.headers.forEach { (name, value) -> response.addHeader(name, value) }
        return response
    }

    private fun translateStatic(asset: StaticAssetStore.StaticAsset, secure: Boolean): Response {
        val response = when (asset) {
            StaticAssetStore.StaticAsset.InvalidPath -> newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Invalid asset path",
            )
            is StaticAssetStore.StaticAsset.Found -> {
                val streamResponse = newFixedLengthResponse(
                    Response.Status.OK,
                    asset.mimeType,
                    ByteArrayInputStream(asset.bytes),
                    asset.bytes.size.toLong(),
                )
                if (asset.noStore) {
                    HttpResult.NO_STORE_HEADERS.forEach { (name, value) ->
                        streamResponse.addHeader(name, value)
                    }
                } else {
                    streamResponse.addHeader("Cache-Control", "no-cache")
                }
                streamResponse
            }
            is StaticAssetStore.StaticAsset.FallbackPage -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                asset.html,
            )
        }
        // The fallback page carries its own security headers on every path.
        if (secure || asset is StaticAssetStore.StaticAsset.FallbackPage) {
            response.addSecurityHeaders()
        }
        return response
    }

    private fun Response.addSecurityHeaders() {
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("X-XSS-Protection", "1; mode=block")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader(
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=()"
        )
        addHeader(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; " +
                "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; " +
                "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
        )
    }

    private fun handleApiRoute(uri: String, method: Method, session: IHTTPSession): Response {
        val body = readRequestBody(session, MAX_BODY_BYTES)?.toString(Charsets.UTF_8)
            ?: return translate(tooLargeResult(MAX_BODY_BYTES))

        val apiMethod = when (method) {
            Method.GET -> ApiMethod.GET
            Method.PUT -> ApiMethod.PUT
            Method.POST -> ApiMethod.POST
            Method.DELETE -> ApiMethod.DELETE
            // Unknown /api route with an unhandled method — same 404 the
            // router returns, preserving the client's contract.
            else -> return translate(HttpResult.jsonError(404, "Not found"))
        }
        val query = session.parameters?.mapValues { it.value.firstOrNull() ?: "" } ?: emptyMap()

        // The single place the transport blocks on a handler: this dedicated
        // server thread awaits the suspend router with a bounded timeout so a
        // slow Capture/Gallery handler can't stall all /api/* workers.
        val response = try {
            runBlocking {
                withTimeout(API_DISPATCH_TIMEOUT_MS) {
                    webApi.router.dispatch(ApiRequest(method = apiMethod, path = uri, body = body, query = query))
                }
            }
        } catch (_: TimeoutCancellationException) {
            android.util.Log.w(TAG, "API dispatch timed out: $uri")
            return translate(HttpResult.jsonError(504, "Handler timed out"))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "API dispatch failed: $uri", e)
            return translate(HttpResult.jsonError(500, "Internal handler error"))
        }

        return newFixedLengthResponse(
            Response.Status.lookup(response.httpStatus) ?: Response.Status.OK,
            response.contentType,
            response.body
        )
    }

    /** The 413 answer for a body beyond its route's cap — the one `{"error":…}` shape, via [HttpResult]. */
    private fun tooLargeResult(capBytes: Long): HttpResult =
        HttpResult.jsonError(413, "Request body too large (max ${describeCap(capBytes)})")

    private fun describeCap(capBytes: Long): String = when {
        capBytes >= 1024 * 1024 && capBytes % (1024 * 1024) == 0L -> "${capBytes / (1024 * 1024)}MB"
        capBytes >= 1024 && capBytes % 1024 == 0L -> "${capBytes / 1024}KB"
        else -> "${capBytes}B"
    }

    /**
     * The one capped request-body reader, shared by the login and API paths.
     * Reads the declared Content-Length in a loop — a single `read` short-reads
     * under TCP scheduling and silently truncates the body — and fails closed
     * with null when the declared length exceeds [capBytes], leaving the error
     * response to the caller. EOF or an IO error mid-body returns whatever was
     * read so far; downstream parsing fails the request exactly as before.
     */
    private fun readRequestBody(session: IHTTPSession, capBytes: Long): ByteArray? {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > capBytes) {
            Log.w(TAG, "Request body too large: $contentLength bytes (max $capBytes)")
            return null
        }
        if (contentLength <= 0L) return ByteArray(0)
        return readCappedBody(contentLength, capBytes, session.inputStream)
    }

    /** The pure loop behind [readRequestBody] — [contentLength] must already be within [capBytes]. */
    internal fun readCappedBody(contentLength: Long, capBytes: Long, input: java.io.InputStream): ByteArray {
        val body = ByteArray(contentLength.toInt())
        var total = 0
        try {
            while (total < body.size) {
                val read = input.read(body, total, body.size - total)
                if (read < 0) break
                total += read
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read request body", e)
        }
        return body
    }

    fun startServer(): Boolean {
        return try {
            start()
            isRunning = true
            Log.d(TAG, "Streaming server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            false
        }
    }

    fun stopServer() {
        if (isRunning) {
            stop()
            isRunning = false
            // Sessions live in the manager-owned WebAuthGate — they survive a
            // server recreation (e.g. a port change) by design.
            Log.d(TAG, "Streaming server stopped")
        }
    }

    companion object {
        private const val TAG = "StreamingServer"
        const val BOUNDARY_MARKER = "LensCastBoundary"
        private const val MAX_BODY_BYTES = 1L * 1024 * 1024
        /** Bounded wait for a suspend handler so one slow route can't wedge the NanoHTTPD pool. */
        private const val API_DISPATCH_TIMEOUT_MS = 10_000L

        /**
         * The credential-body cap for the login route: the JSON login body is
         * two short strings, so 64 KiB is orders of magnitude above any real
         * request while keeping an attacker's allocation bounded.
         */
        private const val LOGIN_BODY_MAX_BYTES = 64L * 1024
    }
}

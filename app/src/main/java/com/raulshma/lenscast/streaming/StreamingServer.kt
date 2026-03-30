package com.raulshma.lenscast.streaming

import android.content.Context
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StreamingServer(
    private val port: Int = 8080,
    private val context: Context? = null,
    private val audioStreamingManager: AudioStreamingManager? = null,
) : NanoHTTPD(port) {

    private val boundary = "LensCastBoundary"
    private val clientCount = AtomicInteger(0)
    private var latestJpeg: ByteArray? = null
    private val frameLock = Object()
    private var latestFrameVersion = 0L
    private var isRunning = false
    var authUsername: String? = null
    var authPasswordHash: String? = null

    private val apiController: WebApiController? = context?.let { WebApiController(it) }

    private val assetCache = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    private val sessions = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()

    private val fallbackControlPageHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>LensCast - IPTV Camera</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body { font-family: sans-serif; max-width: 600px; margin: 40px auto; padding: 0 20px; background: #1a1a2e; color: #e0e0e0; }
            h1 { color: #64b5f6; }
            a { color: #81d4fa; display: block; margin: 10px 0; padding: 12px; background: #16213e; border-radius: 8px; text-decoration: none; }
            a:hover { background: #0f3460; }
            .info { color: #aaa; font-size: 14px; margin-top: 20px; }
        </style>
        </head>
        <body>
            <h1>LensCast Camera</h1>
            <a href="/stream">MJPEG Stream</a>
            <a href="/audio">AAC Audio Stream</a>
            <a href="/snapshot">Snapshot</a>
            <p class="info">Stream URL: /stream | Audio: /audio | Snapshot: /snapshot | API: /api/settings</p>
        </body>
        </html>
    """.trimIndent()

    fun updateFrame(jpegData: ByteArray) {
        synchronized(frameLock) {
            latestJpeg = jpegData
            latestFrameVersion++
            frameLock.notifyAll()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        if (uri == "/api/auth/status") {
            val isAuthEnabled = authUsername != null && !authUsername.isNullOrEmpty() && authPasswordHash != null
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"required":$isAuthEnabled}"""
            ).apply { addSecurityHeaders() }
        }

        if (uri == "/api/auth/logout" && session.method == Method.POST) {
            val cookieHeader = session.headers["cookie"] ?: ""
            val token = extractCookie(cookieHeader, "lenscast_session")
            if (token != null) {
                sessions.remove(token)
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"success":true}"""
            ).apply {
                addHeader("Set-Cookie", "lenscast_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")
                addSecurityHeaders()
            }
        }

        val authResult = checkAuth(session)
        if (!authResult.authorized) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "text/plain",
                "Unauthorized"
            ).apply {
                addHeader("WWW-Authenticate", "Basic realm=\"LensCast\"")
                addSecurityHeaders()
            }
        }

        val method = session.method

        val response = when {
            uri == "/stream" -> serveMjpegStream()
            uri == "/audio" -> serveAudioStream()
            uri == "/snapshot" -> serveSnapshot()
            uri.startsWith("/api/media/") -> serveMediaFile(uri, session)
            uri.startsWith("/api/") -> handleApiRoute(uri, method, session)
            else -> serveStaticFile(uri)
        }

        if (authResult.viaBasicAuth) {
            val token = createSession()
            response.addHeader(
                "Set-Cookie",
                "lenscast_session=$token; Path=/; Max-Age=$SESSION_MAX_AGE_SEC; HttpOnly; SameSite=Lax"
            )
        }

        response.addSecurityHeaders()
        return response
    }

    private fun Response.addSecurityHeaders() {
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("X-XSS-Protection", "1; mode=block")
        addHeader("Referrer-Policy", "no-referrer")
    }

    private data class AuthResult(val authorized: Boolean, val viaBasicAuth: Boolean)

    private fun checkAuth(session: IHTTPSession): AuthResult {
        val username = authUsername ?: return AuthResult(true, false)
        val storedHash = authPasswordHash ?: return AuthResult(true, false)
        if (username.isEmpty()) return AuthResult(true, false)

        val cookieHeader = session.headers["cookie"] ?: ""
        val sessionToken = extractCookie(cookieHeader, "lenscast_session")
        if (sessionToken != null && validateSession(sessionToken)) {
            return AuthResult(true, false)
        }

        val authHeader = session.headers["authorization"] ?: return AuthResult(false, false)
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return AuthResult(false, false)

        val encoded = authHeader.substring(6).trim()
        val decoded: String
        try {
            decoded = String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: Exception) {
            return AuthResult(false, false)
        }

        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return AuthResult(false, false)
        if (!constantTimeEquals(parts[0], username)) return AuthResult(false, false)

        val incomingHash = sha256Base64(parts[1])
        return if (constantTimeEquals(incomingHash, storedHash)) {
            AuthResult(true, true)
        } else {
            AuthResult(false, false)
        }
    }

    private fun createSession(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessions[token] = System.currentTimeMillis() + SESSION_DURATION_MS
        return token
    }

    private fun validateSession(token: String): Boolean {
        val expiry = sessions[token] ?: return false
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(token)
            return false
        }
        return true
    }

    private fun extractCookie(cookieHeader: String, name: String): String? {
        return cookieHeader.split(";")
            .map { it.trim() }
            .find { it.startsWith("$name=") }
            ?.substring(name.length + 1)
    }

    private fun sha256Base64(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun handleApiRoute(uri: String, method: Method, session: IHTTPSession): Response {
        val controller = apiController
        if (controller == null) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"API not available"}"""
            )
        }

        val body = readBody(session)
        val json = when {
            method == Method.GET && uri == "/api/settings" -> controller.handleGetSettings()
            method == Method.PUT && uri == "/api/settings" -> controller.handlePutSettings(body)
            method == Method.POST && uri == "/api/settings" -> controller.handlePutSettings(body)
            method == Method.GET && uri == "/api/status" -> controller.handleGetStatus()
            method == Method.POST && uri == "/api/stream/start" -> controller.handleStartStream()
            method == Method.POST && uri == "/api/stream/stop" -> controller.handleStopStream()
            method == Method.POST && uri == "/api/stream/resume" -> controller.handleStartStream()
            method == Method.POST && uri == "/api/capture" -> controller.handleCapture()
            method == Method.GET && uri == "/api/camera/lenses" -> controller.handleGetLenses()
            method == Method.PUT && uri == "/api/camera/lens" -> controller.handleSelectLens(body)
            method == Method.POST && uri == "/api/camera/lens" -> controller.handleSelectLens(body)
            method == Method.GET && uri == "/api/capture/interval/status" -> controller.handleGetIntervalCaptureStatus()
            method == Method.POST && uri == "/api/capture/interval/start" -> controller.handleStartIntervalCapture(body)
            method == Method.POST && uri == "/api/capture/interval/stop" -> controller.handleStopIntervalCapture()
            method == Method.GET && uri == "/api/recording/status" -> controller.handleGetRecordingStatus()
            method == Method.POST && uri == "/api/recording/start" -> controller.handleStartRecording(body)
            method == Method.POST && uri == "/api/recording/stop" -> controller.handleStopRecording()
            method == Method.GET && uri == "/api/gallery" -> {
                val type = session.parameters?.get("type")?.firstOrNull()
                controller.handleGetGallery(type)
            }
            uri.startsWith("/api/media/") && method == Method.DELETE -> {
                val id = uri.removePrefix("/api/media/")
                controller.handleDeleteMedia(id)
            }
            uri == "/api/media/batch-delete" && method == Method.POST -> {
                controller.handleBatchDeleteMedia(body)
            }
            else -> null
        }

        return if (json != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json", json)
        } else {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error":"Not found"}"""
            )
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 0L) return ""
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"]
                ?: files["content"]?.let { java.io.File(it).readText() }
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read request body", e)
            ""
        }
    }

    private fun serveStaticFile(uri: String): Response {
        val assetMgr = context?.assets ?: return serveFallbackControlPage()

        val path = if (uri == "/" || uri == "") "webui/index.html" else "webui$uri"

        return try {
            val cached = assetCache[path]
            val bytes = cached?.first ?: assetMgr.open(path).use { it.readBytes() }
                .also { assetCache[path] = Pair(it, getMimeType(path)) }
            val mimeType = cached?.second ?: getMimeType(path)
            val response = newFixedLengthResponse(
                Response.Status.OK, mimeType,
                ByteArrayInputStream(bytes), bytes.size.toLong()
            )
            val maxAge = if (path == "webui/index.html") 0 else 86400
            response.addHeader("Cache-Control", "public, max-age=$maxAge")
            response
        } catch (_: Exception) {
            if (path != "webui/index.html") {
                try {
                    val indexPath = "webui/index.html"
                    val cached = assetCache[indexPath]
                    val bytes = cached?.first ?: assetMgr.open(indexPath).use { it.readBytes() }
                        .also { assetCache[indexPath] = Pair(it, "text/html") }
                    val response = newFixedLengthResponse(
                        Response.Status.OK, "text/html",
                        ByteArrayInputStream(bytes), bytes.size.toLong()
                    )
                    response.addHeader("Cache-Control", "no-cache")
                    response
                } catch (_: Exception) {
                    serveFallbackControlPage()
                }
            } else {
                serveFallbackControlPage()
            }
        }
    }

    private fun serveMediaFile(uri: String, session: IHTTPSession): Response {
        val controller = apiController ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "application/json",
            """{"error":"API not available"}"""
        )

        val id = uri.removePrefix("/api/media/")
        if (id.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"Missing media ID"}"""
            )
        }

        val resolved = controller.resolveMediaFile(id)
        if (resolved == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"Media not found"}"""
            )
        }

        val (inputStream, mimeType) = resolved
        val download = session.parameters?.containsKey("download") == true
        val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        if (download) {
            response.addHeader("Content-Disposition", "attachment")
        }
        return response
    }

    private fun serveFallbackControlPage(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html", fallbackControlPageHtml)
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun serveMjpegStream(): Response {
        val clientNum = clientCount.incrementAndGet()
        Log.d(TAG, "Client connected. Total: $clientNum")

        val stream = object : InputStream() {
            private var currentFrame: ByteArray? = null
            private var currentFrameVersion = -1L
            private var frameOffset = 0
            private var headerBytes = ByteArray(0)
            private var headerOffset = 0
            private var footerOffset = 0
            private var isFirstPart = true
            private val footerBytes = "\r\n".toByteArray()
            @Volatile
            private var closed = false

            override fun read(): Int {
                val buf = ByteArray(1)
                val n = read(buf, 0, 1)
                return if (n <= 0) -1 else buf[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed) return -1
                if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
                if (len == 0) return 0

                var totalRead = 0
                while (totalRead < len) {
                    if (!ensureCurrentPart()) {
                        return if (totalRead > 0) totalRead else -1
                    }

                    val writtenHeader = copyChunk(
                        source = headerBytes,
                        sourceOffset = headerOffset,
                        target = b,
                        targetOffset = off + totalRead,
                        maxLength = len - totalRead,
                    )
                    headerOffset += writtenHeader
                    totalRead += writtenHeader
                    if (totalRead == len) break

                    val frame = currentFrame ?: continue
                    val writtenFrame = copyChunk(
                        source = frame,
                        sourceOffset = frameOffset,
                        target = b,
                        targetOffset = off + totalRead,
                        maxLength = len - totalRead,
                    )
                    frameOffset += writtenFrame
                    totalRead += writtenFrame
                    if (totalRead == len) break

                    val writtenFooter = copyChunk(
                        source = footerBytes,
                        sourceOffset = footerOffset,
                        target = b,
                        targetOffset = off + totalRead,
                        maxLength = len - totalRead,
                    )
                    footerOffset += writtenFooter
                    totalRead += writtenFooter

                    if (headerOffset >= headerBytes.size &&
                        frameOffset >= frame.size &&
                        footerOffset >= footerBytes.size
                    ) {
                        currentFrame = null
                    }
                }

                return totalRead
            }

            private fun ensureCurrentPart(): Boolean {
                if (closed) return false
                val frame = currentFrame
                if (frame != null && (
                    headerOffset < headerBytes.size ||
                        frameOffset < frame.size ||
                        footerOffset < footerBytes.size
                    )
                ) {
                    return true
                }

                synchronized(frameLock) {
                    while (!closed) {
                        val nextFrame = latestJpeg
                        if (nextFrame != null && latestFrameVersion != currentFrameVersion) {
                            currentFrame = nextFrame
                            currentFrameVersion = latestFrameVersion
                            frameOffset = 0
                            footerOffset = 0
                            headerOffset = 0
                            headerBytes = buildHeader(nextFrame.size, isFirstPart).toByteArray()
                            isFirstPart = false
                            return true
                        }
                        frameLock.wait(250)
                    }
                }

                return false
            }

            private fun buildHeader(frameSize: Int, isFirst: Boolean): String {
                val prefix = if (isFirst) "--" else "\r\n--"
                return "$prefix$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: $frameSize\r\n\r\n"
            }

            private fun copyChunk(
                source: ByteArray,
                sourceOffset: Int,
                target: ByteArray,
                targetOffset: Int,
                maxLength: Int,
            ): Int {
                if (sourceOffset >= source.size || maxLength <= 0) return 0
                val copyLength = minOf(source.size - sourceOffset, maxLength)
                System.arraycopy(source, sourceOffset, target, targetOffset, copyLength)
                return copyLength
            }

            override fun close() {
                closed = true
                synchronized(frameLock) { frameLock.notifyAll() }
                val num = clientCount.decrementAndGet()
                Log.d(TAG, "Client disconnected. Total: $num")
            }
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            stream
        ).apply {
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun serveSnapshot(): Response {
        val jpeg = latestJpeg
        return if (jpeg != null) {
            newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(jpeg), jpeg.size.toLong()
            ).apply {
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")
            }
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No frame available")
        }
    }

    private fun serveAudioStream(): Response {
        val audioStream = audioStreamingManager?.openStream()
        return if (audioStream != null) {
            newChunkedResponse(
                Response.Status.OK,
                "application/octet-stream",
                audioStream
            ).apply {
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")
                addHeader("X-Accel-Buffering", "no")
                addHeader("X-Audio-Format", "pcm_s16le")
                addHeader("X-Audio-Sample-Rate", "${audioStreamingManager?.getSampleRateHz() ?: 48000}")
                addHeader("X-Audio-Channels", "${audioStreamingManager?.getChannelCount() ?: 1}")
            }
        } else {
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Audio stream not available"
            )
        }
    }

    fun getClientCount(): Int = clientCount.get()

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
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
            sessions.clear()
            Log.d(TAG, "Streaming server stopped")
        }
    }

    companion object {
        private const val TAG = "StreamingServer"
        private const val SESSION_DURATION_MS = 24 * 60 * 60 * 1000L
        private const val SESSION_MAX_AGE_SEC = 24 * 60 * 60
    }
}

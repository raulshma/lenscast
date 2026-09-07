package com.raulshma.lenscast.streaming

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.streaming.web.ApiMethod
import com.raulshma.lenscast.streaming.web.ApiRequest
import kotlinx.coroutines.runBlocking
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class StreamingServer(
    private val port: Int = StreamDefaults.WEB_PORT,
    private val context: Context,
    private val audioStreamingManager: AudioStreamingManager,
    // Received at the seam, never manufactured here: the transport layer must
    // not grow its own Web API modules per instance.
    private val webApi: WebApiStack,
    private val networkQualityMonitor: NetworkQualityMonitor,
    // Auth policy (sessions, rate limiting, CSRF) lives behind this seam; the
    // transport only translates requests to it.
    private val webAuthGate: WebAuthGate,
) : NanoHTTPD(port) {

    private val boundary = BOUNDARY_MARKER
    private val clientCount = AtomicInteger(0)
    private val clientCounter = AtomicInteger(0)
    @Volatile private var latestJpeg: ByteArray? = null
    private val frameLock = Object()
    private var latestFrameVersion = 0L
    private var isRunning = false
    @Volatile private var webStreamingEnabled = true

    private val precomputedMjpegHeaderFirst = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ".toByteArray()
    private val precomputedMjpegHeaderSubsequent = "\r\n--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ".toByteArray()
    private val precomputedMjpegHeaderSuffix = "\r\n\r\n".toByteArray()
    private val precomputedMjpegFooter = "\r\n".toByteArray()

    private val assetCache = object : LinkedHashMap<String, Pair<ByteArray, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String>>): Boolean {
            return size > MAX_CACHED_ASSETS
        }
    }

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
        val uri = session.uri.substringBefore("?")
        val method = session.method

        if (uri == "/api/auth/status") {
            val isAuthEnabled = webAuthGate.isEnabled
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"required":$isAuthEnabled}"""
            ).apply {
                addHeader("Cache-Control", "no-store")
                addSecurityHeaders()
            }
        }

        if (uri == "/api/auth/login" && method == Method.POST) {
            if (!webAuthGate.isEnabled) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"required":false}"""
                ).apply { addHeader("Cache-Control", "no-store"); addSecurityHeaders() }
            }
            val loginBody = try {
                val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0) return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"Missing request body"}"""
                ).apply { addSecurityHeaders() }
                val buf = ByteArray(contentLength)
                session.inputStream.read(buf)
                val json = String(buf, Charsets.UTF_8)
                val parsed = org.json.JSONObject(json)
                val username = parsed.optString("username", "")
                val password = parsed.optString("password", "")
                if (username.isEmpty() || password.isEmpty()) return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"Missing credentials"}"""
                ).apply { addSecurityHeaders() }
                Pair(username, password)
            } catch (_: Exception) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"Invalid request"}"""
                ).apply { addSecurityHeaders() }
            }
            val result = webAuthGate.login(session.remoteIpAddress, loginBody.first, loginBody.second)
            if (!result.success) {
                val status = when (result.error) {
                    "Auth not configured" -> Response.Status.INTERNAL_ERROR
                    else -> Response.Status.UNAUTHORIZED
                }
                return newFixedLengthResponse(
                    status, "application/json", """{"error":"${result.error}"}"""
                ).apply { addSecurityHeaders() }
            }
            return newFixedLengthResponse(
                Response.Status.OK, "application/json", """{"success":true}"""
            ).apply {
                addHeader(
                    "Set-Cookie",
                    "${WebAuthGate.COOKIE_NAME}=${result.token}; Path=${WebAuthGate.COOKIE_PATH}; " +
                        "Max-Age=${WebAuthGate.SESSION_MAX_AGE_SEC}; HttpOnly; SameSite=Lax"
                )
                addHeader("Cache-Control", "no-store")
                addSecurityHeaders()
            }
        }

        if (uri == "/api/auth/session" && method == Method.GET) {
            val isValid = webAuthGate.authenticate(session.headers["cookie"])
            return newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"authenticated":$isValid}"""
            ).apply { addHeader("Cache-Control", "no-store"); addSecurityHeaders() }
        }

        if (uri == "/api/auth/logout" && method == Method.POST) {
            if (!webAuthGate.authenticate(session.headers["cookie"])) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"error":"Authentication required"}"""
                ).apply { addSecurityHeaders() }
            }
            if (!webAuthGate.isCsrfSafe(
                    originHeader = session.headers["origin"] ?: session.headers["referer"],
                    hasRequestedWithHeader = session.headers.containsKey("x-requested-with"),
                    port = port,
                )
            ) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "application/json",
                    """{"error":"CSRF check failed"}"""
                ).apply { addSecurityHeaders() }
            }

            webAuthGate.logout(webAuthGate.tokenFromCookie(session.headers["cookie"]))
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"success":true}"""
            ).apply {
                addHeader(
                    "Set-Cookie",
                    "${WebAuthGate.COOKIE_NAME}=; Path=${WebAuthGate.COOKIE_PATH}; Max-Age=0; HttpOnly; SameSite=Lax"
                )
                addHeader("Cache-Control", "no-store")
                addSecurityHeaders()
            }
        }

        val isProtectedRoute = uri.startsWith("/api/") || uri == "/stream" || uri == "/audio" || uri.startsWith("/snapshot")

        if (!isProtectedRoute) {
            return serveStaticFile(uri)
        }

        if (!webAuthGate.authenticate(session.headers["cookie"])) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                """{"error":"Authentication required"}"""
            ).apply { addSecurityHeaders() }
        }

        if (method != Method.GET && !webAuthGate.isCsrfSafe(
                originHeader = session.headers["origin"] ?: session.headers["referer"],
                hasRequestedWithHeader = session.headers.containsKey("x-requested-with"),
                port = port,
            )
        ) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "application/json",
                """{"error":"CSRF check failed"}"""
            ).apply { addSecurityHeaders() }
        }

        val response = when {
            uri == "/stream" -> serveMjpegStream()
            uri == "/audio" -> serveAudioStream()
            uri.startsWith("/snapshot") -> serveSnapshot(session)
            uri.startsWith("/api/media/") && method == Method.GET -> serveMediaFile(uri, session)
            uri.startsWith("/api/") -> handleApiRoute(uri, method, session)
            else -> serveStaticFile(uri)
        }

        response.addSecurityHeaders()
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
        val body = readBody(session)
        if (body == null) {
            return newFixedLengthResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                "application/json",
                """{"error":"Request body too large (max ${MAX_BODY_BYTES / 1024 / 1024}MB)"}"""
            )
        }

        val apiMethod = when (method) {
            Method.GET -> ApiMethod.GET
            Method.PUT -> ApiMethod.PUT
            Method.POST -> ApiMethod.POST
            Method.DELETE -> ApiMethod.DELETE
            // Unknown /api route with an unhandled method — same 404 the
            // router returns, preserving the client's contract.
            else -> return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error":"Not found"}"""
            )
        }
        val query = session.parameters?.mapValues { it.value.firstOrNull() ?: "" } ?: emptyMap()

        // The single place the transport blocks on a handler: this dedicated
        // server thread awaits the suspend router instead of every handler
        // runBlocking-ing its way to the Main dispatcher.
        val response = runBlocking {
            webApi.router.dispatch(ApiRequest(method = apiMethod, path = uri, body = body, query = query))
        }

        return newFixedLengthResponse(
            Response.Status.lookup(response.httpStatus) ?: Response.Status.OK,
            response.contentType,
            response.body
        )
    }

    private fun readBody(session: IHTTPSession): String? {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 0L) return ""
        if (contentLength > MAX_BODY_BYTES) {
            Log.w(TAG, "Request body too large: $contentLength bytes (max $MAX_BODY_BYTES)")
            return null
        }
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
        val assetMgr = context.assets

        val path = resolveAssetPath(uri)
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Invalid asset path"
            )

        return try {
            val cached = assetCache[path]
            val bytes = cached?.first ?: assetMgr.open(path).use { it.readBytes() }
                .also { assetCache[path] = Pair(it, getMimeType(path)) }
            val mimeType = cached?.second ?: getMimeType(path)
            val response = newFixedLengthResponse(
                Response.Status.OK, mimeType,
                ByteArrayInputStream(bytes), bytes.size.toLong()
            )
            response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")
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
        val path = uri.removePrefix("/api/media/")
        if (path.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"Missing media ID"}"""
            )
        }

        // Handle /api/media/{id}/thumbnail
        if (path.endsWith("/thumbnail")) {
            val id = path.removeSuffix("/thumbnail")
            val thumbnailBytes = webApi.gallery.resolveVideoThumbnail(id)
            return if (thumbnailBytes != null) {
                newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(thumbnailBytes), thumbnailBytes.size.toLong()
                ).apply {
                    addHeader("Cache-Control", "public, max-age=3600")
                }
            } else {
                // Fallback: try to serve the media file itself (for photos)
                val resolved = webApi.gallery.resolveMediaFile(id)
                if (resolved != null) {
                    newChunkedResponse(Response.Status.OK, resolved.mimeType, resolved.stream)
                } else {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "application/json",
                        """{"error":"Thumbnail not available"}"""
                    )
                }
            }
        }

        val id = path
        val resolved = webApi.gallery.resolveMediaFile(id)
        if (resolved == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"Media not found"}"""
            )
        }

        val download = session.parameters?.containsKey("download") == true

        // For video files, support HTTP Range requests for proper playback
        if (resolved.mimeType.startsWith("video/") && !download) {
            val rangeHeader = session.headers["range"]
            return serveVideoWithRange(resolved.stream, resolved.mimeType, resolved.fileSizeBytes, rangeHeader)
        }

        val response = newChunkedResponse(Response.Status.OK, resolved.mimeType, resolved.stream)
        if (download) {
            response.addHeader("Content-Disposition", "attachment")
        }
        return response
    }

    private fun serveVideoWithRange(
        inputStream: InputStream,
        mimeType: String,
        totalSize: Long,
        rangeHeader: String?,
    ): Response {
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = rangeSpec.indexOf('-')
            if (dashIdx >= 0) {
                val startStr = rangeSpec.substring(0, dashIdx).trim()
                val endStr = rangeSpec.substring(dashIdx + 1).trim()
                val start = if (startStr.isNotEmpty()) startStr.toLongOrNull() ?: 0L else 0L
                val end = if (endStr.isNotEmpty()) {
                    (endStr.toLongOrNull() ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
                } else {
                    // Limit chunk size to 2MB to avoid excessive memory use
                    (start + 2 * 1024 * 1024 - 1).coerceAtMost(totalSize - 1)
                }
                val contentLength = end - start + 1
                
                var skipped = 0L
                while (skipped < start) {
                    val skippedThisTime = inputStream.skip(start - skipped)
                    if (skippedThisTime <= 0L) break
                    skipped += skippedThisTime
                }

                return newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mimeType,
                    inputStream, contentLength
                ).apply {
                    addHeader("Content-Range", "bytes $start-$end/$totalSize")
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Length", contentLength.toString())
                }
            }
        }

        // No range requested – serve full content
        return newFixedLengthResponse(
            Response.Status.OK, mimeType,
            inputStream, totalSize
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Length", totalSize.toString())
        }
    }

    private fun serveFallbackControlPage(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html", fallbackControlPageHtml)
            .apply { addSecurityHeaders() }
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

    fun setWebStreamingEnabled(enabled: Boolean) {
        webStreamingEnabled = enabled
    }

    private fun serveMjpegStream(): Response {
        if (!webStreamingEnabled) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Web streaming is disabled"
            )
        }

        val clientNum = clientCount.incrementAndGet()
        val clientId = "mjpeg_${clientCounter.incrementAndGet()}"
        Log.d(TAG, "Client connected: $clientId. Total: $clientNum")

        networkQualityMonitor.registerClient(clientId)

        val stream = object : InputStream() {
            private var currentFrame: ByteArray? = null
            private var currentFrameVersion = -1L
            private var frameOffset = 0
            private var headerBytes = ByteArray(0)
            private var headerOffset = 0
            private var footerOffset = 0
            private var isFirstPart = true
            @Volatile
            private var closed = false
            private var frameSendStartTime = 0L
            private var currentFrameTotalBytes = 0
            private val lengthBuffer = ByteArray(16)

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
                        source = precomputedMjpegFooter,
                        sourceOffset = footerOffset,
                        target = b,
                        targetOffset = off + totalRead,
                        maxLength = len - totalRead,
                    )
                    footerOffset += writtenFooter
                    totalRead += writtenFooter

                    if (headerOffset >= headerBytes.size &&
                        frameOffset >= currentFrame!!.size &&
                        footerOffset >= precomputedMjpegFooter.size
                    ) {
                        val sendDuration = System.currentTimeMillis() - frameSendStartTime
                        networkQualityMonitor.recordFrameSent(
                            clientId = clientId,
                            frameSizeBytes = currentFrameTotalBytes,
                            sendDurationMs = sendDuration,
                        )
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
                        footerOffset < precomputedMjpegFooter.size
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
                            val prefix = if (isFirstPart) precomputedMjpegHeaderFirst else precomputedMjpegHeaderSubsequent
                            val len = formatIntIntoBuffer(nextFrame.size)
                            headerBytes = ByteArray(prefix.size + len + precomputedMjpegHeaderSuffix.size)
                            System.arraycopy(prefix, 0, headerBytes, 0, prefix.size)
                            System.arraycopy(lengthBuffer, 0, headerBytes, prefix.size, len)
                            System.arraycopy(precomputedMjpegHeaderSuffix, 0, headerBytes, prefix.size + len, precomputedMjpegHeaderSuffix.size)
                            isFirstPart = false
                            currentFrameTotalBytes = nextFrame.size + headerBytes.size + precomputedMjpegFooter.size
                            frameSendStartTime = System.currentTimeMillis()
                            return true
                        }
                        frameLock.wait(250)
                    }
                }

                return false
            }

            private fun formatIntIntoBuffer(value: Int): Int {
                var v = value
                var pos = 0
                if (v == 0) {
                    lengthBuffer[pos++] = '0'.code.toByte()
                    return pos
                }
                val start = pos
                while (v > 0) {
                    lengthBuffer[pos++] = ('0'.code + (v % 10)).toByte()
                    v /= 10
                }
                reverse(lengthBuffer, start, pos - 1)
                return pos - start
            }

            private fun reverse(buf: ByteArray, start: Int, end: Int) {
                var i = start
                var j = end
                while (i < j) {
                    val tmp = buf[i]
                    buf[i] = buf[j]
                    buf[j] = tmp
                    i++
                    j--
                }
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
                networkQualityMonitor.unregisterClient(clientId)
                val num = clientCount.decrementAndGet()
                Log.d(TAG, "Client disconnected: $clientId. Total: $num")
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

    private fun resolveAssetPath(uri: String): String? {
        val normalizedUri = URLDecoder.decode(uri, StandardCharsets.UTF_8.name())
        if (normalizedUri.contains('\u0000')) return null

        val relativePath = normalizedUri
            .removePrefix("/")
            .ifEmpty { "index.html" }
            .split('/')
            .filter { it.isNotBlank() && it != "." }

        if (relativePath.any { it == ".." }) return null

        val assetPath = relativePath.joinToString("/")
        return if (assetPath.isBlank()) "webui/index.html" else "webui/$assetPath"
    }

    private fun serveSnapshot(session: IHTTPSession): Response {
        if (!webStreamingEnabled) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Web streaming is disabled"
            )
        }

        val params = session.queryParameterString ?: ""
        val highRes = params.contains("highres=1") || params.contains("high_res=1")
        val saveToDisk = params.contains("save=1") || params.contains("save_to_disk=1")

        if (highRes) {
            val result = runBlocking { webApi.capture.captureSnapshot(saveToDisk) }
            return when (result) {
                is PhotoCaptureManager.SnapshotResult.Success -> {
                    newFixedLengthResponse(
                        Response.Status.OK, "image/jpeg",
                        ByteArrayInputStream(result.data), result.data.size.toLong()
                    ).apply {
                        addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                        addHeader("Pragma", "no-cache")
                        addHeader("Expires", "0")
                        result.savedPath?.let { path ->
                            addHeader("X-Saved-Path", path)
                        }
                    }
                }
                is PhotoCaptureManager.SnapshotResult.Error -> {
                    newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        """{"error":"${result.message.replace("\"", "\\\"")}"}"""
                    )
                }
            }
        }

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
        if (!webStreamingEnabled) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Web streaming is disabled"
            )
        }

        val audioStream = audioStreamingManager.openStream()
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
                addHeader("X-Audio-Sample-Rate", "${audioStreamingManager.getSampleRateHz()}")
                addHeader("X-Audio-Channels", "${audioStreamingManager.getChannelCount()}")
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
        private const val MAX_CACHED_ASSETS = 50
    }
}

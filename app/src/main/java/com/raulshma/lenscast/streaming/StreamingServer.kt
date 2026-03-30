package com.raulshma.lenscast.streaming

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.data.StreamAuthSettings
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

class StreamingServer(
    private val port: Int = DEFAULT_PORT,
    private val context: Context? = null,
    private val audioStreamingManager: AudioStreamingManager? = null,
) : NanoHTTPD(port) {

    private val boundary = BOUNDARY_MARKER
    private val clientCount = AtomicInteger(0)
    private var latestJpeg: ByteArray? = null
    private val frameLock = Object()
    private var latestFrameVersion = 0L
    private var isRunning = false
    @Volatile var authUsername: String? = null
    @Volatile var authPasswordHash: String? = null

    private val apiController: WebApiController? = context?.let { WebApiController(it) }

    private val assetCache = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    private val sessions = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()

    private data class AuthAttempt(var count: Int, var blockedUntil: Long)
    private val authAttempts = ConcurrentHashMap<String, AuthAttempt>()

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
            val isAuthEnabled = authUsername != null && !authUsername.isNullOrEmpty() && authPasswordHash != null
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
            if (authUsername == null || authUsername.isNullOrEmpty() || authPasswordHash == null) {
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
            val clientIp = session.remoteIpAddress ?: "unknown"
            val attempt = authAttempts.getOrPut(clientIp) { AuthAttempt(0, 0L) }
            val now = System.currentTimeMillis()
            if (attempt.blockedUntil > now) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"Too many attempts. Try again later."}"""
                ).apply { addSecurityHeaders() }
            }
            if (attempt.count >= MAX_AUTH_ATTEMPTS) {
                attempt.blockedUntil = now + AUTH_LOCKOUT_MS
                attempt.count = 0
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"Too many attempts. Try again later."}"""
                ).apply { addSecurityHeaders() }
            }
            val storedUsername = authUsername
            val storedHash = authPasswordHash
            if (storedUsername == null || storedHash == null) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"Auth not configured"}"""
                ).apply { addSecurityHeaders() }
            }
            if (!constantTimeEquals(loginBody.first, storedUsername)) {
                attempt.count++
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"Invalid credentials"}"""
                ).apply { addSecurityHeaders() }
            }
            if (!StreamAuthSettings.verifyPassword(loginBody.second, storedHash)) {
                attempt.count++
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"Invalid credentials"}"""
                ).apply { addSecurityHeaders() }
            }
            attempt.count = 0
            val token = createSession()
            val secureFlag = if (sslEnabled) "; Secure" else ""
            return newFixedLengthResponse(
                Response.Status.OK, "application/json", """{"success":true}"""
            ).apply {
                addHeader("Set-Cookie", "$COOKIE_NAME=$token; Path=$COOKIE_PATH; Max-Age=$SESSION_MAX_AGE_SEC; HttpOnly; SameSite=Lax$secureFlag")
                addHeader("Cache-Control", "no-store")
                addSecurityHeaders()
            }
        }

        if (uri == "/api/auth/session" && method == Method.GET) {
            val cookieHeader = session.headers["cookie"] ?: ""
            val sessionToken = extractCookie(cookieHeader, COOKIE_NAME)
            val isValid = sessionToken != null && validateSession(sessionToken)
            return newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"authenticated":$isValid}"""
            ).apply { addHeader("Cache-Control", "no-store"); addSecurityHeaders() }
        }

        if (uri == "/api/auth/logout" && method == Method.POST) {
            if (!checkAuth(session)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"error":"Authentication required"}"""
                ).apply { addSecurityHeaders() }
            }
            if (!isCsrfSafe(session)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "application/json",
                    """{"error":"CSRF check failed"}"""
                ).apply { addSecurityHeaders() }
            }

            val cookieHeader = session.headers["cookie"] ?: ""
            val token = extractCookie(cookieHeader, COOKIE_NAME)
            if (token != null) {
                sessions.remove(token)
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"success":true}"""
            ).apply {
                addHeader("Set-Cookie", "$COOKIE_NAME=; Path=$COOKIE_PATH; Max-Age=0; HttpOnly; SameSite=Lax")
                addHeader("Cache-Control", "no-store")
                addSecurityHeaders()
            }
        }

        val isProtectedRoute = uri.startsWith("/api/") || uri == "/stream" || uri == "/audio" || uri == "/snapshot"

        if (!isProtectedRoute) {
            return serveStaticFile(uri)
        }

        if (!checkAuth(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                """{"error":"Authentication required"}"""
            ).apply { addSecurityHeaders() }
        }

        if (method != Method.GET && !isCsrfSafe(session)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "application/json",
                """{"error":"CSRF check failed"}"""
            ).apply { addSecurityHeaders() }
        }

        val response = when {
            uri == "/stream" -> serveMjpegStream()
            uri == "/audio" -> serveAudioStream()
            uri == "/snapshot" -> serveSnapshot()
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
        if (sslEnabled) {
            addHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }

    private fun checkAuth(session: IHTTPSession): Boolean {
        val username = authUsername ?: return true
        val storedHash = authPasswordHash ?: return true
        if (username.isEmpty()) return true

        val cookieHeader = session.headers["cookie"] ?: ""
        val sessionToken = extractCookie(cookieHeader, COOKIE_NAME)
        if (sessionToken != null && validateSession(sessionToken)) {
            return true
        }

        return false
    }

    private fun createSession(): String {
        cleanExpiredSessions()
        // Enforce maximum session count to prevent OOM via session flooding
        if (sessions.size >= MAX_SESSIONS) {
            // Evict oldest sessions beyond the cap
            val sorted = sessions.entries.sortedBy { it.value }
            val toRemove = sorted.take(sessions.size - MAX_SESSIONS + 1)
            toRemove.forEach { sessions.remove(it.key) }
        }
        val bytes = ByteArray(SESSION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessions[token] = System.currentTimeMillis() + SESSION_DURATION_MS
        return token
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now > it.value }
    }

    private fun validateSession(token: String): Boolean {
        // Opportunistically clean expired sessions on each validation
        cleanExpiredSessions()
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

    /**
     * Constant-time comparison that does not leak string length via timing.
     * Uses MessageDigest.isEqual on SHA-256 hashes so both operands are always
     * the same length regardless of input.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashA = digest.digest(a.toByteArray(Charsets.UTF_8))
        val hashB = digest.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(hashA, hashB)
    }

    /**
     * CSRF protection for state-changing requests.
     * Session-based requests must have a matching Origin/Referer or X-Requested-With header.
     */
    private fun isCsrfSafe(session: IHTTPSession): Boolean {
        // Check for X-Requested-With header (sent by XHR/fetch)
        if (session.headers.containsKey("x-requested-with")) return true

        // Check Origin or Referer header matches this server using strict URI parsing
        val originHeader = session.headers["origin"] ?: session.headers["referer"]
        if (originHeader != null) {
            val localIp = NetworkUtils.getLocalIpAddress()
            val allowedOrigins = buildList {
                add("http://localhost:$port")
                add("http://127.0.0.1:$port")
                if (localIp != null) add("http://$localIp:$port")
            }
            return try {
                val requestUri = URI(originHeader)
                val requestOrigin = "${requestUri.scheme}://${requestUri.host}:${requestUri.port}"
                allowedOrigins.any { allowed ->
                    val allowedUri = URI(allowed)
                    val normalizedAllowed = "${allowedUri.scheme}://${allowedUri.host}:${allowedUri.port}"
                    requestOrigin == normalizedAllowed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse CSRF origin header: $originHeader", e)
                false
            }
        }

        // No recognized CSRF protection headers found
        return false
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
        if (body == null) {
            return newFixedLengthResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                "application/json",
                """{"error":"Request body too large (max ${MAX_BODY_BYTES / 1024 / 1024}MB)"}"""
            )
        }
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
        val assetMgr = context?.assets ?: return serveFallbackControlPage()

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
            val thumbnailBytes = controller.resolveVideoThumbnail(id)
            return if (thumbnailBytes != null) {
                newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(thumbnailBytes), thumbnailBytes.size.toLong()
                ).apply {
                    addHeader("Cache-Control", "public, max-age=3600")
                }
            } else {
                // Fallback: try to serve the media file itself (for photos)
                val resolved = controller.resolveMediaFile(id)
                if (resolved != null) {
                    newChunkedResponse(Response.Status.OK, resolved.second, resolved.first)
                } else {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "application/json",
                        """{"error":"Thumbnail not available"}"""
                    )
                }
            }
        }

        val id = path
        val resolved = controller.resolveMediaFile(id)
        if (resolved == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"Media not found"}"""
            )
        }

        val (inputStream, mimeType, fileSize) = resolved
        val download = session.parameters?.containsKey("download") == true

        // For video files, support HTTP Range requests for proper playback
        if (mimeType.startsWith("video/") && !download) {
            val rangeHeader = session.headers["range"]
            return serveVideoWithRange(inputStream, mimeType, fileSize, rangeHeader)
        }

        val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
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

    private var sslEnabled = false
    private var sslServerSocketFactory: javax.net.ssl.SSLServerSocketFactory? = null

    /**
     * Enables HTTPS using a self-signed certificate stored in the Android Keystore.
     * Must be called before [startServer]. When enabled, all connections use TLS.
     */
    fun enableSsl(ctx: Context): Boolean {
        return try {
            val alias = "lenscast_ssl"
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // Generate a self-signed key if not already present
            if (!keyStore.containsAlias(alias)) {
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
                )
                kpg.initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or
                            KeyProperties.PURPOSE_DECRYPT or
                            KeyProperties.PURPOSE_SIGN or
                            KeyProperties.PURPOSE_VERIFY
                    )
                        .setKeySize(2048)
                        .setCertificateSubject(X500Principal("CN=LensCast, O=LensCast"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(Date())
                        .setCertificateNotAfter(
                            Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000)
                        )
                        .build()
                )
                kpg.generateKeyPair()
                Log.d(TAG, "Self-signed TLS certificate generated")
            }

            // Reload keystore to pick up the new key
            val loadedKeyStore = KeyStore.getInstance("AndroidKeyStore")
            loadedKeyStore.load(null)

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(loadedKeyStore, null)

            val sslCtx = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, null)
            }
            sslServerSocketFactory = sslCtx.serverSocketFactory
            sslEnabled = true
            Log.d(TAG, "TLS enabled for streaming server")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable TLS", e)
            sslEnabled = false
            false
        }
    }

    fun isSslEnabled(): Boolean = sslEnabled

    fun startServer(): Boolean {
        return try {
            if (sslEnabled && sslServerSocketFactory != null) {
                makeSecure(sslServerSocketFactory, null)
            }
            start()
            isRunning = true
            Log.d(TAG, if (sslEnabled) "HTTPS streaming server started on port $port" else "Streaming server started on port $port")
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
        const val DEFAULT_PORT = 8080
        const val BOUNDARY_MARKER = "LensCastBoundary"
        private const val SESSION_DURATION_MS = 24 * 60 * 60 * 1000L
        private const val SESSION_MAX_AGE_SEC = 24 * 60 * 60
        private const val MAX_SESSIONS = 1000
        private const val SESSION_TOKEN_BYTES = 32
        private const val COOKIE_NAME = "lenscast_session"
        private const val COOKIE_PATH = "/"
        private const val MAX_BODY_BYTES = 1L * 1024 * 1024
        private const val MAX_AUTH_ATTEMPTS = 10
        private const val AUTH_LOCKOUT_MS = 60 * 1000L
    }
}

package com.raulshma.lenscast.streaming.rtsp

import android.util.Log
import com.raulshma.lenscast.data.StreamAuthSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RtspServer(private val port: Int = DEFAULT_PORT) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    private val encoder = H264Encoder()
    private val clients = ConcurrentHashMap<String, ClientSession>()
    private val sessionIdCounter = AtomicInteger(0)

    @Volatile
    private var authEnabled = false
    @Volatile
    private var authUsername: String? = null
    @Volatile
    private var authPasswordHash: String? = null
    @Volatile
    private var authDigestHa1: String? = null

    private val secureRandom = SecureRandom()
    private val digestNonces = ConcurrentHashMap<String, DigestNonceState>()

    private var videoWidth = 1280
    private var videoHeight = 720
    private var videoBitrate = 2_000_000
    private var videoFrameRate = 24
    private var videoInputFormat = RtspInputFormat.AUTO
    private var lastRotation = 0

    private var rtpTimestamp: Long = 0
    private val timestampIncrement: Long
        get() = if (videoFrameRate > 0) 90000L / videoFrameRate else 3750L

    private val lastFrameTime = AtomicLong(0)
    private var lastEncodedTime = 0L
    private val minFrameIntervalMs: Long
        get() = if (videoFrameRate > 0) 1000L / videoFrameRate else 42L

    private data class DigestNonceState(
        val expiresAtMs: Long,
        val ncTrack: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    )

    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        return try {
            serverSocket = ServerSocket(port, 5, InetAddress.getByName("0.0.0.0"))
            rtpTimestamp = 0
            RtpPacketizer.reset()

            encoder.configure(videoWidth, videoHeight, videoBitrate, videoFrameRate)
            encoder.setInputFormat(videoInputFormat)
            encoder.onEncodedFrame = { nalUnits ->
                distributeEncodedFrame(nalUnits)
            }

            if (!encoder.start()) {
                running.set(false)
                serverSocket?.close()
                serverSocket = null
                return false
            }

            acceptThread = Thread({ acceptLoop() }, "RtspServer-Accept").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }

            Log.d(TAG, "RTSP server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        encoder.stop()

        clients.values.forEach { it.close() }
        clients.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        try {
            acceptThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        acceptThread = null

        Log.d(TAG, "RTSP server stopped")
    }

    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int) {
        if (!running.get()) return

        val playingClients = clients.count { it.value.isPlaying }
        if (playingClients == 0) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTime.getAndSet(now)
        if (elapsed < minFrameIntervalMs * 0.8) return

        if (encoder.isEncoderLagged()) {
            return
        }

        val effectiveWidth: Int
        val effectiveHeight: Int
        val frameData: ByteArray

        if (rotation == 90 || rotation == 270) {
            effectiveWidth = height
            effectiveHeight = width
            frameData = rotateNv21(yuvData, width, height, rotation)
        } else if (rotation == 180) {
            effectiveWidth = width
            effectiveHeight = height
            frameData = rotateNv21(yuvData, width, height, rotation)
        } else {
            effectiveWidth = width
            effectiveHeight = height
            frameData = yuvData
        }

        if (effectiveWidth != videoWidth || effectiveHeight != videoHeight || rotation != lastRotation) {
            lastRotation = rotation
            reconfigureEncoder(effectiveWidth, effectiveHeight)
        }

        encoder.encodeFrame(frameData)
    }

    fun setBitrate(bitrate: Int) {
        videoBitrate = bitrate.coerceIn(500_000, 8_000_000)
        encoder.setBitrate(videoBitrate)
    }

    fun setFrameRate(fps: Int) {
        videoFrameRate = fps.coerceIn(1, 60)
    }

    fun setInputFormat(format: RtspInputFormat) {
        if (videoInputFormat == format) return
        videoInputFormat = format
        encoder.setInputFormat(format)
        if (running.get()) {
            reconfigureEncoder(videoWidth, videoHeight)
        }
    }

    fun setAuthSettings(enabled: Boolean, username: String?, passwordHash: String?, digestHa1: String?) {
        authEnabled = enabled && !username.isNullOrBlank() && !passwordHash.isNullOrBlank()
        authUsername = if (authEnabled) username else null
        authPasswordHash = if (authEnabled) passwordHash else null
        authDigestHa1 = if (authEnabled && !digestHa1.isNullOrBlank()) digestHa1.lowercase(Locale.US) else null
    }

    private fun reconfigureEncoder(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        encoder.stop()
        encoder.configure(width, height, videoBitrate, videoFrameRate)
        encoder.setInputFormat(videoInputFormat)
        encoder.onEncodedFrame = { nalUnits ->
            distributeEncodedFrame(nalUnits)
        }
        encoder.start()
        Log.d(TAG, "Encoder reconfigured to ${width}x${height}")
    }

    private fun distributeEncodedFrame(nalUnits: List<H264Encoder.EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return

        rtpTimestamp += timestampIncrement
        lastEncodedTime = System.currentTimeMillis()

        for (client in clients.values) {
            if (client.isPlaying) {
                for ((index, nalUnit) in nalUnits.withIndex()) {
                    val marker = index == nalUnits.lastIndex
                    val packets = RtpPacketizer.packetizeNalUnit(nalUnit.data, rtpTimestamp, marker)
                    for (packet in packets) {
                        client.sendRtpPacket(packet)
                    }
                }
            }
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                serverSocket?.soTimeout = 2000
                val socket = serverSocket?.accept() ?: break

                if (clients.size >= MAX_CLIENTS) {
                    Log.w(TAG, "Rejecting client: max connections ($MAX_CLIENTS) reached")
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                    continue
                }

                socket.soTimeout = SESSION_TIMEOUT_MS.toInt()
                socket.tcpNoDelay = true

                val sessionId = sessionIdCounter.incrementAndGet().toString()
                val client = ClientSession(socket, sessionId)
                clients[sessionId] = client

                Thread({
                    try {
                        client.handle()
                    } catch (e: Exception) {
                        Log.d(TAG, "Client session ended: ${e.message}")
                    } finally {
                        clients.remove(sessionId)
                        client.close()
                    }
                }, "RtspClient-$sessionId").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                    start()
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: SocketException) {
                if (running.get()) {
                    try {
                        serverSocket?.close()
                    } catch (_: Exception) {
                    }
                    serverSocket = null
                    if (running.get()) {
                        try {
                            serverSocket = ServerSocket(port, 5, InetAddress.getByName("0.0.0.0"))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reopen server socket", e)
                            running.set(false)
                        }
                    }
                }
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Accept error", e)
            }
        }
    }

    fun getClientCount(): Int = clients.count { it.value.isPlaying }

    fun isRunning(): Boolean = running.get()

    private fun rotateNv21(src: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
        return when (rotation) {
            180 -> rotateNv21_180(src, width, height)
            90 -> rotateNv21_90(src, width, height)
            270 -> rotateNv21_270(src, width, height)
            else -> src
        }
    }

    private fun rotateNv21_90(src: ByteArray, width: Int, height: Int): ByteArray {
        val dstW = height
        val dstH = width
        val ySize = width * height
        val dst = ByteArray(dstW * dstH * 3 / 2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dstX = dstW - 1 - y
                val dstY = x
                dst[dstY * dstW + dstX] = src[y * width + x]
            }
        }

        val dstUvStart = ySize
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val srcVIdx = ySize + y * width + x * 2
                val srcUIdx = ySize + y * width + x * 2 + 1
                val dstX = dstW / 2 - 1 - y
                val dstY = x
                val dstIdx = dstUvStart + dstY * dstW + dstX * 2
                dst[dstIdx] = src[srcVIdx]
                dst[dstIdx + 1] = src[srcUIdx]
            }
        }

        return dst
    }

    private fun rotateNv21_270(src: ByteArray, width: Int, height: Int): ByteArray {
        val dstW = height
        val dstH = width
        val ySize = width * height
        val dst = ByteArray(dstW * dstH * 3 / 2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                dst[(width - 1 - x) * dstW + y] = src[y * width + x]
            }
        }

        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val srcVIdx = ySize + y * width + x * 2
                val srcUIdx = ySize + y * width + x * 2 + 1
                val dstX = y
                val dstY = width / 2 - 1 - x
                val dstIdx = ySize + dstY * dstW + dstX * 2
                dst[dstIdx] = src[srcVIdx]
                dst[dstIdx + 1] = src[srcUIdx]
            }
        }

        return dst
    }

    private fun rotateNv21_180(src: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val dst = ByteArray(ySize * 3 / 2)

        for (i in 0 until ySize) {
            dst[i] = src[ySize - 1 - i]
        }

        val uvSize = ySize / 2
        for (i in 0 until uvSize step 2) {
            dst[ySize + i] = src[ySize + uvSize - 2 - i]
            dst[ySize + i + 1] = src[ySize + uvSize - 1 - i]
        }

        return dst
    }

    private inner class ClientSession(
        private val socket: Socket,
        private val sessionId: String
    ) {
        private var state = SessionState.INIT
        private var cSeq = 0
        private var lastCSeq = -1
        private var rtpChannel = 0
        private var rtcpChannel = 1
        private var rtspSessionId = ""
        private val createdAt = System.currentTimeMillis()
        private var lastActivity = System.currentTimeMillis()
        private var lastRtcpActivity = 0L

        var isPlaying = false
            private set

        private val outputStream: OutputStream?
            get() = try {
                if (!socket.isClosed) socket.getOutputStream() else null
            } catch (_: Exception) {
                null
            }

        fun handle() {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            var requestLines = mutableListOf<String>()

            while (!socket.isClosed && running.get()) {
                try {
                    if (System.currentTimeMillis() - lastActivity > SESSION_TIMEOUT_MS) {
                        Log.d(TAG, "Client session timed out: $sessionId")
                        break
                    }

                    val firstByte = input.read()
                    if (firstByte < 0) break

                    if (firstByte == INTERLEAVED_FRAME_MAGIC) {
                        val channel = input.read()
                        val sizeHi = input.read()
                        val sizeLo = input.read()
                        if (channel < 0 || sizeHi < 0 || sizeLo < 0) break

                        val frameSize = (sizeHi shl 8) or sizeLo
                        if (!discardBytes(input, frameSize)) break

                        lastActivity = System.currentTimeMillis()
                        if (channel == rtcpChannel) {
                            lastRtcpActivity = lastActivity
                        }
                        continue
                    }

                    val line = readRtspLine(input, firstByte) ?: break

                    lastActivity = System.currentTimeMillis()

                    if (line.isNotEmpty()) {
                        requestLines.add(line)
                    } else if (requestLines.isNotEmpty()) {
                        val contentLength = extractContentLength(requestLines)
                        if (contentLength > 0) {
                            if (!discardBytes(input, contentLength)) break
                        }
                        processRequest(requestLines, output)
                        requestLines = mutableListOf()
                    }
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
            }
        }

        private fun readRtspLine(input: InputStream, firstByte: Int): String? {
            val lineBuffer = ByteArrayOutputStream(128)
            var current = firstByte

            while (true) {
                if (current < 0) return null
                if (current == '\n'.code) {
                    break
                }
                if (current != '\r'.code) {
                    lineBuffer.write(current)
                }
                current = input.read()
            }

            return lineBuffer.toString(Charsets.UTF_8.name())
        }

        private fun discardBytes(input: InputStream, byteCount: Int): Boolean {
            var remaining = byteCount
            val discardBuffer = ByteArray(2048)

            while (remaining > 0) {
                val toRead = minOf(remaining, discardBuffer.size)
                val read = input.read(discardBuffer, 0, toRead)
                if (read <= 0) return false
                remaining -= read
            }
            return true
        }

        private fun processRequest(lines: List<String>, output: OutputStream) {
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val requestUri = parts[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val colonIdx = lines[i].indexOf(':')
                if (colonIdx > 0) {
                    val key = lines[i].substring(0, colonIdx).trim().lowercase()
                    val value = lines[i].substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            val parsedCSeq = headers["cseq"]?.toIntOrNull()
            if (parsedCSeq == null || parsedCSeq < 0) {
                cSeq = 0
                sendResponse(output, "400 Bad Request")
                return
            }
            if (lastCSeq >= 0 && parsedCSeq <= lastCSeq) {
                cSeq = parsedCSeq
                sendResponse(output, "400 Bad Request")
                return
            }

            cSeq = parsedCSeq
            lastCSeq = parsedCSeq

            if (requiresAuthentication(method) && !isAuthorized(headers, method, requestUri)) {
                sendUnauthorized(output)
                return
            }

            if (!isRequestUriAllowed(method, requestUri)) {
                sendResponse(output, "404 Not Found")
                return
            }

            when (method) {
                "OPTIONS" -> handleOptions(output)
                "DESCRIBE" -> handleDescribe(output, requestUri)
                "SETUP" -> handleSetup(output, headers, requestUri)
                "PLAY" -> handlePlay(output, headers, requestUri)
                "TEARDOWN" -> handleTeardown(output, headers)
                "GET_PARAMETER" -> if (isValidSession(headers)) sendOk(output) else sendResponse(output, "454 Session Not Found")
                "SET_PARAMETER" -> if (isValidSession(headers)) sendOk(output) else sendResponse(output, "454 Session Not Found")
                else -> sendResponse(output, "405 Method Not Allowed")
            }
        }

        private fun handleOptions(output: OutputStream) {
            sendResponse(
                output, "200 OK", mapOf(
                    "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER, SET_PARAMETER"
                )
            )
        }

        private fun handleDescribe(output: OutputStream, requestUri: String) {
            if (rtspSessionId.isEmpty()) {
                rtspSessionId = sessionId + "_" + System.currentTimeMillis().toString(16)
            }

            val sdp = buildSdp()
            sendResponse(
                output, "200 OK", mapOf(
                    "Content-Type" to "application/sdp",
                    "Content-Base" to "rtsp://${socket.localAddress.hostAddress}:$port/"
                ), sdp.toByteArray(Charsets.UTF_8)
            )
        }

        private fun handleSetup(output: OutputStream, headers: Map<String, String>, requestUri: String) {
            if (!isStreamControlUri(requestUri)) {
                sendResponse(output, "404 Not Found")
                return
            }

            val transport = headers["transport"] ?: ""

            if (!transport.contains("RTP/AVP/TCP", ignoreCase = true) ||
                !transport.contains("interleaved", ignoreCase = true)
            ) {
                sendResponse(output, "461 Unsupported Transport")
                return
            }

            if (rtspSessionId.isNotEmpty()) {
                val requestedSession = headers["session"]?.substringBefore(';')?.trim()
                if (requestedSession != null && requestedSession != rtspSessionId) {
                    sendResponse(output, "454 Session Not Found")
                    return
                }
            }

            val interleavedMatch = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
            if (interleavedMatch != null) {
                rtpChannel = interleavedMatch.groupValues[1].toInt()
                rtcpChannel = interleavedMatch.groupValues[2].toInt()
            }

            if (rtspSessionId.isEmpty()) {
                rtspSessionId = sessionId + "_" + System.currentTimeMillis().toString(16)
            }

            state = SessionState.READY

            sendResponse(
                output, "200 OK", mapOf(
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel",
                    "Session" to "$rtspSessionId;timeout=60"
                )
            )
        }

        private fun handlePlay(output: OutputStream, headers: Map<String, String>, requestUri: String) {
            if (!isValidSession(headers)) {
                sendResponse(output, "454 Session Not Found")
                return
            }

            if (state != SessionState.READY && !isPlaying) {
                sendResponse(output, "455 Method Not Valid In This State")
                return
            }

            state = SessionState.PLAYING
            isPlaying = true

            encoder.requestKeyFrame()
            val streamUrl = buildAbsoluteRtspUrl(requestUri)

            sendResponse(
                output, "200 OK", mapOf(
                    "Session" to rtspSessionId,
                    "Range" to "npt=0.000-",
                    "RTP-Info" to "url=$streamUrl;seq=${RtpPacketizer.currentSeq};rtptime=$rtpTimestamp"
                )
            )
        }

        private fun handleTeardown(output: OutputStream, headers: Map<String, String>) {
            if (!isValidSession(headers)) {
                sendResponse(output, "454 Session Not Found")
                return
            }

            isPlaying = false
            state = SessionState.INIT
            sendResponse(
                output, "200 OK", mapOf(
                    "Session" to rtspSessionId
                )
            )
        }

        private fun sendOk(output: OutputStream) {
            sendResponse(output, "200 OK")
        }

        private fun requiresAuthentication(method: String): Boolean {
            if (!authEnabled) return false
            return method != "OPTIONS"
        }

        private fun isAuthorized(headers: Map<String, String>, method: String, requestUri: String): Boolean {
            if (!authEnabled) return true

            val expectedUser = authUsername ?: return false
            val expectedHash = authPasswordHash ?: return false
            val authHeader = headers["authorization"] ?: return false

            if (authHeader.startsWith("Digest ", ignoreCase = true)) {
                val digestHa1 = authDigestHa1 ?: return false
                return isAuthorizedDigest(authHeader, expectedUser, digestHa1, method, requestUri)
            }
            if (!authHeader.startsWith("Basic ", ignoreCase = true)) return false

            val payload = authHeader.substringAfter(' ', "").trim()
            if (payload.isEmpty()) return false

            val decoded = runCatching {
                val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            }.getOrNull() ?: return false

            val separator = decoded.indexOf(':')
            if (separator <= 0) return false

            val providedUser = decoded.substring(0, separator)
            val providedPassword = decoded.substring(separator + 1)

            if (!constantTimeEquals(providedUser, expectedUser)) return false
            return StreamAuthSettings.verifyPassword(providedPassword, expectedHash)
        }

        private fun isAuthorizedDigest(
            authHeader: String,
            expectedUser: String,
            digestHa1: String,
            method: String,
            requestUri: String,
        ): Boolean {
            val params = parseDigestParams(authHeader.substringAfter(' ', ""))

            val username = params["username"] ?: return false
            val realm = params["realm"] ?: return false
            val nonce = params["nonce"] ?: return false
            val uri = params["uri"] ?: return false
            val response = params["response"]?.lowercase(Locale.US) ?: return false
            val qop = params["qop"]?.lowercase(Locale.US)
            val nc = params["nc"] ?: ""
            val cnonce = params["cnonce"] ?: ""
            val opaque = params["opaque"] ?: ""

            if (!constantTimeEquals(username, expectedUser)) return false
            if (!constantTimeEquals(realm, AUTH_REALM)) return false
            if (!constantTimeEquals(opaque, AUTH_OPAQUE)) return false
            if (!isDigestUriMatch(uri, requestUri)) return false
            if (!validateDigestNonce(nonce, username, cnonce, nc, qop)) return false

            val ha2 = md5Hex("$method:$uri")
            val expectedResponse = if (!qop.isNullOrBlank()) {
                if (cnonce.isBlank() || nc.isBlank()) return false
                md5Hex("$digestHa1:$nonce:$nc:$cnonce:$qop:$ha2")
            } else {
                md5Hex("$digestHa1:$nonce:$ha2")
            }

            return constantTimeEquals(response, expectedResponse)
        }

        private fun parseDigestParams(value: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            var i = 0
            while (i < value.length) {
                while (i < value.length && (value[i] == ' ' || value[i] == ',')) i++
                if (i >= value.length) break

                val keyStart = i
                while (i < value.length && value[i] != '=') i++
                if (i >= value.length) break
                val key = value.substring(keyStart, i).trim().lowercase(Locale.US)
                i++

                val parsedValue = if (i < value.length && value[i] == '"') {
                    i++
                    val sb = StringBuilder()
                    var escaped = false
                    while (i < value.length) {
                        val ch = value[i]
                        if (escaped) {
                            sb.append(ch)
                            escaped = false
                        } else if (ch == '\\') {
                            escaped = true
                        } else if (ch == '"') {
                            i++
                            break
                        } else {
                            sb.append(ch)
                        }
                        i++
                    }
                    sb.toString()
                } else {
                    val start = i
                    while (i < value.length && value[i] != ',') i++
                    value.substring(start, i).trim()
                }

                result[key] = parsedValue
                while (i < value.length && value[i] != ',') i++
                if (i < value.length && value[i] == ',') i++
            }
            return result
        }

        private fun validateDigestNonce(
            nonce: String,
            username: String,
            cnonce: String,
            ncHex: String,
            qop: String?,
        ): Boolean {
            cleanExpiredDigestNonces()
            val state = digestNonces[nonce] ?: return false
            if (System.currentTimeMillis() > state.expiresAtMs) {
                digestNonces.remove(nonce)
                return false
            }

            if (qop.isNullOrBlank()) return true
            val nc = ncHex.toLongOrNull(16) ?: return false
            if (cnonce.isBlank()) return false
            val key = "$username|$cnonce"
            val previous = state.ncTrack[key] ?: 0L
            if (nc <= previous) return false
            state.ncTrack[key] = nc
            return true
        }

        private fun isDigestUriMatch(digestUri: String, requestUri: String): Boolean {
            val digestPath = normalizeRtspPath(extractRtspPath(digestUri))
            val requestPath = normalizeRtspPath(extractRtspPath(requestUri))
            return constantTimeEquals(digestPath, requestPath)
        }

        private fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun sendUnauthorized(output: OutputStream) {
            val digestChallenge = if (authDigestHa1 != null) {
                val nonce = createDigestNonce()
                "Digest realm=\"$AUTH_REALM\", nonce=\"$nonce\", opaque=\"$AUTH_OPAQUE\", algorithm=MD5, qop=\"auth\""
            } else null

            val challenge = digestChallenge ?: "Basic realm=\"$AUTH_REALM\""
            sendResponse(output, "401 Unauthorized", mapOf("WWW-Authenticate" to challenge))
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashA = digest.digest(a.toByteArray(Charsets.UTF_8))
            val hashB = digest.digest(b.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(hashA, hashB)
        }

        private fun createDigestNonce(): String {
            cleanExpiredDigestNonces()
            val bytes = ByteArray(DIGEST_NONCE_BYTES)
            secureRandom.nextBytes(bytes)
            val nonce = android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
            )
            digestNonces[nonce] = DigestNonceState(System.currentTimeMillis() + DIGEST_NONCE_TTL_MS)
            return nonce
        }

        private fun cleanExpiredDigestNonces() {
            val now = System.currentTimeMillis()
            digestNonces.entries.removeIf { now > it.value.expiresAtMs }
            if (digestNonces.size > MAX_DIGEST_NONCES) {
                val overflow = digestNonces.size - MAX_DIGEST_NONCES
                val keysToRemove = digestNonces.entries
                    .sortedBy { it.value.expiresAtMs }
                    .take(overflow)
                    .map { it.key }
                keysToRemove.forEach { digestNonces.remove(it) }
            }
        }

        private fun isValidSession(headers: Map<String, String>): Boolean {
            if (rtspSessionId.isEmpty()) return false
            val sessionHeader = headers["session"] ?: return false
            val providedSession = sessionHeader.substringBefore(';').trim()
            return providedSession == rtspSessionId
        }

        private fun isRequestUriAllowed(method: String, requestUri: String): Boolean {
            return when (method) {
                "OPTIONS", "DESCRIBE" -> isAggregateOrStreamUri(requestUri)
                "SETUP" -> isStreamControlUri(requestUri)
                "PLAY", "TEARDOWN" -> isAggregateOrStreamUri(requestUri) || isStreamControlUri(requestUri)
                "GET_PARAMETER", "SET_PARAMETER" -> true
                else -> true
            }
        }

        private fun isAggregateOrStreamUri(requestUri: String): Boolean {
            val path = normalizeRtspPath(extractRtspPath(requestUri))
            return path == "/" || path == "/$DEFAULT_STREAM_PATH"
        }

        private fun isStreamControlUri(requestUri: String): Boolean {
            val path = normalizeRtspPath(extractRtspPath(requestUri))
            if (path == "/$DEFAULT_STREAM_PATH") return true
            if (path.equals("/trackid=0", ignoreCase = true)) return true
            if (path.startsWith("/$DEFAULT_STREAM_PATH/trackid=", ignoreCase = true)) return true
            if (path.startsWith("/$DEFAULT_STREAM_PATH/track", ignoreCase = true)) return true
            return false
        }

        private fun extractRtspPath(requestUri: String): String {
            val path = if (requestUri.startsWith("rtsp://", ignoreCase = true)) {
                val schemeSep = requestUri.indexOf("://")
                val afterScheme = if (schemeSep >= 0) requestUri.substring(schemeSep + 3) else requestUri
                val slashIndex = afterScheme.indexOf('/')
                if (slashIndex >= 0) afterScheme.substring(slashIndex) else "/"
            } else {
                requestUri
            }
            return if (path.startsWith('/')) path else "/$path"
        }

        private fun normalizeRtspPath(path: String): String {
            var normalized = path.substringBefore('?').substringBefore('#').trim()
            if (normalized.isEmpty()) return "/"
            if (!normalized.startsWith('/')) normalized = "/$normalized"
            while (normalized.contains("//")) {
                normalized = normalized.replace("//", "/")
            }
            if (normalized.length > 1 && normalized.endsWith('/')) {
                normalized = normalized.dropLast(1)
            }
            return normalized
        }

        private fun buildAbsoluteRtspUrl(requestUri: String): String {
            if (requestUri.startsWith("rtsp://", ignoreCase = true)) {
                return requestUri
            }
            val normalizedPath = if (requestUri.startsWith("/")) requestUri else "/$requestUri"
            return "rtsp://${socket.localAddress.hostAddress}:$port$normalizedPath"
        }

        private fun extractContentLength(lines: List<String>): Int {
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx <= 0) continue
                val key = line.substring(0, colonIdx).trim()
                if (!key.equals("Content-Length", ignoreCase = true)) continue
                return line.substring(colonIdx + 1).trim().toIntOrNull() ?: 0
            }
            return 0
        }

        fun sendRtpPacket(packet: ByteArray) {
            val output = outputStream ?: return
            try {
                synchronized(output) {
                    output.write(0x24)
                    output.write(rtpChannel)
                    output.write((packet.size shr 8) and 0xFF)
                    output.write(packet.size and 0xFF)
                    output.write(packet)
                    output.flush()
                }
            } catch (_: Exception) {
                isPlaying = false
            }
        }

        fun close() {
            isPlaying = false
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        private fun sendResponse(
            output: OutputStream,
            status: String,
            headers: Map<String, String> = emptyMap(),
            body: ByteArray? = null
        ) {
            synchronized(output) {
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                writer.write("RTSP/1.0 $status\r\n")
                writer.write("CSeq: $cSeq\r\n")
                writer.write("Server: LensCast\r\n")
                writer.write("Date: ${rfc1123Now()}\r\n")
                for ((key, value) in headers) {
                    writer.write("$key: $value\r\n")
                }
                if (body != null) {
                    writer.write("Content-Length: ${body.size}\r\n")
                }
                writer.write("\r\n")
                writer.flush()
                if (body != null) {
                    output.write(body)
                    output.flush()
                }
            }
        }

        private fun buildSdp(): String {
            val spsData = encoder.sps
            val ppsData = encoder.pps

            val spsHex = spsData?.let { bytesToBase64(it) } ?: ""
            val ppsHex = ppsData?.let { bytesToBase64(it) } ?: ""

            val profileLevelId = if (spsData != null && spsData.size >= 4) {
                "%02x%02x%02x".format(
                    spsData[1].toInt() and 0xFF,
                    spsData[2].toInt() and 0xFF,
                    spsData[3].toInt() and 0xFF
                )
            } else {
                "42c01e"
            }

            val spropParams = if (spsHex.isNotEmpty() && ppsHex.isNotEmpty()) {
                "sprop-parameter-sets=$spsHex,$ppsHex;"
            } else {
                ""
            }

            val ip = socket.localAddress.hostAddress

            return buildString {
                appendLine("v=0")
                appendLine("o=- $sessionId 1 IN IP4 $ip")
                appendLine("s=LensCast Camera Stream")
                appendLine("t=0 0")
                appendLine("a=tool:LensCast")
                appendLine("a=type:broadcast")
                appendLine("a=control:*")
                appendLine("a=range:npt=0-")
                appendLine("m=video 0 RTP/AVP 96")
                appendLine("c=IN IP4 0.0.0.0")
                appendLine("b=AS:${videoBitrate / 1000}")
                appendLine("a=rtpmap:96 H264/90000")
                appendLine("a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId;${spropParams}")
                appendLine("a=control:$DEFAULT_STREAM_PATH")
            }
        }

        private fun bytesToBase64(bytes: ByteArray): String {
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

        private fun rfc1123Now(): String = checkNotNull(RFC_1123_FORMAT.get()).format(Date())
    }

    private enum class SessionState {
        INIT, READY, PLAYING
    }

    companion object {
        private const val TAG = "RtspServer"
        private const val AUTH_REALM = "LensCast RTSP"
        private const val AUTH_OPAQUE = "lenscast-rtsp"
        const val DEFAULT_PORT = 8554
        const val DEFAULT_STREAM_PATH = "stream"
        private const val INTERLEAVED_FRAME_MAGIC = 0x24
        private const val MAX_CLIENTS = 4
        private const val SESSION_TIMEOUT_MS = 65_000L
        private const val DIGEST_NONCE_BYTES = 16
        private const val DIGEST_NONCE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_DIGEST_NONCES = 512
        private val RFC_1123_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
            }
        }
    }
}

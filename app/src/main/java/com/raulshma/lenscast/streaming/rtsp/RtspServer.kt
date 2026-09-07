package com.raulshma.lenscast.streaming.rtsp

import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.streaming.FrameThrottle
import com.raulshma.lenscast.streaming.FrameTiming
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
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
    private val aacEncoder = AacEncoder()
    // Fresh packetizers per start replace the old global reset() ritual.
    private var videoPacketizer = RtpPacketizer()
    private var audioPacketizer = AacRtpPacketizer()
    private var audioTimestamp: Long = 0
    private val audioTimestampIncrement: Long = 1024 // AAC-LC: 1024 samples per frame

    private val clients = ConcurrentHashMap<String, ClientSession>()
    private val sessionIdCounter = AtomicInteger(0)

    // Protocol/auth/URI knowledge lives behind the pure seams; the server
    // keeps sockets, fan-out, and the session state machine. The authorizer
    // is per-instance so its nonce store survives stop/start, reading the
    // (restart-applied) auth spec dynamically like the old authSpec getter.
    private val authorizer = RtspSessionAuthorizer(specProvider = { config.auth })

    // One immutable config value replaces the old order-sensitive setter bag.
    @Volatile
    private var config = RtspConfig()

    private var lastRotation = 0

    private var rtpTimestamp: Long = 0
    private val timestampIncrement: Long
        get() = FrameTiming.rtpClockIncrement(config.videoFrameRate)

    private val lastFrameTime = AtomicLong(0)

    private val distributedAus = AtomicLong(0)
    private val distributedPackets = AtomicLong(0)

    // Frame counters for the push path, mirroring the FramePipeline pair:
    // accepted = submitted to the encoder; dropped = throttle- or lag-rejected.
    private val acceptedFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)

    @Volatile
    private var firstKeyframeLogged = false

    @Volatile
    private var lastSenderReportTime = 0L
    private val minFrameIntervalMs: Long
        get() = FrameTiming.frameIntervalMs(config.videoFrameRate)

    private val frameThrottle = FrameThrottle(
        intervalMs = { minFrameIntervalMs },
        tolerance = FrameThrottle.TOLERANCE,
        updateClockOnReject = true,
    )

    /**
     * Start with a complete [RtspConfig] — the old "configure via setters,
     * then start" ordering contract is now enforced by construction.
     * [audioStream] is the live AAC byte source for the audio track, if any.
     */
    fun start(initial: RtspConfig, audioStream: InputStream? = null): Boolean {
        if (running.getAndSet(true)) return true
        config = normalize(initial)
        if (audioStream != null) aacEncoder.setAudioStream(audioStream)

        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 5)
            }
            rtpTimestamp = 0
            firstKeyframeLogged = false
            lastSenderReportTime = 0L
            distributedAus.set(0)
            distributedPackets.set(0)
            acceptedFrames.set(0)
            droppedFrames.set(0)
            videoPacketizer = RtpPacketizer()
            audioPacketizer = AacRtpPacketizer()

            encoder.configure(config.videoWidth, config.videoHeight, config.videoBitrate, config.videoFrameRate)
            encoder.setInputFormat(config.inputFormat)
            encoder.onEncodedFrame = { nalUnits ->
                distributeEncodedFrame(nalUnits)
            }

            if (!encoder.start()) {
                running.set(false)
                serverSocket?.close()
                serverSocket = null
                return false
            }

            // Force CSD emission so the first DESCRIBE can advertise real
            // sprop-parameter-sets instead of an empty/degraded fmtp.
            encoder.submitBlackFrame()

            if (config.audioEnabled) {
                aacEncoder.configure(config.audioSampleRateHz, config.audioChannelCount, config.audioBitrateKbps)
                aacEncoder.onEncodedFrame = { aacData, _ ->
                    distributeEncodedAudioFrame(aacData)
                }
                aacEncoder.start()
                audioTimestamp = 0
                Log.d(TAG, "AAC audio enabled for RTSP: ${config.audioSampleRateHz}Hz, ${config.audioChannelCount}ch")
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

        // Capture the caller: an unexpected stop() is the prime suspect when the
        // RTSP port silently dies while the app keeps running, so make the
        // initiator visible in logcat.
        val caller = Throwable().stackTrace.drop(1).take(6).joinToString(" <- ")
        Log.d(TAG, "RTSP server stopped; stop() called from: $caller")

        encoder.stop()
        if (config.audioEnabled) {
            aacEncoder.stop()
        }

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
        if (!frameThrottle.accept(now)) {
            droppedFrames.incrementAndGet()
            return
        }

        if (encoder.isEncoderLagged()) {
            droppedFrames.incrementAndGet()
            return
        }

        val effectiveWidth: Int
        val effectiveHeight: Int
        val frameData: ByteArray

        if (rotation == 90 || rotation == 270) {
            effectiveWidth = height
            effectiveHeight = width
            frameData = YuvConverter.rotateNv21(yuvData, width, height, rotation)
        } else if (rotation == 180) {
            effectiveWidth = width
            effectiveHeight = height
            frameData = YuvConverter.rotateNv21(yuvData, width, height, rotation)
        } else {
            effectiveWidth = width
            effectiveHeight = height
            frameData = yuvData
        }

        if (effectiveWidth != config.videoWidth || effectiveHeight != config.videoHeight || rotation != lastRotation) {
            lastRotation = rotation
            reconfigureEncoder(effectiveWidth, effectiveHeight)
        }

        acceptedFrames.incrementAndGet()
        encoder.encodeFrame(frameData)
    }

    /** Frames accepted onto the encoder path since start (mirrors the FramePipeline counter). */
    fun acceptedFrameCount(): Long = acceptedFrames.get()

    /** Frames dropped by the push throttle or a lagged encoder since start. */
    fun droppedFrameCount(): Long = droppedFrames.get()

    /** Entry-point validation: fps/bitrate clamped to their StreamDefaults bounds. */
    private fun normalize(config: RtspConfig): RtspConfig = config.copy(
        videoFrameRate = config.videoFrameRate.coerceIn(StreamDefaults.RTSP_FPS_MIN, StreamDefaults.RTSP_FPS_MAX),
        videoBitrate = config.videoBitrate.coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX),
    )

    /**
     * Live-update with a new config. The per-setting semantics that used to be
     * invisible at the call site live in one place now: bitrates hot-swap in
     * the encoders, frame rate changes the RTP timestamp increment, a new
     * input format reconfigures the encoder. Structural changes (audio
     * on/off, channels, sample rate, auth) take effect via a server restart
     * by the caller.
     */
    fun apply(update: RtspConfig) {
        val old = config
        config = normalize(update)
        if (!running.get()) return
        if (config.videoBitrate != old.videoBitrate) {
            encoder.setBitrate(config.videoBitrate)
        }
        if (config.audioBitrateKbps != old.audioBitrateKbps) {
            aacEncoder.setBitrate(config.audioBitrateKbps)
        }
        if (config.inputFormat != old.inputFormat) {
            encoder.setInputFormat(config.inputFormat)
            reconfigureEncoder(config.videoWidth, config.videoHeight)
        }
    }

    private fun reconfigureEncoder(width: Int, height: Int) {
        config = config.copy(videoWidth = width, videoHeight = height)
        encoder.stop()
        encoder.configure(width, height, config.videoBitrate, config.videoFrameRate)
        encoder.setInputFormat(config.inputFormat)
        encoder.onEncodedFrame = { nalUnits ->
            distributeEncodedFrame(nalUnits)
        }
        if (!encoder.start()) {
            Log.e(TAG, "Encoder restart at ${width}x${height} failed; retrying once")
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
            if (!encoder.start()) {
                // Encoder stays stopped: encodeFrame() no-ops safely, and the cached
                // SPS/PPS keep serving SDP until a later reconfigure succeeds.
                Log.e(TAG, "Encoder restart at ${width}x${height} failed permanently; frames skipped until next reconfigure")
                return
            }
        }
        encoder.submitBlackFrame()
        Log.d(TAG, "Encoder reconfigured to ${width}x${height}")
    }

    private fun distributeEncodedFrame(nalUnits: List<H264Encoder.EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return

        rtpTimestamp += timestampIncrement

        if (nalUnits.any { it.isKeyFrame } && !firstKeyframeLogged) {
            firstKeyframeLogged = true
            Log.d(
                TAG,
                "First keyframe AU distributed: " +
                    nalUnits.joinToString { "${H264NalParser.nalType(it.data)}:${it.data.size}" }
            )
        }

        val auCount = distributedAus.incrementAndGet()
        if (auCount % 300L == 0L) {
            Log.d(
                TAG,
                "RTSP video stats: AUs=$auCount packets=${distributedPackets.get()} accepted=${acceptedFrames.get()} dropped=${droppedFrames.get()} rtpTimestamp=$rtpTimestamp playingClients=${getClientCount()}"
            )
        }

        val senderReport = if (System.currentTimeMillis() - lastSenderReportTime >= RTCP_SR_INTERVAL_MS) {
            lastSenderReportTime = System.currentTimeMillis()
            buildVideoSenderReport()
        } else {
            null
        }

        for (client in clients.values) {
            if (client.isPlaying) {
                if (senderReport != null) client.sendVideoRtcpPacket(senderReport)
                for ((index, nalUnit) in nalUnits.withIndex()) {
                    val marker = index == nalUnits.lastIndex
                    val packets = videoPacketizer.packetizeNalUnit(nalUnit.data, rtpTimestamp, marker)
                    distributedPackets.addAndGet(packets.size.toLong())
                    for (packet in packets) {
                        client.sendRtpPacket(packet)
                    }
                }
            }
        }
    }

    /** RFC 3550 sender report (no report blocks) for the video RTP stream. */
    private fun buildVideoSenderReport(): ByteArray {
        val ntpTimeMs = System.currentTimeMillis() + NTP_EPOCH_OFFSET_MS
        val ntpSeconds = ntpTimeMs / 1000
        val ntpFraction = ((ntpTimeMs % 1000) shl 32) / 1000

        val packet = ByteArray(28)
        packet[0] = 0x80.toByte() // V=2, P=0, RC=0
        packet[1] = 200.toByte()  // PT=SR
        packet[2] = 0
        packet[3] = 6             // length in 32-bit words minus one
        writeInt(packet, 4, videoPacketizer.wireSsrc)
        writeInt(packet, 8, ntpSeconds.toInt())
        writeInt(packet, 12, ntpFraction.toInt())
        writeInt(packet, 16, (rtpTimestamp and 0xFFFFFFFFL).toInt())
        writeInt(packet, 20, (videoPacketizer.sentPacketCount and 0xFFFFFFFFL).toInt())
        writeInt(packet, 24, (videoPacketizer.sentOctetCount and 0xFFFFFFFFL).toInt())
        return packet
    }

    private fun writeInt(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }

    private fun distributeEncodedAudioFrame(aacData: ByteArray) {
        audioTimestamp += audioTimestampIncrement

        for (client in clients.values) {
            if (client.isPlaying && client.isAudioSetup) {
                val packet = audioPacketizer.packetize(aacData, audioTimestamp)
                client.sendAudioRtpPacket(packet)
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
                            serverSocket = ServerSocket().apply {
                                reuseAddress = true
                                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 5)
                            }
                            Log.w(TAG, "Accept socket reopened after SocketException")
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

    /**
     * One client connection: reads the wire (via [RtspWireReader]), parses
     * (via [RtspRequestParser]), authorizes (via [RtspSessionAuthorizer]),
     * routes URIs (via [RtspUriPolicy]), and drives the SETUP/PLAY/TEARDOWN
     * state machine plus the interleaved-frame writers.
     */
    private inner class ClientSession(
        private val socket: Socket,
        private val sessionId: String
    ) {
        private var state = SessionState.INIT
        private var cSeq = 0
        private var lastCSeq = -1
        private var rtspSessionId = ""
        private var lastActivity = System.currentTimeMillis()
        private var lastRtcpActivity = 0L

        private val tracks = mutableMapOf<Int, TrackState>()

        init {
            // Default video track on channels 0-1
            tracks[0] = TrackState(trackId = 0, rtpChannel = 0, rtcpChannel = 1)
            // Default audio track on channels 2-3
            tracks[1] = TrackState(trackId = 1, rtpChannel = 2, rtcpChannel = 3)
        }

        val isVideoSetup: Boolean get() = tracks[0]?.isSetup == true
        val isAudioSetup: Boolean get() = tracks[1]?.isSetup == true

        private val videoRtpChannel: Int get() = tracks[0]?.rtpChannel ?: 0
        private val videoRtcpChannel: Int get() = tracks[0]?.rtcpChannel ?: 1
        private val audioRtpChannel: Int get() = tracks[1]?.rtpChannel ?: 2
        private val audioRtcpChannel: Int get() = tracks[1]?.rtcpChannel ?: 3

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
            val wireReader = RtspWireReader(input)

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
                        if (channel == videoRtcpChannel || channel == audioRtcpChannel) {
                            lastRtcpActivity = lastActivity
                        }
                        if (!wireReader.discardBytes(frameSize)) break

                        lastActivity = System.currentTimeMillis()
                        continue
                    }

                    val line = wireReader.readLine(firstByte) ?: break

                    lastActivity = System.currentTimeMillis()

                    if (line.isNotEmpty()) {
                        requestLines.add(line)
                    } else if (requestLines.isNotEmpty()) {
                        val contentLength = RtspRequestParser.extractContentLength(requestLines)
                        if (contentLength > 0) {
                            if (!wireReader.discardBytes(contentLength)) break
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

        private fun processRequest(lines: List<String>, output: OutputStream) {
            val request = RtspRequestParser.parse(lines) ?: return

            val parsedCSeq = request.headers["cseq"]?.toIntOrNull()
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

            if (authorizer.requiresAuthentication(request.method) &&
                !authorizer.authorize(request.method, request.uri, request.headers["authorization"])
            ) {
                sendUnauthorized(output)
                return
            }

            if (!RtspUriPolicy.isRequestUriAllowed(request.method, request.uri)) {
                sendResponse(output, "404 Not Found")
                return
            }

            when (request.method) {
                "OPTIONS" -> handleOptions(output)
                "DESCRIBE" -> handleDescribe(output)
                "SETUP" -> handleSetup(output, request.headers, request.uri)
                "PLAY" -> handlePlay(output, request.headers)
                "TEARDOWN" -> handleTeardown(output, request.headers)
                "GET_PARAMETER" -> if (isValidSession(request.headers)) sendOk(output) else sendResponse(output, "454 Session Not Found")
                "SET_PARAMETER" -> if (isValidSession(request.headers)) sendOk(output) else sendResponse(output, "454 Session Not Found")
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

        private fun handleDescribe(output: OutputStream) {
            if (rtspSessionId.isEmpty()) {
                rtspSessionId = sessionId + "_" + System.currentTimeMillis().toString(16)
            }

            val sdp = SdpBuilder.build(
                sessionId = sessionId,
                ip = socket.localAddress.hostAddress,
                videoBitrate = config.videoBitrate,
                audioEnabled = config.audioEnabled,
                audioSampleRateHz = config.audioSampleRateHz,
                audioChannelCount = config.audioChannelCount,
                sps = encoder.sps,
                pps = encoder.pps,
                audioSpecificConfig = aacEncoder.audioSpecificConfig,
            )
            sendResponse(
                output, "200 OK", mapOf(
                    "Content-Type" to "application/sdp",
                    "Content-Base" to "rtsp://${socket.localAddress.hostAddress}:$port/"
                ), sdp.toByteArray(Charsets.UTF_8)
            )
        }

        private fun handleSetup(output: OutputStream, headers: Map<String, String>, requestUri: String) {
            val trackId = RtspUriPolicy.resolveTrackId(requestUri)
            if (trackId == null) {
                sendResponse(output, "404 Not Found")
                return
            }

            // Reject audio track if audio is not enabled
            if (trackId == 1 && !config.audioEnabled) {
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

            val trackState = tracks[trackId] ?: run {
                sendResponse(output, "404 Not Found")
                return
            }

            val interleavedMatch = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
            if (interleavedMatch != null) {
                trackState.rtpChannel = interleavedMatch.groupValues[1].toInt()
                trackState.rtcpChannel = interleavedMatch.groupValues[2].toInt()
            }

            trackState.isSetup = true

            if (rtspSessionId.isEmpty()) {
                rtspSessionId = sessionId + "_" + System.currentTimeMillis().toString(16)
            }

            state = SessionState.READY

            sendResponse(
                output, "200 OK", mapOf(
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=${trackState.rtpChannel}-${trackState.rtcpChannel}",
                    "Session" to "$rtspSessionId;timeout=$SESSION_TIMEOUT_HEADER_SECONDS"
                )
            )
        }

        private fun handlePlay(output: OutputStream, headers: Map<String, String>) {
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

            val rtpInfoParts = mutableListOf<String>()
            val streamBase = buildAbsoluteRtspUrl("/${RtspUriPolicy.DEFAULT_STREAM_PATH}")
            val nextSeq = (videoPacketizer.currentSeq + 1) and 0xFFFF
            val nextRtpTime = (rtpTimestamp + timestampIncrement) and 0xFFFFFFFFL
            rtpInfoParts.add("url=$streamBase;seq=$nextSeq;rtptime=$nextRtpTime")

            if (isAudioSetup && config.audioEnabled) {
                val audioUrl = buildAbsoluteRtspUrl("/${RtspUriPolicy.DEFAULT_STREAM_PATH}/trackID=1")
                rtpInfoParts.add("url=$audioUrl;seq=${audioPacketizer.currentSeq};rtptime=$audioTimestamp")
            }

            sendResponse(
                output, "200 OK", mapOf(
                    "Session" to rtspSessionId,
                    "Range" to "npt=0.000-",
                    "RTP-Info" to rtpInfoParts.joinToString(",")
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

        private fun sendUnauthorized(output: OutputStream) {
            sendResponse(
                output, "401 Unauthorized",
                mapOf("WWW-Authenticate" to authorizer.challengeHeader())
            )
        }

        private fun isValidSession(headers: Map<String, String>): Boolean {
            if (rtspSessionId.isEmpty()) return false
            val sessionHeader = headers["session"] ?: return false
            val providedSession = sessionHeader.substringBefore(';').trim()
            return providedSession == rtspSessionId
        }

        private fun buildAbsoluteRtspUrl(requestUri: String): String {
            if (requestUri.startsWith("rtsp://", ignoreCase = true)) {
                return requestUri
            }
            val normalizedPath = if (requestUri.startsWith("/")) requestUri else "/$requestUri"
            return "rtsp://${socket.localAddress.hostAddress}:$port$normalizedPath"
        }

        /**
         * Writes one `$`-framed interleaved packet in a single write() call.
         * Writing the 4 header bytes individually produced several tiny TCP
         * segments per RTP packet under TCP_NODELAY.
         */
        private fun sendInterleaved(channel: Int, packet: ByteArray) {
            val output = outputStream ?: return
            val frame = ByteArray(4 + packet.size)
            frame[0] = INTERLEAVED_FRAME_MAGIC.toByte()
            frame[1] = channel.toByte()
            frame[2] = ((packet.size shr 8) and 0xFF).toByte()
            frame[3] = (packet.size and 0xFF).toByte()
            System.arraycopy(packet, 0, frame, 4, packet.size)
            try {
                synchronized(output) {
                    output.write(frame)
                    output.flush()
                }
            } catch (_: Exception) {
                isPlaying = false
            }
        }

        fun sendRtpPacket(packet: ByteArray) = sendInterleaved(videoRtpChannel, packet)

        fun sendAudioRtpPacket(packet: ByteArray) = sendInterleaved(audioRtpChannel, packet)

        fun sendVideoRtcpPacket(packet: ByteArray) = sendInterleaved(videoRtcpChannel, packet)

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

        private fun rfc1123Now(): String = checkNotNull(RFC_1123_FORMAT.get()).format(Date())
    }

    private enum class SessionState {
        INIT, READY, PLAYING
    }

    private class TrackState(
        val trackId: Int,
        var rtpChannel: Int,
        var rtcpChannel: Int,
        var isSetup: Boolean = false,
    )

    companion object {
        private const val TAG = "RtspServer"

        const val DEFAULT_PORT = StreamDefaults.RTSP_PORT

        private const val INTERLEAVED_FRAME_MAGIC = 0x24

        // ── Session lifecycle constants (one home) ──
        // MAX_CLIENTS caps concurrent client connections; the accept loop rejects beyond it.
        private const val MAX_CLIENTS = 4
        // SESSION_TIMEOUT_MS is the enforced idle timeout (socket read timeout and
        // the handle() loop check). We advertise a slightly smaller timeout in the
        // SETUP "Session" header so an idle-but-alive client times itself out
        // before we would drop it — the 5s skew is deliberate grace.
        private const val SESSION_TIMEOUT_MS = 65_000L
        private const val SESSION_TIMEOUT_HEADER_SECONDS = 60L
        private const val RTCP_SR_INTERVAL_MS = 5_000L

        private const val NTP_EPOCH_OFFSET_MS = 2_208_988_800_000L // 1900-01-01 vs 1970-01-01

        private val RFC_1123_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
            }
        }
    }
}

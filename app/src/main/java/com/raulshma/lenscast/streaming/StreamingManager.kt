package com.raulshma.lenscast.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed class FrameData {
    data class YuvFrame(
        val yuvData: ByteArray,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val quality: Int,
        val overlay: OverlaySettings,
        val clientCount: Int,
    ) : FrameData()
}

class StreamingManager(
    private val context: Context,
    private val thermalMonitor: ThermalMonitor,
) {

    private val audioStreamingManager = AudioStreamingManager(context)
    private val serviceDiscoveryManager = ServiceDiscoveryManager(context)
    private val webStreamingEnabled = AtomicBoolean(true)
    private val mdnsEnabled = AtomicBoolean(true)
    @Volatile
    private var currentAuthSettings = StreamAuthSettings()
    @Volatile
    private var currentOverlaySettings = OverlaySettings()
    val networkQualityMonitor = NetworkQualityMonitor()
    private val adaptiveBitrateController = AdaptiveBitrateController(networkQualityMonitor)
    private val apiController by lazy { WebApiController(context) }
    private var server: StreamingServer = createServer(StreamDefaults.WEB_PORT)
    private val webStreamingActive = AtomicBoolean(false)
    private val rtspStreamingActive = AtomicBoolean(false)
    private val jpegQuality = AtomicInteger(StreamDefaults.JPEG_QUALITY)
    private val streamAudioEnabled = AtomicBoolean(true)
    private val streamAudioBitrateKbps = AtomicInteger(StreamDefaults.AUDIO_BITRATE_KBPS)
    private val streamAudioChannels = AtomicInteger(StreamDefaults.AUDIO_CHANNELS)
    private val streamAudioEchoCancellation = AtomicBoolean(true)
    @Volatile
    private var recordingAudioCaptureActive = false
    private var currentPort: Int = StreamDefaults.WEB_PORT

    private val rtspEnabled = AtomicBoolean(false)
    @Volatile
    private var currentRtspPort: Int = RtspServer.DEFAULT_PORT
    @Volatile
    private var currentRtspInputFormat: RtspInputFormat = RtspInputFormat.AUTO
    @Volatile
    private var currentRtspFrameRate: Int = StreamDefaults.STREAM_FPS
    private var rtspServer: RtspServer? = null
    @Volatile
    private var rtspAudioStream: InputStream? = null

    private val lastFrameTimeMs = AtomicLong(0L)
    private val minFrameIntervalMs = AtomicLong(1000L / StreamDefaults.STREAM_FPS)
    private val maxBufferSize = 4 * 1024 * 1024
    private var reusableBuffer = ByteArrayOutputStream(256 * 1024)
    private var reusableYuvBuffer = ByteArrayOutputStream(256 * 1024)
    private val bufferLock = Any()
    private var lastReportedClientCount = -1

    private val frameQueueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameQueue = Channel<FrameData>(capacity = Channel.CONFLATED)
    private val droppedFrameCount = AtomicInteger(0)
    private val processedFrameCount = AtomicInteger(0)

    init {
        frameQueueScope.launch {
            for (frame in frameQueue) {
                processFrameInternal(frame)
            }
        }
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _isWebStreamingActive = MutableStateFlow(false)
    val isWebStreamingActive: StateFlow<Boolean> = _isWebStreamingActive

    private val _isWebEnabled = MutableStateFlow(true)
    val isWebEnabled: StateFlow<Boolean> = _isWebEnabled

    private val _isRtspEnabled = MutableStateFlow(false)
    val isRtspEnabled: StateFlow<Boolean> = _isRtspEnabled

    private val _streamUrl = MutableStateFlow("")
    val streamUrl: StateFlow<String> = _streamUrl

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    private val _audioStreamUrl = MutableStateFlow("")
    val audioStreamUrl: StateFlow<String> = _audioStreamUrl

    private val _isAudioStreaming = MutableStateFlow(false)
    val isAudioStreaming: StateFlow<Boolean> = _isAudioStreaming

    private val _rtspUrl = MutableStateFlow("")
    val rtspUrl: StateFlow<String> = _rtspUrl

    private val _isRtspRunning = MutableStateFlow(false)
    val isRtspRunning: StateFlow<Boolean> = _isRtspRunning

    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames

    private val _processedFrames = MutableStateFlow(0)
    val processedFrames: StateFlow<Int> = _processedFrames

    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> = adaptiveBitrateController.state

    fun getNetworkStatsSnapshot(): NetworkQualityMonitor.NetworkStatsSnapshot = networkQualityMonitor.getStatsSnapshot()

    fun isLiveStreaming(): Boolean = webStreamingActive.get() || rtspStreamingActive.get()

    fun isWebStreamingEnabled(): Boolean = webStreamingEnabled.get()

    fun isWebStreamActive(): Boolean = webStreamingActive.get()

    private fun updateStreamingState() {
        val anyActive = webStreamingActive.get() || rtspStreamingActive.get()
        _isStreaming.value = anyActive
        _isWebStreamingActive.value = webStreamingActive.get()
    }

    fun setPort(port: Int) {
        if (isLiveStreaming()) {
            Log.w(TAG, "Cannot change port while streaming")
            return
        }
        if (port != currentPort) {
            val wasServerRunning = _isServerRunning.value
            if (wasServerRunning) {
                server.stopServer()
                _isServerRunning.value = false
            }
            currentPort = port
            server = createServer(port)
            _streamUrl.value = buildVideoUrl()
            if (_isAudioStreaming.value) {
                _audioStreamUrl.value = buildAudioUrl()
            }
            if (wasServerRunning) {
                val restarted = server.startServer()
                _isServerRunning.value = restarted
                if (!restarted) {
                    Log.e(TAG, "Failed to restart streaming server on new port $port")
                }
            }
            Log.d(TAG, "Streaming port set to $port")
        }
    }

    fun setAdaptiveBitrateEnabled(enabled: Boolean) {
        adaptiveBitrateController.setEnabled(enabled)
        Log.d(TAG, "Adaptive bitrate ${if (enabled) "enabled" else "disabled"}")
    }

    fun ensureServerRunning(): Boolean {
        if (_isServerRunning.value) {
            if (_streamUrl.value.isBlank()) {
                _streamUrl.value = buildVideoUrl()
            }
            return true
        }

        val started = server.startServer()
        if (!started) {
            return false
        }

        _isServerRunning.value = true
        _streamUrl.value = buildVideoUrl()
        Log.d(TAG, "Streaming server ready at ${_streamUrl.value}")
        return true
    }

    fun startStreaming(): Boolean {
        if (!webStreamingEnabled.get() && !rtspEnabled.get()) {
            Log.w(TAG, "Cannot start streaming: both web and RTSP outputs are disabled")
            return false
        }

        if (webStreamingEnabled.get()) {
            if (!startWebStreaming()) return false
        }
        if (rtspEnabled.get()) {
            startRtspStreaming()
        }

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
        server.stopServer()
        unregisterMdnsService()
        _isServerRunning.value = false
        Log.d(TAG, "Streaming stopped")
    }

    fun pauseStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
        Log.d(TAG, "Live streaming paused (server still running)")
    }

    fun startWebStreaming(): Boolean {
        if (!webStreamingEnabled.get()) {
            Log.w(TAG, "Cannot start web streaming: web streaming is disabled")
            return false
        }
        if (webStreamingActive.getAndSet(true)) return true

        if (!ensureServerRunning()) {
            webStreamingActive.set(false)
            updateStreamingState()
            return false
        }

        refreshAudioStreamingState()
        if (mdnsEnabled.get()) {
            registerMdnsService(currentPort)
        }

        updateStreamingState()
        Log.d(TAG, "Web streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopWebStreaming() {
        if (!webStreamingActive.getAndSet(false)) return
        audioStreamingManager.stop()
        clearWebAudioState()
        _streamUrl.value = ""
        _clientCount.value = 0
        lastReportedClientCount = -1
        unregisterMdnsService()
        updateStreamingState()
        Log.d(TAG, "Web streaming stopped")
    }

    fun startRtspStreaming(): Boolean {
        if (!rtspEnabled.get()) {
            Log.w(TAG, "Cannot start RTSP streaming: RTSP is disabled")
            return false
        }
        if (rtspStreamingActive.getAndSet(true)) return true
        startRtspServer()
        updateStreamingState()
        Log.d(TAG, "RTSP streaming started")
        return true
    }

    fun stopRtspStreaming() {
        if (!rtspStreamingActive.getAndSet(false)) return
        stopRtspServer()
        updateStreamingState()
        Log.d(TAG, "RTSP streaming stopped")
    }

    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        if (!webStreamingActive.get()) return

        val clientCount = server.getClientCount()
        if (clientCount == 0) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTimeMs.get()

        val baseInterval = minFrameIntervalMs.get()
        val thermalAdjustedInterval = thermalMonitor.getAdjustedFrameDelay(baseInterval)
        val adaptiveInterval = adaptiveBitrateController.getAdaptiveFrameInterval(baseInterval, thermalAdjustedInterval)

        if (elapsed < adaptiveInterval) {
            droppedFrameCount.incrementAndGet()
            return
        }

        lastFrameTimeMs.set(now)

        val baseQuality = jpegQuality.get()
        val thermalAdjustedQuality = thermalMonitor.getAdjustedQuality(baseQuality)
        val quality = adaptiveBitrateController.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)

        val frame = FrameData.YuvFrame(yuvData.copyOf(), width, height, rotation, quality, currentOverlaySettings, clientCount)
        frameQueue.trySend(frame)
    }

    fun pushFrameToRtsp(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        if (!rtspStreamingActive.get()) return
        val rtsp = rtspServer ?: return
        rtsp.pushFrame(yuvData, width, height, rotation)
    }

    private fun processFrameInternal(frame: FrameData) {
        try {
            when (frame) {
                is FrameData.YuvFrame -> {
                    var jpegData = yuvToJpeg(frame.yuvData, frame.width, frame.height, frame.quality, frame.rotation) ?: return

                    if (frame.overlay.enabled) {
                        val decoded = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                        if (decoded != null) {
                            val withOverlay = StreamOverlayRenderer.applyOverlay(decoded, frame.overlay, frame.clientCount)
                            if (withOverlay !== decoded) decoded.recycle()
                            jpegData = bitmapToJpegReuse(withOverlay, frame.quality.coerceAtLeast(85))
                            if (withOverlay !== decoded && withOverlay.isRecycled.not()) withOverlay.recycle()
                        }
                    }

                    server.updateFrame(jpegData)
                    processedFrameCount.incrementAndGet()

                    if (frame.clientCount != lastReportedClientCount) {
                        lastReportedClientCount = frame.clientCount
                        _clientCount.value = frame.clientCount
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            droppedFrameCount.incrementAndGet()
        }

        val dropped = droppedFrameCount.get()
        val processed = processedFrameCount.get()
        if (dropped != _droppedFrames.value && dropped % 30 == 0) {
            _droppedFrames.value = dropped
        }
        if (processed != _processedFrames.value && processed % 30 == 0) {
            _processedFrames.value = processed
        }
    }

    private fun yuvToJpeg(yuvData: ByteArray, width: Int, height: Int, quality: Int, rotation: Int = 0): ByteArray? {
        return try {
            if (rotation != 0) {
                val bitmap = yuvToRotatedBitmap(yuvData, width, height, quality, rotation) ?: return null
                synchronized(bufferLock) {
                    reusableBuffer.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, reusableBuffer)
                    bitmap.recycle()
                    val result = reusableBuffer.toByteArray()
                    capBuffer(reusableBuffer)
                    result
                }
            } else {
                val yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
                synchronized(bufferLock) {
                    reusableBuffer.reset()
                    yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, reusableBuffer)
                    val result = reusableBuffer.toByteArray()
                    capBuffer(reusableBuffer)
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV to JPEG conversion failed", e)
            null
        }
    }

    private fun yuvToRotatedBitmap(yuvData: ByteArray, width: Int, height: Int, quality: Int, rotation: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
            val jpegData: ByteArray
            synchronized(bufferLock) {
                reusableYuvBuffer.reset()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, reusableYuvBuffer)
                jpegData = reusableYuvBuffer.toByteArray()
                capBuffer(reusableYuvBuffer)
            }
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return null
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "YUV to rotated bitmap conversion failed", e)
            null
        }
    }

    fun setJpegQuality(quality: Int) {
        jpegQuality.set(quality.coerceIn(10, 100))
    }

    fun setStreamFrameRate(fps: Int) {
        minFrameIntervalMs.set(if (fps > 0) 1000L / fps else 1000L / StreamDefaults.STREAM_FPS)
    }

    fun setAdaptiveDefaultFrameRate(fps: Int) {
        adaptiveBitrateController.setDefaultFrameRate(fps)
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        streamAudioEnabled.set(enabled)
        onWebAudioChanged()
        // The only exception to the onRtspAudioChanged ladder: a toggle changes
        // the RTSP audio track either way, so restart even when turning off.
        if (rtspStreamingActive.get()) {
            restartRtspServer()
        }
    }

    fun setWebStreamingEnabled(enabled: Boolean) {
        val changed = webStreamingEnabled.getAndSet(enabled) != enabled
        if (!changed) return

        _isWebEnabled.value = enabled
        server.setWebStreamingEnabled(enabled)

        if (!enabled && webStreamingActive.get()) {
            stopWebStreaming()
        }
    }

    fun setStreamAudioBitrateKbps(bitrateKbps: Int) {
        streamAudioBitrateKbps.set(bitrateKbps.coerceIn(MIN_AUDIO_BITRATE_KBPS, MAX_AUDIO_BITRATE_KBPS))
        onWebAudioChanged()
        // RTSP supports live bitrate updates; no restart needed.
        onRtspAudioChanged(liveUpdate = { rtspServer?.setAudioBitrate(streamAudioBitrateKbps.get()) })
    }

    fun setStreamAudioChannels(channels: Int) {
        streamAudioChannels.set(channels.coerceIn(MIN_AUDIO_CHANNELS, MAX_AUDIO_CHANNELS))
        onWebAudioChanged()
        // Channel count is an encoder config; RTSP restart required.
        onRtspAudioChanged()
    }

    fun setStreamAudioEchoCancellation(enabled: Boolean) {
        streamAudioEchoCancellation.set(enabled)
        onWebAudioChanged()
        // Echo cancellation is an audio-capture config; RTSP restart required.
        onRtspAudioChanged()
    }

    // ── Stream-audio change policy: one decision point for every audio setting ──

    private fun onWebAudioChanged() {
        if (webStreamingActive.get()) {
            refreshAudioStreamingState()
        } else {
            clearWebAudioState()
        }
    }

    /**
     * React to an audio setting that affects the RTSP track. [liveUpdate] is
     * used when the running server can apply the change without a restart;
     * otherwise the server restarts so the encoder picks the new config up.
     */
    private fun onRtspAudioChanged(liveUpdate: (() -> Unit)? = null) {
        if (!rtspStreamingActive.get() || !streamAudioEnabled.get()) return
        if (liveUpdate != null) {
            liveUpdate()
        } else {
            restartRtspServer()
        }
    }

    private fun restartRtspServer() {
        stopRtspServer()
        startRtspServer()
    }

    private fun clearWebAudioState() {
        _isAudioStreaming.value = false
        _audioStreamUrl.value = ""
    }

    private fun audioConfig(): AudioStreamingManager.Config {
        return AudioStreamingManager.Config(
            bitrateKbps = streamAudioBitrateKbps.get(),
            channelCount = streamAudioChannels.get(),
            echoCancellation = streamAudioEchoCancellation.get(),
        )
    }

    fun setRecordingAudioCaptureActive(active: Boolean) {
        val wasActive = recordingAudioCaptureActive
        recordingAudioCaptureActive = active

        when {
            active && !wasActive -> {
                if (_isAudioStreaming.value) {
                    audioStreamingManager.stop()
                    _isAudioStreaming.value = false
                    _audioStreamUrl.value = ""
                    Log.d(TAG, "Paused live audio streaming so recording can capture the microphone")
                }
            }
            !active && wasActive -> {
                refreshAudioStreamingState()
                Log.d(TAG, "Recording microphone capture finished; refreshed live audio streaming state")
            }
        }
    }

    fun updateAuthSettings(settings: StreamAuthSettings) {
        currentAuthSettings = settings
        applyAuthSettings(server, settings)
        applyRtspAuthSettings(rtspServer, settings)
    }

    fun setOverlaySettings(settings: OverlaySettings) {
        currentOverlaySettings = settings
        Log.d(TAG, "Overlay settings updated: enabled=${settings.enabled}, position=${settings.position}")
    }

    fun setRtspEnabled(enabled: Boolean) {
        val changed = rtspEnabled.getAndSet(enabled) != enabled
        if (!changed) return
        _isRtspEnabled.value = enabled
        if (!enabled && rtspStreamingActive.get()) {
            stopRtspStreaming()
        }
    }

    fun setRtspPort(port: Int) {
        if (port == currentRtspPort) return
        currentRtspPort = port
        if (rtspEnabled.get() && rtspServer != null) {
            stopRtspServer()
            startRtspServer()
        }
    }

    fun setRtspInputFormat(format: RtspInputFormat) {
        if (format == currentRtspInputFormat) return
        currentRtspInputFormat = format
        rtspServer?.setInputFormat(format)
    }

    fun setRtspFrameRate(fps: Int) {
        // Retain even when RTSP is not running so the next start picks it up —
        // every RTSP setting is retained, no silent drops.
        currentRtspFrameRate = fps
        rtspServer?.setFrameRate(fps)
    }

    fun setMdnsEnabled(enabled: Boolean) {
        val changed = mdnsEnabled.getAndSet(enabled) != enabled
        if (!changed) return

        if (enabled && webStreamingActive.get() && webStreamingEnabled.get()) {
            registerMdnsService(currentPort)
        } else {
            unregisterMdnsService()
        }
        Log.d(TAG, "mDNS service discovery ${if (enabled) "enabled" else "disabled"}")
    }

    private fun registerMdnsService(port: Int) {
        serviceDiscoveryManager.registerService(port = port)
    }

    private fun unregisterMdnsService() {
        serviceDiscoveryManager.unregisterService()
    }

    private fun createServer(port: Int): StreamingServer {
        return StreamingServer(port, context, audioStreamingManager, apiController).also {
            it.networkQualityMonitor = networkQualityMonitor
            applyAuthSettings(it, currentAuthSettings)
            it.setWebStreamingEnabled(webStreamingEnabled.get())
        }
    }

    private fun applyAuthSettings(server: StreamingServer, settings: StreamAuthSettings) {
        if (settings.enabled && settings.username.isNotEmpty() && settings.passwordHash.isNotEmpty()) {
            server.authUsername = settings.username
            server.authPasswordHash = settings.passwordHash
        } else {
            server.authUsername = null
            server.authPasswordHash = null
        }
    }

    private fun applyRtspAuthSettings(server: RtspServer?, settings: StreamAuthSettings) {
        val target = server ?: return
        target.setAuthSettings(
            enabled = settings.enabled,
            username = settings.username,
            passwordHash = settings.passwordHash,
            digestHa1 = settings.rtspDigestHa1,
        )
    }

    private fun bitmapToJpegReuse(bitmap: Bitmap, quality: Int): ByteArray {
        synchronized(bufferLock) {
            reusableBuffer.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, reusableBuffer)
            val result = reusableBuffer.toByteArray()
            capBuffer(reusableBuffer)
            return result
        }
    }

    private fun capBuffer(buffer: ByteArrayOutputStream) {
        if (buffer.size() > maxBufferSize) {
            buffer.reset()
        }
    }

    fun applyBatteryOptimization(result: com.raulshma.lenscast.core.BatteryOptimizationResult?) {
        if (result == null) return
        setJpegQuality(result.suggestedJpegQuality)
        Log.d(TAG, "Battery optimization applied: quality=${result.suggestedJpegQuality} (${result.message})")
    }

    fun release() {
        frameQueueScope.cancel()
        frameQueue.close()
        audioStreamingManager.release()
        stopStreaming()
    }

    private fun refreshAudioStreamingState() {
        audioStreamingManager.stop()

        if (!webStreamingActive.get() || !webStreamingEnabled.get() || !streamAudioEnabled.get() || recordingAudioCaptureActive) {
            clearWebAudioState()
            return
        }

        val audioStarted = audioStreamingManager.start(audioConfig())
        _isAudioStreaming.value = audioStarted
        _audioStreamUrl.value = if (audioStarted) buildAudioUrl() else ""
    }

    private fun buildVideoUrl(): String {
        return NetworkUtils.getStreamingUrl(currentPort) ?: "http://localhost:$currentPort/stream"
    }

    private fun buildAudioUrl(): String {
        return NetworkUtils.getAudioUrl(currentPort) ?: "http://localhost:$currentPort/audio"
    }

    private fun buildRtspUrl(): String {
        val ip = NetworkUtils.getLocalIpAddress() ?: "localhost"
        return "rtsp://$ip:$currentRtspPort/${RtspServer.DEFAULT_STREAM_PATH}"
    }

    private fun startRtspServer() {
        if (rtspServer != null) return
        val server = RtspServer(currentRtspPort)
        applyRtspAuthSettings(server, currentAuthSettings)
        server.setInputFormat(currentRtspInputFormat)
        server.setFrameRate(currentRtspFrameRate)

        // Configure audio for RTSP if enabled
        val audioEnabled = streamAudioEnabled.get() && !recordingAudioCaptureActive
        if (audioEnabled) {
            // Ensure audio capture is running
            if (!audioStreamingManager.isRunning()) {
                audioStreamingManager.start(audioConfig())
            }
            if (audioStreamingManager.isRunning()) {
                val audioStream = audioStreamingManager.openStream()
                if (audioStream != null) {
                    rtspAudioStream = audioStream
                    server.setAudioEnabled(true)
                    server.setAudioConfig(
                        audioStreamingManager.getSampleRateHz(),
                        audioStreamingManager.getChannelCount(),
                        streamAudioBitrateKbps.get()
                    )
                    server.setAudioStream(audioStream)
                }
            }
        }

        if (server.start()) {
            rtspServer = server
            _rtspUrl.value = buildRtspUrl()
            _isRtspRunning.value = true
            Log.d(TAG, "RTSP server started on port $currentRtspPort (audio=${audioEnabled && rtspAudioStream != null})")
        } else {
            rtspAudioStream?.close()
            rtspAudioStream = null
            Log.e(TAG, "Failed to start RTSP server on port $currentRtspPort")
        }
    }

    private fun stopRtspServer() {
        rtspServer?.stop()
        rtspServer = null
        rtspAudioStream?.close()
        rtspAudioStream = null
        // If audio was started only for RTSP and web streaming is not active, stop it
        if (!webStreamingActive.get()) {
            audioStreamingManager.stop()
            clearWebAudioState()
        }
        _rtspUrl.value = ""
        _isRtspRunning.value = false
    }

    companion object {
        private const val TAG = "StreamingManager"
        private const val MIN_AUDIO_BITRATE_KBPS = 32
        private const val MAX_AUDIO_BITRATE_KBPS = 320
        private const val MIN_AUDIO_CHANNELS = 1
        private const val MAX_AUDIO_CHANNELS = 2
    }
}

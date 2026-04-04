package com.raulshma.lenscast.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.AdaptiveBitrateController.AdaptiveBitrateConfig
import com.raulshma.lenscast.streaming.AdaptiveBitrateController.AdaptiveResult
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamingManager(private val context: Context) {

    private val audioStreamingManager = AudioStreamingManager(context)
    private val webStreamingEnabled = AtomicBoolean(true)
    @Volatile
    private var currentAuthSettings = StreamAuthSettings()
    private var server: StreamingServer = createServer(DEFAULT_PORT)
    private val streamingActive = AtomicBoolean(false)
    private val jpegQuality = AtomicInteger(DEFAULT_JPEG_QUALITY)
    private val streamAudioEnabled = AtomicBoolean(true)
    private val streamAudioBitrateKbps = AtomicInteger(DEFAULT_AUDIO_BITRATE_KBPS)
    private val streamAudioChannels = AtomicInteger(DEFAULT_AUDIO_CHANNELS)
    private val streamAudioEchoCancellation = AtomicBoolean(true)
    @Volatile
    private var recordingAudioCaptureActive = false
    private var currentPort: Int = DEFAULT_PORT

    private val rtspEnabled = AtomicBoolean(false)
    @Volatile
    private var currentRtspPort: Int = RtspServer.DEFAULT_PORT
    @Volatile
    private var currentRtspInputFormat: RtspInputFormat = RtspInputFormat.AUTO
    private var rtspServer: RtspServer? = null

    private val lastFrameTimeMs = AtomicLong(0L)
    private val minFrameIntervalMs = AtomicLong(1000L / DEFAULT_STREAM_FPS)
    private val reusableBuffer = ByteArrayOutputStream(256 * 1024)
    private val reusableYuvBuffer = ByteArrayOutputStream(256 * 1024)
    private val bufferLock = Any()
    private var lastReportedClientCount = -1

    var thermalMonitor: ThermalMonitor? = null
    val networkQualityMonitor = NetworkQualityMonitor()
    private val adaptiveBitrateController = AdaptiveBitrateController(networkQualityMonitor)

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

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

    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> = adaptiveBitrateController.state

    fun getAdaptiveBitrateState(): AdaptiveBitrateController.AdaptiveState = adaptiveBitrateController.state.value

    fun getNetworkStatsSnapshot(): NetworkQualityMonitor.NetworkStatsSnapshot = networkQualityMonitor.getStatsSnapshot()

    fun isLiveStreaming(): Boolean = streamingActive.get()

    fun isWebStreamingEnabled(): Boolean = webStreamingEnabled.get()

    fun isWebStreamingActive(): Boolean = streamingActive.get() && webStreamingEnabled.get() && _isServerRunning.value

    fun setPort(port: Int) {
        if (streamingActive.get()) {
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

    fun isAdaptiveBitrateEnabled(): Boolean = adaptiveBitrateController.isEnabled

    fun setAdaptiveBitrateConfig(config: AdaptiveBitrateConfig) {
        adaptiveBitrateController.reset()
        Log.d(TAG, "Adaptive bitrate config updated")
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

        if (streamingActive.getAndSet(true)) return true
        _isStreaming.value = true

        if (webStreamingEnabled.get()) {
            if (!ensureServerRunning()) {
                streamingActive.set(false)
                _isStreaming.value = false
                return false
            }
        } else {
            _streamUrl.value = ""
        }

        refreshAudioStreamingState()

        if (rtspEnabled.get()) {
            startRtspServer()
        }

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        if (!streamingActive.getAndSet(false)) return
        _isStreaming.value = false

        stopRtspServer()
        audioStreamingManager.stop()
        server.stopServer()
        _streamUrl.value = ""
        _audioStreamUrl.value = ""
        _clientCount.value = 0
        lastReportedClientCount = -1
        _isServerRunning.value = false
        _isAudioStreaming.value = false

        Log.d(TAG, "Streaming stopped")
    }

    fun pauseStreaming() {
        if (!streamingActive.getAndSet(false)) return
        _isStreaming.value = false
        audioStreamingManager.stop()
        _clientCount.value = 0
        _audioStreamUrl.value = ""
        _isAudioStreaming.value = false
        Log.d(TAG, "Live streaming paused (server still running)")
    }

    fun pushFrame(bitmap: Bitmap) {
        if (!streamingActive.get() || !webStreamingEnabled.get()) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTimeMs.get()

        val baseInterval = minFrameIntervalMs.get()
        val thermalAdjustedInterval = thermalMonitor?.getAdjustedFrameDelay(baseInterval) ?: baseInterval
        val adaptiveInterval = adaptiveBitrateController.getAdaptiveFrameInterval(baseInterval, thermalAdjustedInterval)

        if (elapsed < adaptiveInterval) return
        lastFrameTimeMs.set(now)

        val clientCount = server.getClientCount()
        if (clientCount == 0) return

        val baseQuality = jpegQuality.get()
        val thermalAdjustedQuality = thermalMonitor?.getAdjustedQuality(baseQuality) ?: baseQuality
        val quality = adaptiveBitrateController.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)

        val jpegData = bitmapToJpegReuse(bitmap, quality)
        server.updateFrame(jpegData)

        if (clientCount != lastReportedClientCount) {
            lastReportedClientCount = clientCount
            _clientCount.value = clientCount
        }
    }

    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        if (!streamingActive.get() || !webStreamingEnabled.get()) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTimeMs.get()

        val baseInterval = minFrameIntervalMs.get()
        val thermalAdjustedInterval = thermalMonitor?.getAdjustedFrameDelay(baseInterval) ?: baseInterval
        val adaptiveInterval = adaptiveBitrateController.getAdaptiveFrameInterval(baseInterval, thermalAdjustedInterval)

        if (elapsed < adaptiveInterval) return
        lastFrameTimeMs.set(now)

        val clientCount = server.getClientCount()
        if (clientCount == 0) return

        val baseQuality = jpegQuality.get()
        val thermalAdjustedQuality = thermalMonitor?.getAdjustedQuality(baseQuality) ?: baseQuality
        val quality = adaptiveBitrateController.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)

        val jpegData = yuvToJpeg(yuvData, width, height, quality, rotation) ?: return
        server.updateFrame(jpegData)

        if (clientCount != lastReportedClientCount) {
            lastReportedClientCount = clientCount
            _clientCount.value = clientCount
        }
    }

    fun pushFrameToRtsp(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        if (!rtspEnabled.get()) return
        val rtsp = rtspServer ?: return
        rtsp.pushFrame(yuvData, width, height, rotation)
    }

    private fun yuvToJpeg(yuvData: ByteArray, width: Int, height: Int, quality: Int, rotation: Int = 0): ByteArray? {
        return try {
            if (rotation != 0) {
                val bitmap = yuvToRotatedBitmap(yuvData, width, height, quality, rotation) ?: return null
                synchronized(bufferLock) {
                    reusableBuffer.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, reusableBuffer)
                    bitmap.recycle()
                    reusableBuffer.toByteArray()
                }
            } else {
                val yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
                synchronized(bufferLock) {
                    reusableBuffer.reset()
                    yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, reusableBuffer)
                    reusableBuffer.toByteArray()
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
        minFrameIntervalMs.set(if (fps > 0) 1000L / fps else 1000L / DEFAULT_STREAM_FPS)
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        streamAudioEnabled.set(enabled)
        if (streamingActive.get()) {
            refreshAudioStreamingState()
        } else {
            _isAudioStreaming.value = false
            _audioStreamUrl.value = ""
        }
    }

    fun setWebStreamingEnabled(enabled: Boolean) {
        val changed = webStreamingEnabled.getAndSet(enabled) != enabled
        if (!changed) return

        _isWebEnabled.value = enabled
        server.setWebStreamingEnabled(enabled)

        if (!enabled) {
            if (streamingActive.getAndSet(false)) {
                _isStreaming.value = false
            }
            audioStreamingManager.stop()
            _isAudioStreaming.value = false
            _audioStreamUrl.value = ""
            _streamUrl.value = ""
            _clientCount.value = 0
            lastReportedClientCount = -1
        } else if (streamingActive.get()) {
            ensureServerRunning()
            _streamUrl.value = buildVideoUrl()
            refreshAudioStreamingState()
        }
    }

    fun setStreamAudioBitrateKbps(bitrateKbps: Int) {
        streamAudioBitrateKbps.set(bitrateKbps.coerceIn(32, 320))
        if (streamingActive.get() && streamAudioEnabled.get()) {
            refreshAudioStreamingState()
        }
    }

    fun setStreamAudioChannels(channels: Int) {
        streamAudioChannels.set(channels.coerceIn(1, 2))
        if (streamingActive.get() && streamAudioEnabled.get()) {
            refreshAudioStreamingState()
        }
    }

    fun setStreamAudioEchoCancellation(enabled: Boolean) {
        streamAudioEchoCancellation.set(enabled)
        if (streamingActive.get() && streamAudioEnabled.get()) {
            refreshAudioStreamingState()
        }
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

    fun reduceBitrate(multiplier: Float) {
        val current = jpegQuality.get()
        jpegQuality.set((current * multiplier).toInt().coerceIn(10, 100))
        Log.d(TAG, "Thermal: JPEG quality adjusted to ${jpegQuality.get()}")
        rtspServer?.setBitrate((2_000_000 * multiplier).toInt())
    }

    fun reduceFrameRate(multiplier: Float) {
        val baseInterval = 1000L / DEFAULT_STREAM_FPS
        minFrameIntervalMs.set(if (multiplier > 0f) (baseInterval / multiplier).toLong() else Long.MAX_VALUE)
        Log.d(TAG, "Thermal: frame interval adjusted to ${minFrameIntervalMs.get()}ms (factor $multiplier)")
    }

    fun restoreNormalSettings() {
        jpegQuality.set(DEFAULT_JPEG_QUALITY)
        minFrameIntervalMs.set(1000L / DEFAULT_STREAM_FPS)
        rtspServer?.setBitrate(2_000_000)
        Log.d(TAG, "Thermal: settings restored to normal")
    }

    fun updateAuthSettings(settings: StreamAuthSettings) {
        currentAuthSettings = settings
        applyAuthSettings(server, settings)
        applyRtspAuthSettings(rtspServer, settings)
    }

    fun setRtspEnabled(enabled: Boolean) {
        rtspEnabled.set(enabled)
        _isRtspEnabled.value = enabled
        if (enabled && streamingActive.get()) {
            startRtspServer()
        } else {
            stopRtspServer()
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

    private fun createServer(port: Int): StreamingServer {
        return StreamingServer(port, context, audioStreamingManager).also {
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
            return reusableBuffer.toByteArray()
        }
    }



    fun applyBatteryOptimization(result: com.raulshma.lenscast.core.BatteryOptimizationResult?) {
        if (result == null) return
        setJpegQuality(result.suggestedJpegQuality)
        Log.d(TAG, "Battery optimization applied: quality=${result.suggestedJpegQuality}, " +
                "bitrate=${result.suggestedBitrate}, fps=${result.suggestedFrameRate}")
    }

    fun getStreamUrls(): StreamUrls {
        val port = currentPort
        return StreamUrls(
            streamUrl = buildVideoUrl(),
            audioUrl = if (_isAudioStreaming.value) buildAudioUrl() else "",
            snapshotUrl = NetworkUtils.getSnapshotUrl(port) ?: "",
            controlUrl = NetworkUtils.getLocalIpAddress()?.let { "http://$it:$port/" } ?: ""
        )
    }

    fun release() {
        audioStreamingManager.release()
        stopStreaming()
        thermalMonitor?.stopMonitoring()
    }

    private fun refreshAudioStreamingState() {
        audioStreamingManager.stop()

        if (!streamingActive.get() || !webStreamingEnabled.get() || !streamAudioEnabled.get() || recordingAudioCaptureActive) {
            _isAudioStreaming.value = false
            _audioStreamUrl.value = ""
            return
        }

        val audioStarted = audioStreamingManager.start(
            AudioStreamingManager.Config(
                bitrateKbps = streamAudioBitrateKbps.get(),
                channelCount = streamAudioChannels.get(),
                echoCancellation = streamAudioEchoCancellation.get(),
            )
        )
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
        if (server.start()) {
            rtspServer = server
            _rtspUrl.value = buildRtspUrl()
            _isRtspRunning.value = true
            Log.d(TAG, "RTSP server started on port $currentRtspPort")
        } else {
            Log.e(TAG, "Failed to start RTSP server on port $currentRtspPort")
        }
    }

    private fun stopRtspServer() {
        rtspServer?.stop()
        rtspServer = null
        _rtspUrl.value = ""
        _isRtspRunning.value = false
    }

    data class StreamUrls(
        val streamUrl: String,
        val audioUrl: String,
        val snapshotUrl: String,
        val controlUrl: String,
    )

    companion object {
        private const val TAG = "StreamingManager"
        const val DEFAULT_PORT = 8080
        private const val DEFAULT_JPEG_QUALITY = 70
        private const val DEFAULT_STREAM_FPS = 24
        private const val DEFAULT_AUDIO_BITRATE_KBPS = 128
        private const val DEFAULT_AUDIO_CHANNELS = 1
    }
}

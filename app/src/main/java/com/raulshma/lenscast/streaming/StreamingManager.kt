package com.raulshma.lenscast.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.StreamAuthSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamingManager(private val context: Context) {

    private val audioStreamingManager = AudioStreamingManager(context)
    private var server: StreamingServer = StreamingServer(DEFAULT_PORT, context, audioStreamingManager)
    private val isStreaming = AtomicBoolean(false)
    private val jpegQuality = AtomicInteger(DEFAULT_JPEG_QUALITY)
    private val streamAudioEnabled = AtomicBoolean(true)
    private val streamAudioBitrateKbps = AtomicInteger(DEFAULT_AUDIO_BITRATE_KBPS)
    private val streamAudioChannels = AtomicInteger(DEFAULT_AUDIO_CHANNELS)
    private var currentPort: Int = DEFAULT_PORT

    private var lastFrameTimeMs = 0L
    private var minFrameIntervalMs = 1000L / DEFAULT_STREAM_FPS
    private val reusableBuffer = ByteArrayOutputStream(256 * 1024)
    private val reusableYuvBuffer = ByteArrayOutputStream(256 * 1024)
    private val bufferLock = Any()
    private var lastReportedClientCount = -1

    var thermalMonitor: ThermalMonitor? = null

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

    fun isLiveStreaming(): Boolean = isStreaming.get()

    fun setPort(port: Int) {
        if (isStreaming.get()) {
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
            server = StreamingServer(port, context, audioStreamingManager)
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
        if (isStreaming.getAndSet(true)) return true

        if (!ensureServerRunning()) {
            isStreaming.set(false)
            return false
        }

        refreshAudioStreamingState()

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return

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
        if (!isStreaming.getAndSet(false)) return
        audioStreamingManager.stop()
        _clientCount.value = 0
        _audioStreamUrl.value = ""
        _isAudioStreaming.value = false
        Log.d(TAG, "Live streaming paused (server still running)")
    }

    fun pushFrame(bitmap: Bitmap) {
        if (!isStreaming.get()) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTimeMs
        val adjustedInterval = thermalMonitor?.getAdjustedFrameDelay(minFrameIntervalMs)
            ?: minFrameIntervalMs
        if (elapsed < adjustedInterval) return
        lastFrameTimeMs = now

        val clientCount = server.getClientCount()
        if (clientCount == 0) return

        val quality = thermalMonitor?.getAdjustedQuality(jpegQuality.get()) ?: jpegQuality.get()
        val jpegData = bitmapToJpegReuse(bitmap, quality)
        server.updateFrame(jpegData)

        if (clientCount != lastReportedClientCount) {
            lastReportedClientCount = clientCount
            _clientCount.value = clientCount
        }
    }

    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        if (!isStreaming.get()) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTimeMs
        val adjustedInterval = thermalMonitor?.getAdjustedFrameDelay(minFrameIntervalMs)
            ?: minFrameIntervalMs
        if (elapsed < adjustedInterval) return
        lastFrameTimeMs = now

        val clientCount = server.getClientCount()
        if (clientCount == 0) return

        val quality = thermalMonitor?.getAdjustedQuality(jpegQuality.get()) ?: jpegQuality.get()
        val jpegData = yuvToJpeg(yuvData, width, height, quality, rotation) ?: return
        server.updateFrame(jpegData)

        if (clientCount != lastReportedClientCount) {
            lastReportedClientCount = clientCount
            _clientCount.value = clientCount
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
        minFrameIntervalMs = if (fps > 0) 1000L / fps else 1000L / DEFAULT_STREAM_FPS
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        streamAudioEnabled.set(enabled)
        if (isStreaming.get()) {
            refreshAudioStreamingState()
        } else {
            _isAudioStreaming.value = false
            _audioStreamUrl.value = ""
        }
    }

    fun setStreamAudioBitrateKbps(bitrateKbps: Int) {
        streamAudioBitrateKbps.set(bitrateKbps.coerceIn(32, 320))
        if (isStreaming.get() && streamAudioEnabled.get()) {
            refreshAudioStreamingState()
        }
    }

    fun setStreamAudioChannels(channels: Int) {
        streamAudioChannels.set(channels.coerceIn(1, 2))
        if (isStreaming.get() && streamAudioEnabled.get()) {
            refreshAudioStreamingState()
        }
    }

    fun reduceBitrate(multiplier: Float) {
        val current = jpegQuality.get()
        jpegQuality.set((current * multiplier).toInt().coerceIn(10, 100))
        Log.d(TAG, "Thermal: JPEG quality adjusted to ${jpegQuality.get()}")
    }

    fun reduceFrameRate(multiplier: Float) {
        val baseInterval = 1000L / DEFAULT_STREAM_FPS
        minFrameIntervalMs = if (multiplier > 0f) (baseInterval / multiplier).toLong() else Long.MAX_VALUE
        Log.d(TAG, "Thermal: frame interval adjusted to ${minFrameIntervalMs}ms (factor $multiplier)")
    }

    fun restoreNormalSettings() {
        jpegQuality.set(DEFAULT_JPEG_QUALITY)
        minFrameIntervalMs = 1000L / DEFAULT_STREAM_FPS
        Log.d(TAG, "Thermal: settings restored to normal")
    }

    fun updateAuthSettings(settings: StreamAuthSettings) {
        if (settings.enabled && settings.username.isNotEmpty()) {
            server.authUsername = settings.username
            server.authPasswordHash = settings.passwordHash
        } else {
            server.authUsername = null
            server.authPasswordHash = null
        }
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

        if (!isStreaming.get() || !streamAudioEnabled.get()) {
            _isAudioStreaming.value = false
            _audioStreamUrl.value = ""
            return
        }

        val audioStarted = audioStreamingManager.start(
            AudioStreamingManager.Config(
                bitrateKbps = streamAudioBitrateKbps.get(),
                channelCount = streamAudioChannels.get(),
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

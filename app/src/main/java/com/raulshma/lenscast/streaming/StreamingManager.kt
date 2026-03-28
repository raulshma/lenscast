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

    private var server: StreamingServer = StreamingServer(DEFAULT_PORT, context)
    private var encoder: H264Encoder? = null
    private val isStreaming = AtomicBoolean(false)
    private val jpegQuality = AtomicInteger(DEFAULT_JPEG_QUALITY)
    private var currentPort: Int = DEFAULT_PORT

    var thermalMonitor: ThermalMonitor? = null

    private val _streamUrl = MutableStateFlow("")
    val streamUrl: StateFlow<String> = _streamUrl

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    fun isLiveStreaming(): Boolean = isStreaming.get()

    fun setPort(port: Int) {
        if (isStreaming.get()) {
            Log.w(TAG, "Cannot change port while streaming")
            return
        }
        if (port != currentPort) {
            currentPort = port
            server = StreamingServer(port, context)
            Log.d(TAG, "Streaming port set to $port")
        }
    }

    fun startStreaming(): Boolean {
        if (isStreaming.getAndSet(true)) return true

        if (!_isServerRunning.value) {
            val started = server.startServer()
            if (!started) {
                isStreaming.set(false)
                return false
            }

            _isServerRunning.value = true
        }

        if (_streamUrl.value.isBlank()) {
            val url = NetworkUtils.getStreamingUrl(currentPort)
            _streamUrl.value = url ?: "http://localhost:$currentPort/stream"
        }

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return

        server.stopServer()
        encoder?.release()
        encoder = null
        _streamUrl.value = ""
        _clientCount.value = 0
        _isServerRunning.value = false

        Log.d(TAG, "Streaming stopped")
    }

    fun pauseStreaming() {
        if (!isStreaming.getAndSet(false)) return
        _clientCount.value = 0
        Log.d(TAG, "Live streaming paused (server still running)")
    }

    fun pushFrame(bitmap: Bitmap) {
        if (!isStreaming.get()) return
        val quality = thermalMonitor?.getAdjustedQuality(jpegQuality.get()) ?: jpegQuality.get()
        val jpegData = bitmapToJpeg(bitmap, quality)
        server.updateFrame(jpegData)
        _clientCount.value = server.getClientCount()
    }

    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        if (!isStreaming.get()) return
        val bitmap = yuvToBitmap(yuvData, width, height, rotation) ?: return
        pushFrame(bitmap)
        bitmap.recycle()
    }

    fun setJpegQuality(quality: Int) {
        jpegQuality.set(quality.coerceIn(10, 100))
    }

    fun reduceBitrate(multiplier: Float) {
        val current = jpegQuality.get()
        jpegQuality.set((current * multiplier).toInt().coerceIn(10, 100))
        Log.d(TAG, "Thermal: JPEG quality adjusted to ${jpegQuality.get()}")
    }

    fun reduceFrameRate(multiplier: Float) {
        Log.d(TAG, "Thermal: frame rate reduction requested (factor $multiplier)")
    }

    fun restoreNormalSettings() {
        jpegQuality.set(DEFAULT_JPEG_QUALITY)
        Log.d(TAG, "Thermal: settings restored to normal")
    }

    fun updateAuthSettings(settings: StreamAuthSettings) {
        if (settings.enabled && settings.username.isNotEmpty()) {
            server.authUsername = settings.username
            server.authPassword = settings.password
        } else {
            server.authUsername = null
            server.authPassword = null
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun yuvToBitmap(yuvData: ByteArray, width: Int, height: Int, rotation: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
            val stream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, stream)
            val bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
            if (rotation != 0 && bitmap != null) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                if (rotated != bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV to bitmap conversion failed", e)
            null
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
            streamUrl = NetworkUtils.getStreamingUrl(port) ?: "",
            snapshotUrl = NetworkUtils.getSnapshotUrl(port) ?: "",
            controlUrl = NetworkUtils.getLocalIpAddress()?.let { "http://$it:$port/" } ?: ""
        )
    }

    fun release() {
        stopStreaming()
        thermalMonitor?.stopMonitoring()
    }

    data class StreamUrls(
        val streamUrl: String,
        val snapshotUrl: String,
        val controlUrl: String,
    )

    companion object {
        private const val TAG = "StreamingManager"
        const val DEFAULT_PORT = 8080
        private const val DEFAULT_JPEG_QUALITY = 80
    }
}

package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One camera frame as delivered by the analysis pipeline. */
class YuvFrame(
    val yuvData: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val quality: Int,
    val overlay: OverlaySettings,
    val clientCount: Int,
)

/**
 * The web frame pipeline: camera YUV in, overlaid JPEG out. Owns the
 * frame-interval throttle (thermal + adaptive), JPEG quality resolution,
 * YUV→JPEG conversion with reusable buffers, the conflated frame queue, and
 * the processed/dropped counters. Activity gating stays with
 * StreamingManager; the pixel work sits behind this small interface.
 */
class FramePipeline(
    private val thermalMonitor: ThermalMonitor,
    private val adaptiveBitrateController: AdaptiveBitrateController,
) {

    /** Called on the pipeline worker with the JPEG for each processed frame. */
    private var listener: ((ByteArray) -> Unit)? = null

    fun setListener(listener: ((ByteArray) -> Unit)?) {
        this.listener = listener
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<YuvFrame>(capacity = Channel.CONFLATED)

    private val droppedFrameCount = AtomicInteger(0)
    private val processedFrameCount = AtomicInteger(0)

    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames

    private val _processedFrames = MutableStateFlow(0)
    val processedFrames: StateFlow<Int> = _processedFrames

    private val lastFrameTimeMs = AtomicLong(0L)

    @Volatile
    private var minFrameIntervalMs = 1000L / StreamDefaults.STREAM_FPS

    @Volatile
    private var jpegQuality = StreamDefaults.JPEG_QUALITY

    private val maxBufferSize = 4 * 1024 * 1024
    private var reusableBuffer = ByteArrayOutputStream(256 * 1024)
    private var reusableYuvBuffer = ByteArrayOutputStream(256 * 1024)
    private val bufferLock = Any()

    init {
        scope.launch {
            for (frame in queue) {
                process(frame)
            }
        }
    }

    fun setFrameRate(fps: Int) {
        minFrameIntervalMs = if (fps > 0) 1000L / fps else 1000L / StreamDefaults.STREAM_FPS
    }

    fun setJpegQuality(quality: Int) {
        jpegQuality = quality
    }

    /** Throttle and enqueue one frame. False when it was dropped by the throttle. */
    fun push(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        overlay: OverlaySettings,
        clientCount: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameTimeMs.get()

        val baseInterval = minFrameIntervalMs
        val thermalAdjustedInterval = thermalMonitor.getAdjustedFrameDelay(baseInterval)
        val adaptiveInterval = adaptiveBitrateController.getAdaptiveFrameInterval(baseInterval, thermalAdjustedInterval)

        if (elapsed < adaptiveInterval) {
            droppedFrameCount.incrementAndGet()
            return false
        }

        lastFrameTimeMs.set(now)

        val baseQuality = jpegQuality
        val thermalAdjustedQuality = thermalMonitor.getAdjustedQuality(baseQuality)
        val quality = adaptiveBitrateController.getAdaptiveQuality(baseQuality, thermalAdjustedQuality)

        val queued = queue.trySend(YuvFrame(yuvData.copyOf(), width, height, rotation, quality, overlay, clientCount))
        if (queued.isFailure) {
            droppedFrameCount.incrementAndGet()
            return false
        }
        return true
    }

    fun release() {
        scope.cancel()
        queue.close()
    }

    private fun process(frame: YuvFrame) {
        try {
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

            listener?.invoke(jpegData)
            processedFrameCount.incrementAndGet()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            droppedFrameCount.incrementAndGet()
        }

        publishCounters()
    }

    private fun publishCounters() {
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

    companion object {
        private const val TAG = "FramePipeline"
    }
}

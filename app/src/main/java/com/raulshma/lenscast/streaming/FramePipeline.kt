package com.raulshma.lenscast.streaming

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
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
 * frame-interval pacer (thermal + adaptive), JPEG quality resolution,
 * single-pass NV21 overlay + YUV→JPEG conversion with reusable buffers, the
 * conflated frame queue, and the processed/dropped counters. Activity gating
 * stays with StreamingManager; the pixel work sits behind this small interface.
 */
class FramePipeline(
    private val qualityPolicy: StreamQualityPolicy,
    private val illuminance: () -> Int? = { null },
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

    @Volatile
    private var targetFps = StreamDefaults.STREAM_FPS

    @Volatile
    private var minFrameIntervalMs = FrameTiming.frameIntervalMs(StreamDefaults.STREAM_FPS)

    @Volatile
    private var jpegQuality = StreamDefaults.JPEG_QUALITY

    private val overlayCompositor = EncodedOverlayCompositor(illuminanceProvider = illuminance)

    private val pacer = FramePacer(
        intervalNs = {
            val baseMs = minFrameIntervalMs
            val resolvedMs = qualityPolicy.resolveInterval(baseMs)
            when {
                resolvedMs == Long.MAX_VALUE -> Long.MAX_VALUE
                resolvedMs == baseMs -> FrameTiming.frameIntervalNs(targetFps)
                else -> resolvedMs * 1_000_000L
            }
        },
    )

    private val maxBufferSize = 4 * 1024 * 1024
    private var reusableBuffer = ByteArrayOutputStream(256 * 1024)
    private val bufferLock = Any()

    init {
        scope.launch {
            for (frame in queue) {
                process(frame)
            }
        }
    }

    fun setFrameRate(fps: Int) {
        val effective = FrameTiming.effectiveFps(fps)
        targetFps = effective
        minFrameIntervalMs = FrameTiming.frameIntervalMs(effective)
    }

    fun setJpegQuality(quality: Int) {
        jpegQuality = quality
    }

    /** Pace and enqueue one frame. False when it was dropped by the pacer. */
    fun push(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        overlay: OverlaySettings,
        clientCount: Int,
        timestampNs: Long = 0L,
    ): Boolean {
        val sampleNs = if (timestampNs > 0L) timestampNs else System.nanoTime()
        if (!pacer.accept(sampleNs)) {
            droppedFrameCount.incrementAndGet()
            return false
        }

        // Quality resolves only for frames that pass the pacer: the
        // adaptive module's driven state must reflect flowing frames alone.
        val quality = qualityPolicy.resolveQuality(jpegQuality)

        // No defensive copy: the source array is freshly allocated per frame
        // by the camera's NV21 conversion and never written afterward — every
        // consumer (this pipeline, the snapshot store, motion, the encoded
        // hub, ML) either reads it or copies it into its own buffer.
        val queued = queue.trySend(YuvFrame(yuvData, width, height, rotation, quality, overlay, clientCount))
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
            val outWidth = if (frame.rotation == 90 || frame.rotation == 270) frame.height else frame.width
            val outHeight = if (frame.rotation == 90 || frame.rotation == 270) frame.width else frame.height
            var nv21 = if (frame.rotation != 0) {
                YuvConverter.rotateNv21(frame.yuvData, frame.width, frame.height, frame.rotation)
            } else {
                frame.yuvData
            }

            if (OverlayLayoutPolicy.overlayPassNeeded(frame.overlay)) {
                try {
                    overlayCompositor.setOverlaySettings(frame.overlay)
                    nv21 = overlayCompositor.applyTo(
                        nv21,
                        outWidth,
                        outHeight,
                        System.currentTimeMillis(),
                        frameShared = (nv21 === frame.yuvData),
                        clientCount = frame.clientCount,
                    )
                } catch (e: Exception) {
                    if (frame.overlay.maskingEnabled && frame.overlay.maskingZones.any { it.enabled }) {
                        Log.e(TAG, "Cannot apply privacy masking; dropping frame", e)
                        droppedFrameCount.incrementAndGet()
                        publishCounters()
                        return
                    }
                    Log.w(TAG, "Overlay pass failed; continuing without text overlay", e)
                }
            }

            val jpegData = nv21ToJpeg(nv21, outWidth, outHeight, frame.quality) ?: return

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

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            synchronized(bufferLock) {
                reusableBuffer.reset()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, reusableBuffer)
                val result = reusableBuffer.toByteArray()
                capBuffer(reusableBuffer)
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV to JPEG conversion failed", e)
            null
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

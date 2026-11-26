package com.raulshma.lenscast.streaming

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.raulshma.lenscast.core.YuvConverter
import java.io.ByteArrayOutputStream

/**
 * The detection snapshot's frame source when the M-JPEG pipeline has none:
 * the pipeline only encodes while an actual M-JPEG viewer pulls frames, so a
 * headless camera (or a WebRTC-only dashboard) has no web frame for the event
 * snapshot. The store retains a defensive copy of the newest camera frame at
 * most once per [REFRESH_INTERVAL_MS] — one copy a second, never the recycled
 * frame-path buffer — and encodes it to a display-orientation JPEG on demand,
 * once per detection event.
 */
class SnapshotFrameStore {

    private class RetainedFrame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
    )

    @Volatile private var frame: RetainedFrame? = null
    @Volatile private var lastRetainMs = 0L

    /** Called on the camera frame path; copies at most once per interval. */
    fun maybeRetain(yuv: ByteArray, width: Int, height: Int, rotation: Int, nowMs: Long) {
        if (nowMs - lastRetainMs < REFRESH_INTERVAL_MS) return
        lastRetainMs = nowMs
        frame = RetainedFrame(yuv.copyOf(), width, height, rotation)
    }

    /**
     * The retained frame as a JPEG, or null when no frame was retained yet or
     * the encode failed — the event logs fine without it.
     */
    internal fun encodeJpeg(
        quality: Int,
        compositor: EncodedOverlayCompositor? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray? {
        val retained = frame ?: return null
        return try {
            val prepared = prepareFrame(
                retained.nv21,
                retained.width,
                retained.height,
                retained.rotationDegrees,
                compositor,
                nowMs,
            )
            val yuvImage = YuvImage(prepared.nv21, ImageFormat.NV21, prepared.width, prepared.height, null)
            ByteArrayOutputStream().also { out ->
                yuvImage.compressToJpeg(Rect(0, 0, prepared.width, prepared.height), quality, out)
            }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Retention cadence: fresh enough for an event snapshot, ~free next to 30 fps. */
        private const val REFRESH_INTERVAL_MS = 1_000L

        internal fun prepareFrame(
            nv21: ByteArray,
            width: Int,
            height: Int,
            rotation: Int,
            compositor: EncodedOverlayCompositor?,
            nowMs: Long,
        ): PreparedSnapshotFrame {
            val uprightWidth = if (rotation == 90 || rotation == 270) height else width
            val uprightHeight = if (rotation == 90 || rotation == 270) width else height
            val rotated = YuvConverter.rotateNv21(nv21, width, height, rotation)
            // The retained frame belongs to the store across settings changes;
            // stamping must never make an old mask part of its raw source.
            val source = if (compositor != null && rotated === nv21) rotated.copyOf() else rotated
            val stamped = compositor?.applyTo(
                source,
                uprightWidth,
                uprightHeight,
                nowMs,
                frameShared = false,
            ) ?: source
            return PreparedSnapshotFrame(stamped, uprightWidth, uprightHeight)
        }
    }
}

internal class PreparedSnapshotFrame(val nv21: ByteArray, val width: Int, val height: Int)

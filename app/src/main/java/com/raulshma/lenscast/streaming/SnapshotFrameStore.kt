package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
    fun encodeJpeg(quality: Int): ByteArray? {
        val retained = frame ?: return null
        return try {
            val yuvImage = YuvImage(retained.nv21, ImageFormat.NV21, retained.width, retained.height, null)
            val raw = ByteArrayOutputStream().also { out ->
                yuvImage.compressToJpeg(Rect(0, 0, retained.width, retained.height), quality, out)
            }.toByteArray()
            if (retained.rotationDegrees == 0) return raw
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
            val matrix = Matrix().apply { postRotate(retained.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (rotated !== bitmap) bitmap.recycle()
            rotated.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Retention cadence: fresh enough for an event snapshot, ~free next to 30 fps. */
        private const val REFRESH_INTERVAL_MS = 1_000L
    }
}

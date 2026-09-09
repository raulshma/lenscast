package com.raulshma.lenscast.capture

import android.util.Log
import com.raulshma.lenscast.camera.model.MotionZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Stateful motion detector over [MotionEventPolicy]: keeps the last tile-luma
 * grid, warmup count, and last-fire time; calls [onMotion] off the frame path
 * (caller dispatches) with the triggered zones' labels. Disabled by default;
 * sensitivity scales threshold; detection narrows to the enabled [zones] when
 * any exist. The grid verdict fires when any considered tile breaches the
 * threshold, so motion inside a zone is not washed out by a still
 * rest-of-frame.
 */
class MotionDetector(
    private val onMotion: (delta: Double, zones: List<String>) -> Unit,
) {
    @Volatile var enabled: Boolean = false
    @Volatile var sensitivity: Float = 0.5f
    @Volatile var zones: List<MotionZone> = emptyList()

    private var lastGrid: DoubleArray? = null
    private var lastFireMs = 0L
    private val framesSeen = AtomicLong(0)

    fun threshold(): Double = MotionEventPolicy.thresholdFor(sensitivity)

    fun feed(yuv: ByteArray, width: Int, height: Int, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return
        try {
            val grid = MotionEventPolicy.lumaGrid(yuv, width, height)
            val seen = framesSeen.incrementAndGet()
            val verdict = MotionEventPolicy.evaluateGrid(
                lastGrid = lastGrid,
                currentGrid = grid,
                zones = zones,
                nowMs = nowMs,
                lastFireMs = lastFireMs,
                threshold = threshold(),
                framesSeen = seen,
            )
            lastGrid = grid
            if (verdict.fire) {
                lastFireMs = nowMs
                Log.d(TAG, "Motion event (delta=${String.format(java.util.Locale.US, "%.1f", verdict.delta)}, zones=${verdict.zones})")
                onMotion(verdict.delta, verdict.zones)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Motion feed failed", e)
        }
    }

    fun reset() {
        lastGrid = null
        lastFireMs = 0L
        framesSeen.set(0)
    }

    companion object {
        private const val TAG = "MotionDetector"
    }
}

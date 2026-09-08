package com.raulshma.lenscast.capture

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Stateful motion detector over [MotionEventPolicy]: keeps the last luma
 * average, warmup count, and last-fire time; calls [onMotion] off the frame
 * path (caller dispatches). Disabled by default; sensitivity scales threshold.
 */
class MotionDetector(
    private val onMotion: (delta: Double) -> Unit,
) {
    @Volatile var enabled: Boolean = false
    @Volatile var sensitivity: Float = 0.5f

    private var lastAvg: Double? = null
    private var lastFireMs = 0L
    private val framesSeen = AtomicLong(0)

    fun threshold(): Double = MotionEventPolicy.thresholdFor(sensitivity)

    fun feed(yuv: ByteArray, width: Int, height: Int, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled) return
        try {
            val avg = MotionEventPolicy.lumaAverage(yuv, width, height)
            val seen = framesSeen.incrementAndGet()
            val verdict = MotionEventPolicy.evaluate(
                lastAvg = lastAvg,
                currentAvg = avg,
                nowMs = nowMs,
                lastFireMs = lastFireMs,
                threshold = threshold(),
                framesSeen = seen,
            )
            lastAvg = avg
            if (verdict.fire) {
                lastFireMs = nowMs
                Log.d(TAG, "Motion event (delta=${String.format(java.util.Locale.US, "%.1f", verdict.delta)})")
                onMotion(verdict.delta)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Motion feed failed", e)
        }
    }

    fun reset() {
        lastAvg = null
        lastFireMs = 0L
        framesSeen.set(0)
    }

    companion object {
        private const val TAG = "MotionDetector"
    }
}

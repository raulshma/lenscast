package com.raulshma.lenscast.capture

/**
 * Pure motion-event policy: frame-luma delta → trigger verdict with cooldown.
 * JVM-tested; [MotionDetector] keeps last-average + last-fire time.
 */
object MotionEventPolicy {
    /** Mean-absolute luma delta (0-255) that counts as motion at default sensitivity. */
    const val DEFAULT_THRESHOLD = 12.0
    /** Minimum ms between two motion events. */
    const val DEFAULT_COOLDOWN_MS = 10_000L
    /** Frames to skip after start before arming (exposure settles). */
    const val WARMUP_FRAMES = 10L
    /** Sensitivity ladder endpoints: 0 → MAX (deaf), 1 → MIN (eager). */
    const val SENSITIVITY_THRESHOLD_MAX = 24.0
    const val SENSITIVITY_THRESHOLD_MIN = 4.0

    /** Sensitivity (0..1, coerced) → luma-delta threshold. */
    fun thresholdFor(sensitivity01: Float): Double =
        SENSITIVITY_THRESHOLD_MAX -
            sensitivity01.coerceIn(0f, 1f) * (SENSITIVITY_THRESHOLD_MAX - SENSITIVITY_THRESHOLD_MIN)

    data class Verdict(val fire: Boolean, val delta: Double)

    fun evaluate(
        lastAvg: Double?,
        currentAvg: Double,
        nowMs: Long,
        lastFireMs: Long,
        threshold: Double = DEFAULT_THRESHOLD,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        framesSeen: Long = WARMUP_FRAMES,
    ): Verdict {
        if (lastAvg == null || framesSeen < WARMUP_FRAMES) return Verdict(false, 0.0)
        val delta = kotlin.math.abs(currentAvg - lastAvg)
        if (delta < threshold) return Verdict(false, delta)
        if (nowMs - lastFireMs < cooldownMs) return Verdict(false, delta)
        return Verdict(true, delta)
    }

    /** Cheap luma estimate: mean of sampled Y bytes (NV21 Y plane first). */
    fun lumaAverage(yuv: ByteArray, width: Int, height: Int, stride: Int = 32): Double {
        val ySize = width * height
        if (ySize <= 0 || yuv.isEmpty()) return 0.0
        val limit = minOf(ySize, yuv.size)
        var sum = 0L
        var n = 0L
        var i = 0
        while (i < limit) {
            sum += yuv[i].toInt() and 0xFF
            n++
            i += stride
        }
        return if (n == 0L) 0.0 else sum.toDouble() / n
    }
}

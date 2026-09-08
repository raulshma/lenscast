package com.raulshma.lenscast.capture

/**
 * Pure sound-event policy: PCM16 RMS → trigger verdict with cooldown.
 * The RMS scale is normalized to percent of full scale (0..100), so the
 * persisted threshold percent maps directly without unit conversions living
 * in two places.
 */
object SoundDetectionPolicy {
    /** Minimum ms between two sound events (mirrors motion's cooldown). */
    const val DEFAULT_COOLDOWN_MS = 10_000L

    /** RMS of an int16 PCM chunk, as percent of full scale (0..100). */
    fun rmsPercent(pcm16: ByteArray): Double {
        if (pcm16.size < 2) return 0.0
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        val limit = pcm16.size - 1
        while (i < limit) {
            val low = pcm16[i].toInt() and 0xFF
            val high = pcm16[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0.0
        val rms = kotlin.math.sqrt(sumSquares / samples)
        return rms / 327.68
    }

    data class Verdict(val fire: Boolean, val rms: Double)

    fun evaluate(
        rmsPercent: Double,
        thresholdPercent: Double,
        nowMs: Long,
        lastFireMs: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Verdict {
        if (thresholdPercent <= 0.0) return Verdict(false, rmsPercent)
        if (rmsPercent < thresholdPercent) return Verdict(false, rmsPercent)
        if (nowMs - lastFireMs < cooldownMs) return Verdict(false, rmsPercent)
        return Verdict(true, rmsPercent)
    }
}

/**
 * Stateful sound detector over [SoundDetectionPolicy]: the audio reader thread
 * feeds PCM16 chunks; a breach of the threshold above the configured percent
 * (0 = off) fires [listener] off the audio path (caller dispatches).
 */
class SoundDetector(
    private val listener: (rmsPercent: Double) -> Unit,
) {
    @Volatile var enabled: Boolean = false
    @Volatile var thresholdPercent: Int = 30

    private var lastFireMs = 0L

    fun feed(pcm16: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled || thresholdPercent <= 0) return
        try {
            val rms = SoundDetectionPolicy.rmsPercent(pcm16)
            val verdict = SoundDetectionPolicy.evaluate(
                rmsPercent = rms,
                thresholdPercent = thresholdPercent.toDouble(),
                nowMs = nowMs,
                lastFireMs = lastFireMs,
            )
            if (verdict.fire) {
                lastFireMs = nowMs
                listener(verdict.rms)
            }
        } catch (_: Exception) {
        }
    }
}

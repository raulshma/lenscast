package com.raulshma.lenscast.capture

import com.raulshma.lenscast.core.StreamDefaults

/**
 * Pure sound-event policy: PCM16 RMS → trigger verdict with cooldown.
 * The RMS scale is normalized to percent of full scale (0..100), so the
 * persisted threshold percent maps directly without unit conversions living
 * in two places.
 */
object SoundDetectionPolicy {
    /** Minimum ms between two sound events (mirrors motion's cooldown). */
    const val DEFAULT_COOLDOWN_MS = StreamDefaults.SOUND_COOLDOWN_SECONDS_DEFAULT * 1_000L

    /**
     * The adaptive-noise-floor headroom: with the floor armed, the effective
     * trigger is the user threshold OR the tracked ambient plus this many
     * percent, whichever is higher — a constant ambient (HVAC, traffic)
     * neither masks real events nor trips the detector on its own.
     */
    const val ADAPTIVE_HEADROOM_PERCENT = StreamDefaults.SOUND_ADAPTIVE_FLOOR_HEADROOM_PERCENT

    /**
     * Effective trigger threshold: never below the user's setting; with a
     * tracked noise floor, at least floor + headroom. A null floor (feature
     * off) keeps the raw user threshold.
     */
    fun effectiveThreshold(
        thresholdPercent: Double,
        noiseFloorPercent: Double?,
        headroomPercent: Double = ADAPTIVE_HEADROOM_PERCENT,
    ): Double {
        if (noiseFloorPercent == null) return thresholdPercent
        return maxOf(thresholdPercent, noiseFloorPercent + headroomPercent)
    }

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
 * The slow exponential moving average of measured RMS that backs the adaptive
 * noise floor. [update] returns the new floor. The asymmetric rates are the
 * point: the floor rises fast enough to quiet a newly-started ambient hum but
 * falls slowly, so a sustained loud event cannot walk the floor up to
 * self-suppression within its own duration. The first sample anchors the
 * floor outright — ramping from zero instead would trip the detector on any
 * ambient louder than the user threshold while the floor converges, and one
 * loud blip anchoring high at enable is the cheaper failure (the slow fall
 * unwinds it).
 */
class AdaptiveNoiseFloor(
    initialPercent: Double = 0.0,
    /** EMA weight of a new sample while the ambient rises (louder than floor). */
    private val riseAlpha: Double = RISE_ALPHA,
    /** EMA weight of a new sample while the ambient falls (quieter than floor). */
    private val fallAlpha: Double = FALL_ALPHA,
) {
    // Volatile: update() runs on the audio reader thread, reset() can arrive
    // from the settings-apply thread.
    @Volatile var floorPercent: Double = initialPercent
        private set

    fun update(rmsPercent: Double): Double {
        val alpha = if (rmsPercent > floorPercent) riseAlpha else fallAlpha
        floorPercent = if (floorPercent <= 0.0) {
            // The first sample anchors the EMA: ramping up from zero would
            // trip the detector on any ambient louder than the user
            // threshold while the floor converges.
            rmsPercent
        } else {
            floorPercent + alpha * (rmsPercent - floorPercent)
        }
        return floorPercent
    }

    fun reset() {
        floorPercent = 0.0
    }

    companion object {
        const val RISE_ALPHA = 0.15
        const val FALL_ALPHA = 0.02
    }
}

/**
 * Stateful sound detector over [SoundDetectionPolicy]: the audio reader thread
 * feeds PCM16 chunks; a breach of the threshold above the configured percent
 * (0 = off) fires [listener] off the audio path (caller dispatches). With
 * [adaptiveNoiseFloor] on, the trigger threshold rides above a slowly tracked
 * ambient RMS ([AdaptiveNoiseFloor]) instead of the bare user percent.
 */
class SoundDetector(
    private val listener: (rmsPercent: Double) -> Unit,
) {
    @Volatile var enabled: Boolean = false
    @Volatile var thresholdPercent: Int = 30

    /** Minimum ms between two sound events; persisted via the settings store. */
    @Volatile var cooldownMs: Long = SoundDetectionPolicy.DEFAULT_COOLDOWN_MS

    /**
     * Whether the trigger threshold rides the tracked ambient noise floor.
     * Flipping the toggle re-anchors the floor: a floor tracked during an
     * earlier enabled period is stale by the time the feature comes back,
     * and the first sample must anchor again (AdaptiveNoiseFloor's contract).
     */
    @Volatile
    var adaptiveNoiseFloor: Boolean = false
        set(value) {
            if (value != field) noiseFloor.reset()
            field = value
        }

    private var lastFireMs = 0L

    // Fed on the audio reader thread only; SoundDetectionPolicy and the EMA
    // own the math, this keeps no decisions of its own.
    private var noiseFloor = AdaptiveNoiseFloor()

    fun feed(pcm16: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled || thresholdPercent <= 0) return
        try {
            val rms = SoundDetectionPolicy.rmsPercent(pcm16)
            val floor = if (adaptiveNoiseFloor) noiseFloor.update(rms) else null
            val threshold = SoundDetectionPolicy.effectiveThreshold(
                thresholdPercent = thresholdPercent.toDouble(),
                noiseFloorPercent = floor,
            )
            val verdict = SoundDetectionPolicy.evaluate(
                rmsPercent = rms,
                thresholdPercent = threshold,
                nowMs = nowMs,
                lastFireMs = lastFireMs,
                cooldownMs = cooldownMs,
            )
            if (verdict.fire) {
                lastFireMs = nowMs
                listener(verdict.rms)
            }
        } catch (_: Exception) {
        }
    }
}

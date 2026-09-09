package com.raulshma.lenscast.capture

import com.raulshma.lenscast.core.StreamDefaults

/**
 * Pure deterrence-automation verdicts for the [DetectionCoordinator]: which
 * actions a confirmed detection event triggers given the enabled flags and
 * the time still left on the cooldown. The verdict never re-triggers a
 * deterrent while the cooldown is running — a detection cluster (one
 * intruder, dozens of motion events) arms the siren once. The coordinator
 * keeps the clock, the siren handle, the torch seam, and the dispatch.
 */
object DeterrenceAutomationPolicy {

    data class Verdict(
        val startSiren: Boolean,
        val sirenDurationMs: Long,
        val triggerTorch: Boolean,
    ) {
        val isNoop: Boolean get() = !startSiren && !triggerTorch
    }

    /**
     * One verdict per confirmed event. Both flags off (the defaults) and a
     * running cooldown both produce the no-op verdict; the siren duration is
     * clamped to its [StreamDefaults] bounds here, so no caller re-clamps.
     */
    fun decide(
        autoSiren: Boolean,
        autoTorch: Boolean,
        sirenDurationSeconds: Int,
        cooldownRemainingMs: Long,
    ): Verdict {
        if (!autoSiren && !autoTorch) return Verdict(false, 0L, false)
        if (cooldownRemainingMs > 0) return Verdict(false, 0L, false)
        val durationSeconds = sirenDurationSeconds
            .coerceIn(StreamDefaults.SIREN_DURATION_MIN_SECONDS, StreamDefaults.SIREN_DURATION_MAX_SECONDS)
        return Verdict(
            startSiren = autoSiren,
            sirenDurationMs = durationSeconds * 1_000L,
            triggerTorch = autoTorch,
        )
    }
}

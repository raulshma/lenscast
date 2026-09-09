package com.raulshma.lenscast.capture.model

/**
 * The pure decisions behind continuous NVR-style loop recording: whether to
 * arm ([shouldArm]), which bounded config chains the segments
 * ([segmentConfig]), whether a stop observed while the feature is on broke
 * the chain ([chainBreakConfirmed]), and the user-stop suppression model — a
 * stop sets a suppressed-until timestamp ([suppressedUntilMs]) and arming
 * stays blocked until it lapses ([canArm]), so a manual stop wins for at
 * least the cooldown even with the toggle left on.
 *
 * Chaining itself is NOT re-implemented here: [segmentConfig] sets
 * `repeatIntervalSeconds > 0`, so the RecordingController's existing
 * bounded+repeat machinery (RecordingDurationPolicy) re-starts each segment
 * after its auto-stop. This policy only decides the initial arm, the
 * disable-time teardown, and the re-arm after a chain break.
 */
object ContinuousRecordingPolicy {

    /** How long a stop observed while enabled keeps arming suppressed. */
    const val USER_STOP_SUPPRESSION_MS = 60_000L

    /**
     * The idle window that must follow a chain break before the controller
     * gives up on the repeat machinery and starts suppressing/re-arming
     * itself: slightly longer than the repeat gap, so a healthy chained
     * auto-stop (Idle for ~[REPEAT_GAP_SECONDS]) is never mistaken for a stop.
     */
    const val CHAIN_BREAK_GRACE_MS = 5_000L

    /** The gap between chained segments, in seconds. > 0 arms the repeat. */
    const val REPEAT_GAP_SECONDS = 1L

    /**
     * Arm the loop only when the feature is on, the RecordingController
     * reports Idle, and this controller does not already consider the loop
     * maintained. Camera access is not an input: the RecordingController's
     * start path (and the camera session arbiter) already gate exclusive
     * camera access, so re-deriving it here would be a second truth.
     */
    fun shouldArm(
        enabled: Boolean,
        recordingStateIsIdle: Boolean,
        alreadyArmed: Boolean,
    ): Boolean = enabled && recordingStateIsIdle && !alreadyArmed

    /**
     * The config for one chained bounded segment: [segmentMinutes] minutes
     * (clamped to [RecordingConfig.MAX_DURATION_SECONDS] — 60 minutes is
     * exactly the ceiling, so the full persisted 5..60 range is expressible
     * and nothing is capped below the user's choice), repeat re-arm enabled
     * (the chain), and audio per the recording-audio preference.
     */
    fun segmentConfig(segmentMinutes: Int, audioEnabled: Boolean): RecordingConfig =
        RecordingConfig(
            durationSeconds = (segmentMinutes.toLong() * 60L)
                .coerceAtMost(RecordingConfig.MAX_DURATION_SECONDS),
            repeatIntervalSeconds = REPEAT_GAP_SECONDS,
            quality = RecordingQuality.HIGH,
            includeAudio = audioEnabled,
        )

    /**
     * Whether a stop this controller armed must be stopped when the feature
     * turns off: only a loop this controller maintains goes down with the
     * toggle — a recording a user started by hand is never torn down by
     * someone flipping a setting.
     */
    fun shouldStopOnDisable(enabled: Boolean, recordingActive: Boolean, armedByController: Boolean): Boolean =
        !enabled && recordingActive && armedByController

    /** Idle for at least [CHAIN_BREAK_GRACE_MS] means the repeat chain is dead. */
    fun chainBreakConfirmed(idleForMs: Long): Boolean = idleForMs >= CHAIN_BREAK_GRACE_MS

    /** The suppressed-until stamp a stop at [stopMs] sets. */
    fun suppressedUntilMs(stopMs: Long): Long = stopMs + USER_STOP_SUPPRESSION_MS

    /** Arming is allowed again once the suppression window has lapsed. */
    fun canArm(nowMs: Long, suppressedUntilMs: Long): Boolean = nowMs >= suppressedUntilMs
}

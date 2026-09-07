package com.raulshma.lenscast.capture.model

/**
 * The pure bounded-recording decisions behind the RecordingController's
 * duration/repeat cycle: whether a service-confirmed start arms the policy
 * ([shouldArm]), how long the auto-stop waits ([autoStopDelayMs]), which
 * gates the auto-stop and the repeat re-arm and the
 * repeat fire must pass on wake ([shouldFireAutoStop],
 * [shouldArmRepeatAfterAutoStop], [shouldFireRepeat]), and whether an armed
 * repeat survives a stop report ([doesRepeatSurviveStop]). The controller
 * keeps the lock, the coroutine jobs, the epochs, and the intent
 * construction — every non-obvious conditional in the cycle is one of these
 * verdicts over explicit inputs, so the user-stop / auto-stop / repeat-fire
 * / schedule-fire races are JVM-testable without a service.
 *
 * The repeat re-start is delay-based ([repeatDelayMs]), not wall-clock —
 * there is no rollover to share with [RecordingConfig.scheduledStartFor],
 * which stays the capture-screen schedule's computation.
 */
object RecordingDurationPolicy {

    /**
     * Why a recording stopped — the input to the survive-or-cancel verdict.
     */
    enum class StopCause {
        /** A caller's stop(): the controller bumps the epoch and tears the cycle down there. */
        USER,

        /** The policy's own duration auto-stop, sent through the service stop intent. */
        AUTO,

        /** A stop report with nothing pending: error, service death, a stale queued STOP. */
        SERVICE_REPORTED,
    }

    /**
     * True when a service-confirmed start must arm the duration policy. A
     * config without a duration records unlimited.
     */
    fun shouldArm(config: RecordingConfig?): Boolean =
        config != null && config.durationSeconds > 0

    /**
     * Milliseconds the armed duration job waits before the auto-stop —
     * non-positive when the duration already elapsed, in which case the job
     * fires without waiting (the original inline `remainMs` math).
     */
    fun autoStopDelayMs(config: RecordingConfig, startedAtMs: Long, now: Long): Long =
        config.durationSeconds * 1000 - (now - startedAtMs)

    /**
     * The auto-stop's fire gate on wake: the elapsed wait must still belong
     * to the armed cycle — the same duration job and the arm-time epoch — or
     * a user stop()/start() in the window has already won. Deliberately no
     * Idle check: at auto-stop time the service is still draining the live
     * recording (Recording/finalizing); the Idle re-check belongs to
     * [shouldFireRepeat] alone.
     */
    fun shouldFireAutoStop(autoStopJobIsCurrent: Boolean, epochUnchanged: Boolean): Boolean =
        autoStopJobIsCurrent && epochUnchanged

    /**
     * The re-arm gate after the auto-stop's stop intent: a repeat is
     * configured and the epoch is still at arm time — a user stop landing
     * between the auto-stop and the re-arm must win.
     */
    fun shouldArmRepeatAfterAutoStop(config: RecordingConfig, epochUnchanged: Boolean): Boolean =
        epochUnchanged && shouldArmRepeat(config)

    /** True when the config arms a repeat after its auto-stop. */
    fun shouldArmRepeat(config: RecordingConfig): Boolean =
        config.repeatIntervalSeconds > 0

    /** The repeat gap the armed job waits out before the re-start. */
    fun repeatDelayMs(config: RecordingConfig): Long =
        config.repeatIntervalSeconds * 1000

    /**
     * The repeat-fire triple re-check on wake: still the active repeat job,
     * the epoch still at arm time, and the service back to Idle — the gap
     * must not re-start over a live recording or behind a user stop.
     */
    fun shouldFireRepeat(
        repeatJobIsCurrent: Boolean,
        epochUnchanged: Boolean,
        stateIsIdle: Boolean,
    ): Boolean = repeatJobIsCurrent && epochUnchanged && stateIsIdle

    /**
     * Whether an armed repeat survives a stop report with [cause]. Only the
     * policy's own auto-stop survives — its repeat job is deliberately left
     * waiting out the gap and re-checks Idle and the epoch before firing. A
     * [StopCause.USER] stop tears the cycle down in stop() itself, and a
     * [StopCause.SERVICE_REPORTED] stop (error, service death, a stale
     * queued STOP) must not leave a restart armed behind it.
     *
     * Whether a repeat is actually armed does not enter the verdict: with
     * none armed survival is a no-op, but the controller's stop-pending flag
     * must still clear — so the branch stays cause-only.
     */
    fun doesRepeatSurviveStop(cause: StopCause): Boolean = cause == StopCause.AUTO
}

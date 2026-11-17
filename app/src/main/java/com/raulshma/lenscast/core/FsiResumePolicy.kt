package com.raulshma.lenscast.core

/**
 * Pure camera-resume prompt verdict (blueprint P1 item 11 — the tier ladder's
 * T6 row, restart-reliability §3.2 / §5 option B): after a process death or
 * reboot lands the app back in the SERVER-ONLY state, should the prompt that
 * makes the app visible — and so restores the while-in-use camera access
 * every death-and-relaunch tier loses — be posted, and as which notification
 * kind. [FsiResumeController] keeps the Android reads (POST_NOTIFICATIONS,
 * the full-screen-intent permission), the journal bookkeeping, and the
 * posting; every decision here is a plain input, and the ladder is
 * JVM-tested.
 *
 * The opt-in tension this policy guards (the reason the setting defaults
 * OFF): the prompt is the only unattended path that restores CAMERA after
 * process death, and it buys that by lighting the screen — a full-screen
 * intent auto-launches over locked/off screens when granted, directly
 * against [AppRelaunch]'s service-first "come back serving without lighting
 * up" promise. The default-off setting plus the once-per-death-event
 * debounce ([FsiResumePolicy.Verdict.SUPPRESS_PROMPTED]) are what keep the
 * feature from fighting that promise by default.
 */
object FsiResumePolicy {

    enum class Verdict {
        /**
         * All gates passed and the full-screen-intent permission is held —
         * post the FSI notification; the platform launches it over
         * locked/off screens (default-granted off-Play even on 14+;
         * restart-reliability §3.2).
         */
        POST_FULL_SCREEN,

        /**
         * All gates passed but the full-screen-intent permission is revoked
         * (the Android 14+ Play-install default for non-calling apps) — post
         * the same notification as a high-priority heads-up with the content
         * tap only (the StreamingService denied-promotion fallback's
         * content-tap machinery). The tap still lands the visible start: a
         * notification interaction is a documented while-in-use exemption.
         */
        POST_TAP_FALLBACK,

        /** The opt-in flag is off (the default) — never prompt. */
        SUPPRESS_OFF,

        /** The journal records no live outputs before the death — nothing to restore. */
        SUPPRESS_NOT_LIVE,

        /**
         * The journal's user-stopped flag stands (the swipe-kill intent) —
         * nothing may nag about a stream the user deliberately killed.
         */
        SUPPRESS_USER_STOPPED,

        /**
         * Camera access is already held (an output is live — the boot resume
         * or a surviving session owns the camera): nothing to bring up, and
         * a locked-mount denial belongs to [LockBypassController]'s wake, not
         * this prompt.
         */
        SUPPRESS_CAMERA_HELD,

        /** The prompt already fired once for this death event — never nag on every heartbeat. */
        SUPPRESS_PROMPTED,
    }

    /**
     * The gate order is also the suppression-reason precedence: a held camera
     * beats an already-prompted marker (the more informative reason), and the
     * user-stopped intent beats everything but the flag itself.
     *
     * [journalSaysCameraBearing] is the StreamStateJournal's camera-bearing
     * witness, not mere liveness — this prompt is the tier ladder's CAMERA
     * row (the decision's whole point is restoring while-in-use camera
     * access), so the trigger must key on whether the pre-death record held
     * a camera-bearing output. Today that witness is written as
     * `web || rtsp` — every live output binds the camera through the
     * session's begin, so a web-only record is camera-bearing too and still
     * prompts (a web-only tap restores the camera with it); the explicit
     * field is what a future non-camera output kind would clear, and then
     * this gate suppresses for it without a shape change.
     */
    fun decide(
        settingEnabled: Boolean,
        journalSaysCameraBearing: Boolean,
        userStopped: Boolean,
        cameraAccessHeld: Boolean,
        alreadyPrompted: Boolean,
        canUseFullScreenIntent: Boolean,
    ): Verdict = when {
        !settingEnabled -> Verdict.SUPPRESS_OFF
        !journalSaysCameraBearing -> Verdict.SUPPRESS_NOT_LIVE
        userStopped -> Verdict.SUPPRESS_USER_STOPPED
        cameraAccessHeld -> Verdict.SUPPRESS_CAMERA_HELD
        alreadyPrompted -> Verdict.SUPPRESS_PROMPTED
        canUseFullScreenIntent -> Verdict.POST_FULL_SCREEN
        else -> Verdict.POST_TAP_FALLBACK
    }
}

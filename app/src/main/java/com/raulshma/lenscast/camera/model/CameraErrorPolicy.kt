package com.raulshma.lenscast.camera.model

/**
 * The pure CameraX `CameraState.StateError` recovery verdict: given the
 * error code plus the app's rebind-attempt count, what — if anything —
 * should the app do about a camera error it observed?
 *
 * Why this exists: CameraX's official taxonomy (androidx
 * `CameraState.java`) splits errors into **recoverable** — CameraX retries
 * the open internally (`ERROR_CAMERA_IN_USE`, `ERROR_MAX_CAMERAS_IN_USE`,
 * `ERROR_OTHER_RECOVERABLE_ERROR`) — and **critical**, which "require the
 * intervention of the developer or user": `ERROR_STREAM_CONFIG`,
 * `ERROR_CAMERA_DISABLED`, `ERROR_CAMERA_FATAL_ERROR`,
 * `ERROR_DO_NOT_DISTURB_MODE_ENABLED`, `ERROR_CAMERA_REMOVED`. Until this
 * policy, the observer recorded every error and nothing ever acted on the
 * critical class — in server-only mode (no session to ride the watchdog's
 * stall ladder) a HAL error sat Idle until a human resumed.
 *
 * The verdict table, per the taxonomy:
 * - Recoverable codes → [Verdict.CAMERAX_HANDLES] always: CameraX's
 *   PENDING_OPEN → OPENING machinery owns the retry; an app-side rebind
 *   would fight it.
 * - `ERROR_CAMERA_FATAL_ERROR` → [Verdict.REBOOT_REQUIRED] always: it maps
 *   to camera2's `ERROR_CAMERA_SERVICE`, whose documented answer is "ask the
 *   user to reboot the device" — there is no sanctioned in-process
 *   recovery, so the rebind lever is never spent on it.
 * - `ERROR_CAMERA_REMOVED` → [Verdict.WAIT_CAMERA_ADDED] always: a removed
 *   (unplugged) camera is a terminal state — unbind and wait for
 *   `CameraManager.AvailabilityCallback.onCameraAdded`, never rebind.
 * - `ERROR_CAMERA_DISABLED` (device policy, the locked-mount while-in-use
 *   denial) → [Verdict.POLICY_DENIED]: no app-side lever exists; the
 *   dedicated message downstream is the report, and streaming's
 *   blocked-by-lock detector keys on the verdict (never the message).
 * - `ERROR_DO_NOT_DISTURB_MODE_ENABLED` (user toggle) →
 *   [Verdict.CAMERAX_HANDLES]: no app-side lever exists either; the report
 *   is the whole response.
 * - `ERROR_STREAM_CONFIG` and any code this build does not know →
 *   [Verdict.REBIND] while [rebindAttempts] is under
 *   [MAX_REBIND_ATTEMPTS]: a fresh `bindToLifecycle` is the documented lever
 *   for restarting CameraX's bounded internal reopen machinery, and an
 *   unclassified code lands on the surveillance-honest side (try the lever,
 *   bounded) rather than sitting Idle. The attempt counter resets on an
 *   observed OPEN — the caller owns that bookkeeping.
 *
 * The code constants mirror the CameraX ints one-to-one
 * (`CameraErrorPolicyTest` pins them); the policy stays free of the CameraX
 * dependency so the verdict table is plain JVM.
 */
object CameraErrorPolicy {

    /**
     * How many error-driven rebinds the app attempts before handing the
     * error back to report-only (the ladder above the observer — the
     * watchdog's session stall watch — remains the net).
     */
    const val MAX_REBIND_ATTEMPTS = 3

    // CameraX CameraState.StateError codes (values pinned by test to the
    // androidx constants — do not change one without the other).
    const val CODE_MAX_CAMERAS_IN_USE = 1
    const val CODE_CAMERA_IN_USE = 2
    const val CODE_OTHER_RECOVERABLE_ERROR = 3
    const val CODE_STREAM_CONFIG = 4
    const val CODE_CAMERA_DISABLED = 5
    const val CODE_CAMERA_FATAL_ERROR = 6
    const val CODE_DO_NOT_DISTURB_MODE_ENABLED = 7
    const val CODE_CAMERA_REMOVED = 8

    /** What the observing shell should do about an observed StateError. */
    enum class Verdict {
        /** No app-side action — CameraX's retry machinery, device policy, or the user owns the fix; report only. */
        CAMERAX_HANDLES,

        /**
         * The device's camera policy denied the open (ERROR_CAMERA_DISABLED,
         * the locked-mount while-in-use denial): like [CAMERAX_HANDLES] there
         * is no app-side lever — the policy (screen unlocked, app visible)
         * owns the fix — but streaming's blocked-by-lock detector keys on
         * this verdict, so it is distinct in the taxonomy.
         */
        POLICY_DENIED,

        /** Rebind the use cases (`CameraService.rebindUseCases`) — the bounded lever; count the attempt. */
        REBIND,

        /** The camera is gone (REMOVED): unbind and wait for `onCameraAdded` before reopening. */
        WAIT_CAMERA_ADDED,

        /** A dead camera service: no in-process recovery — surface "reboot the device" and stop trying. */
        REBOOT_REQUIRED,
    }

    /**
     * The verdict for one observed error. [rebindAttempts] is the count of
     * error-driven rebinds since the last observed OPEN (the caller's
     * bookkeeping); it only gates the REBIND verdict.
     */
    fun decide(errorCode: Int, rebindAttempts: Int): Verdict = when (errorCode) {
        CODE_CAMERA_FATAL_ERROR -> Verdict.REBOOT_REQUIRED
        CODE_CAMERA_REMOVED -> Verdict.WAIT_CAMERA_ADDED
        // The device policy denied the open: report-only (no lever), but
        // typed so streaming's blocked-by-lock detector need not string-match.
        CODE_CAMERA_DISABLED -> Verdict.POLICY_DENIED
        CODE_CAMERA_IN_USE,
        CODE_MAX_CAMERAS_IN_USE,
        CODE_OTHER_RECOVERABLE_ERROR,
        CODE_DO_NOT_DISTURB_MODE_ENABLED,
        -> Verdict.CAMERAX_HANDLES
        // STREAM_CONFIG and unknown codes: the bounded rebind lever.
        else -> if (rebindAttempts < MAX_REBIND_ATTEMPTS) Verdict.REBIND else Verdict.CAMERAX_HANDLES
    }

    /**
     * The dashboard's manual reset-camera lever (blueprint P1 item 10): the
     * remote `POST /api/system/reset-camera` request spends the SAME bounded
     * rebind budget the error observer spends — a spam-clicked button cannot
     * loop rebinds past [MAX_REBIND_ATTEMPTS] any more than a flapping camera
     * can. A taxonomy verdict already published for the standing error that
     * is not [Verdict.REBIND] owns the answer verbatim and spends nothing:
     * "reboot the phone" (REBOOT_REQUIRED) and "the camera is gone"
     * (WAIT_CAMERA_ADDED) have no in-process lever to spend, and the manual
     * path must not override the taxonomy — exactly [decide]'s own table.
     * POLICY_DENIED groups with CAMERAX_HANDLES here (report-only, no
     * spend; the wire handler's catch-all answers it). null
     * [publishedRecovery] (no standing classified error) or a REBIND
     * verdict fall through to the budget gate.
     */
    fun decideResetCamera(publishedRecovery: Verdict?, rebindAttempts: Int): Verdict =
        when (publishedRecovery) {
            null, Verdict.REBIND -> if (rebindAttempts < MAX_REBIND_ATTEMPTS) Verdict.REBIND else Verdict.CAMERAX_HANDLES
            else -> publishedRecovery
        }

    /**
     * Whether a standing error published while backgrounded should clear and
     * rebind when the activity resumes in the foreground. Only [Verdict.POLICY_DENIED]
     * (the while-in-use policy denial from background open) resolves solely by
     * returning to foreground; fatal/removed/other verdicts remain standing.
     */
    fun shouldRecoverOnResume(publishedRecovery: Verdict?): Boolean =
        publishedRecovery == Verdict.POLICY_DENIED
}

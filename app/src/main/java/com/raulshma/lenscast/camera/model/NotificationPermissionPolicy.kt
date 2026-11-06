package com.raulshma.lenscast.camera.model

import android.Manifest
import android.os.Build

/**
 * The pure POST_NOTIFICATIONS ask-once decision behind the camera screen's
 * first-launch permission pass: detection alerts need the notification
 * permission on API 33+, and the ask is a one-shot — exactly one dialog on
 * the screen's first composition, never a re-prompt after a denial or a
 * later revocation in system settings. The launcher result is deliberately
 * ignored: a denial buys silence, not a retry.
 *
 * Why this exists: the guard used to be a process-global `var` inside
 * CameraScreen, which is why the decision was stranded in composition — a
 * `LaunchedEffect(Unit)` re-runs on every fresh composition (each navigation
 * back), and only the global kept it from re-prompting. The decision now
 * lives here, and the once-flag lives in CameraViewModel's state, so "asked
 * before" survives recompositions and configuration changes with the screen's
 * ViewModel instead of leaking a mutable top-level var. The screen mirrors
 * [com.raulshma.lenscast.core.MicAccess]'s audio ask-once gate.
 */
object NotificationPermissionPolicy {

    /** The permission the first-launch pass asks for. */
    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** First API level with a runtime POST_NOTIFICATIONS permission (Android 13). */
    const val REQUIRED_API_LEVEL = Build.VERSION_CODES.TIRAMISU

    /**
     * Whether [PERMISSION] is a runtime permission at [apiLevel]: below
     * [REQUIRED_API_LEVEL] notifications are implicitly granted and no ask
     * exists.
     */
    fun isRequired(apiLevel: Int): Boolean = apiLevel >= REQUIRED_API_LEVEL

    /**
     * Whether this fresh composition should launch the ask dialog: the first
     * evaluation of the ViewModel's lifetime ([askedBefore] false), on an API
     * level where the permission exists, and not already granted. When this
     * returns true the caller has already consumed the budget via
     * [shouldMarkAsked] — the mark precedes the verdict.
     */
    fun shouldAsk(granted: Boolean, askedBefore: Boolean, apiLevel: Int): Boolean =
        !askedBefore && isRequired(apiLevel) && !granted

    /**
     * Whether this fresh composition consumes the ask-once budget. The flag
     * flips on the FIRST evaluation even when no dialog can be shown (below
     * [REQUIRED_API_LEVEL], or already granted) — the original guard set the
     * flag before its checks, so the one-shot is spent regardless of the
     * verdict and a later revocation never re-prompts. An already-spent
     * budget is never re-marked.
     */
    fun shouldMarkAsked(askedBefore: Boolean): Boolean = !askedBefore
}

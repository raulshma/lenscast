package com.raulshma.lenscast.automation

import com.raulshma.lenscast.capture.model.RecordingConfig

/**
 * The pure decision half of [AutomationReceiver] — the exported,
 * security-sensitive automation surface. The receiver stays a thin shell:
 * onReceive resolves the broadcast through this policy and executes the
 * matching seam call, so every verdict an automation client can influence
 * from the wire (the action string, the `enabled` and `durationSeconds`
 * extras) is decided here and JVM-testable without an Android process.
 *
 * The manifest's `android:permission` attribute is the outer boundary (only
 * holders of `com.raulshma.lenscast.permission.AUTOMATION` ever reach
 * onReceive); this policy is the inner one: an action string outside the
 * dispatch table routes to nothing, and every extra is bounded before it can
 * reach the camera, the recorder, or the siren.
 */
object AutomationIntentPolicy {

    /** The operations an automation broadcast can drive — the dispatch table. */
    enum class AutomationAction {
        START_STREAM,
        STOP_STREAM,
        CAPTURE_PHOTO,
        START_RECORDING,
        STOP_RECORDING,
        SET_TORCH,
        SET_SIREN,
    }

    /**
     * The action allow-list: the wire string to its operation, or null for
     * anything unlisted. Unknown actions are a no-op — the receiver still
     * refreshes the widget after them, but no camera/recorder/siren seam is
     * ever touched.
     */
    fun route(action: String?): AutomationAction? = when (action) {
        AutomationReceiver.ACTION_START_STREAM -> AutomationAction.START_STREAM
        AutomationReceiver.ACTION_STOP_STREAM -> AutomationAction.STOP_STREAM
        AutomationReceiver.ACTION_CAPTURE_PHOTO -> AutomationAction.CAPTURE_PHOTO
        AutomationReceiver.ACTION_START_RECORDING -> AutomationAction.START_RECORDING
        AutomationReceiver.ACTION_STOP_RECORDING -> AutomationAction.STOP_RECORDING
        AutomationReceiver.ACTION_SET_TORCH -> AutomationAction.SET_TORCH
        AutomationReceiver.ACTION_SET_SIREN -> AutomationAction.SET_SIREN
        else -> null
    }

    /**
     * The `enabled` extra verdict shared by torch and siren: a present extra
     * forces the state; an absent one toggles [currentState]. Toggles read
     * the live state, not the persisted setting — deterrence drives the torch
     * without persisting, so the setting can disagree with reality.
     */
    fun resolveEnable(
        hasEnabledExtra: Boolean,
        enabledExtraValue: Boolean,
        currentState: Boolean,
    ): Boolean = if (hasEnabledExtra) enabledExtraValue else !currentState

    /**
     * The recording duration from `durationSeconds`, clamped into the
     * [RecordingConfig] ceiling: a negative value means unbounded (0), and
     * anything past [RecordingConfig.MAX_DURATION_SECONDS] is cut to it.
     */
    fun recordingDurationSeconds(rawDurationSeconds: Int): Long =
        rawDurationSeconds.toLong().coerceIn(0, RecordingConfig.MAX_DURATION_SECONDS)

    /**
     * The siren auto-stop delay from `durationSeconds`: null when the extra
     * is absent (run until stopped — a bare toggle-on must not touch an
     * already-armed stop), otherwise the extra in milliseconds. Unlike the
     * recording duration this is deliberately unclamped: a non-positive
     * delay arms no timer in SirenAutoStop, so 0 is the explicit "no limit"
     * and a negative value is harmless.
     */
    fun sirenAutoStopDelayMs(hasDurationExtra: Boolean, rawDurationSeconds: Int): Long? =
        if (hasDurationExtra) rawDurationSeconds * 1_000L else null
}

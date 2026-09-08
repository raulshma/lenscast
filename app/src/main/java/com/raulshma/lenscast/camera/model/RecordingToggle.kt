package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.RecordingConfig

/**
 * The record button's verdict — [StreamToggle]'s twin for the recording
 * output: the stop-vs-start decision over the Recording Controller's state,
 * plus the config a start carries. The decision is pure — the ViewModel
 * executes it (stop or start through the controller), passes its start
 * config (the camera screen's default, the capture screen's full draft) and
 * wires the shared mic gate as the pre-start hook, exactly as the Stream
 * Toggle takes its `onBeforeStart`. A degrade never blocks the start —
 * warn-and-degrade is the mic policy — so the decision has no blocked
 * verdict.
 */
object RecordingToggle {

    /** What the record button should do right now. */
    sealed interface ToggleDecision {
        /** Start now with this config — the [decide] startConfig as the pre-start hook returned it. */
        data class Start(val config: RecordingConfig) : ToggleDecision

        /** A recording (or a scheduled start) is live — stop it. */
        data object Stop : ToggleDecision
    }

    /**
     * The verdict: a live state ([RecordingState.Recording] or
     * [RecordingState.Scheduled]) stops; anything else starts, running
     * [onBeforeStart] (the mic gate) on the start path only — a stop never
     * refreshes permissions or warns. The hook receives the start config and
     * returns the config the start carries, so one decide answers every
     * caller: the camera screen passes a default config with the audio
     * setting resolved, the capture screen passes its full draft (quality,
     * duration, repeat, scheduled time).
     */
    fun decide(
        currentState: RecordingState,
        startConfig: RecordingConfig,
        onBeforeStart: (RecordingConfig) -> RecordingConfig = { it },
    ): ToggleDecision {
        if (currentState is RecordingState.Recording || currentState is RecordingState.Scheduled) {
            return ToggleDecision.Stop
        }
        return ToggleDecision.Start(onBeforeStart(startConfig))
    }
}

/**
 * The camera state's stickiness: the service's flow reports Idle whenever no
 * camera session is live (preview stopped, rebinding), but the screen must
 * not regress to Idle from a state it already reached — an incoming Idle is
 * dropped in favor of the current state, and every other state passes
 * through. The ViewModel's collector applies this instead of hand-rolling
 * the filter.
 */
fun stickyCameraState(current: CameraState, incoming: CameraState): CameraState =
    if (incoming is CameraState.Idle) current else incoming

/**
 * The camera-init retry budget: at most [MAX_RETRIES] re-initializations per
 * process — a retry past the budget is a no-op instead of a forever restart
 * loop. [attempt] is the number of retries already spent.
 */
object CameraInitRetry {
    const val MAX_RETRIES = 3

    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_RETRIES
}

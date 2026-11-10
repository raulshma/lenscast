package com.raulshma.lenscast.camera.model

/**
 * The self-timer countdown's state machine, next to [SelfTimerPolicy] (whose
 * verdicts drive every transition):
 *
 * - [Idle] — no countdown running; the shutter captures immediately.
 * - [Counting] — a countdown is running with [Counting.secondsRemaining]
 *   whole seconds left (the overlay's display value).
 * - [Firing] — the countdown elapsed and the capture is going out; the
 *   overlay shows the fire flash, not a stale second count.
 *
 * The CameraViewModel's countdown job is the only writer (the initial
 * [Counting] lands synchronously at start, before the job launches — an
 * immediate cancel must observe a counting state, not the pre-write null
 * the old seconds-nullable shape allowed); the camera screen reads the
 * derived remaining-seconds flow for the overlay. Cancellation (a tap, the
 * mode flipping to OFF) returns the machine to [Idle] and kills the job.
 */
sealed interface CountdownState {
    data object Idle : CountdownState

    data class Counting(val secondsRemaining: Int) : CountdownState

    data object Firing : CountdownState
}

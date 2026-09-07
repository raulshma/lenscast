package com.raulshma.lenscast.streaming

import java.util.concurrent.atomic.AtomicLong

/**
 * The frame-interval throttle shared by the web frame pipeline and the RTSP
 * push path. One decision: a frame is accepted when at least
 * [intervalMs] * [tolerance] has elapsed since the previous decision's
 * reference time.
 *
 * Two knobs keep each call site faithful to its pre-refactor behavior:
 *  - [tolerance] — the web pipeline accepts at exactly the interval; the RTSP
 *    path accepts slightly early ([TOLERANCE] = 0.8) as jitter tolerance, so
 *    a camera clock that fires a touch before the nominal interval doesn't
 *    get every other frame rejected.
 *  - [updateClockOnReject] — the RTSP path stamped the reference time on
 *    every attempt (a rejected frame still advanced the clock, so the next
 *    wait starts over); the web pipeline stamps only accepted frames, so
 *    bursts measure against the last frame that actually flowed.
 *
 * The interval arrives as a supplier because both call sites derive it from
 * runtime config (adaptive interval, live frame-rate changes).
 */
class FrameThrottle(
    private val intervalMs: () -> Long,
    private val tolerance: Float = 1.0f,
    private val updateClockOnReject: Boolean = false,
) {

    constructor(intervalMs: Long, tolerance: Float = 1.0f) : this({ intervalMs }, tolerance, false)

    private val lastReferenceMs = AtomicLong(0L)

    /** Decides one frame at wall-clock [nowMs]. True = proceed with it. */
    fun accept(nowMs: Long): Boolean {
        val interval = intervalMs()
        val elapsed = if (updateClockOnReject) {
            nowMs - lastReferenceMs.getAndSet(nowMs)
        } else {
            nowMs - lastReferenceMs.get()
        }
        val accepted = elapsed >= interval * tolerance
        if (accepted && !updateClockOnReject) {
            lastReferenceMs.set(nowMs)
        }
        return accepted
    }

    companion object {
        /**
         * RTSP jitter tolerance: a frame arriving at 0.8× the nominal interval
         * still passes. The boundary is `elapsed < interval * TOLERANCE`
         * rejects; equal-or-above accepts.
         */
        const val TOLERANCE = 0.8f
    }
}

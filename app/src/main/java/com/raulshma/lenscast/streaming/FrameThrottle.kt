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

/**
 * Phase-accumulating nanosecond frame pacer driven by monotonic camera capture
 * timestamps (`ImageProxy.imageInfo.timestamp`, falling back to `System.nanoTime()`
 * when callers pass `<= 0`).
 *
 * Compared to a greedy `elapsed >= interval * 0.8` throttle:
 * - Down-paces cleanly to non-integer-divisor targets (e.g. 24 fps or 25 fps out
 *   of a 30 fps camera) without leaking the full 30 fps stream.
 * - Absorbs up to `min(interval / 4, MAX_JITTER_NS)` of early-frame jitter on
 *   30/60 fps sensors without dropping frames or accumulating phase drift.
 * - Clamps the next deadline when the sensor runs slower than target or after a
 *   gap, so no catch-up burst ever fires later.
 */
class FramePacer(
    private val intervalNs: () -> Long,
) {
    private var initialized = false
    private var lastAcceptedNs = 0L
    private var nextDueNs = 0L

    /** Decides one frame at monotonic [timestampNs]. True = proceed with it. */
    @Synchronized
    fun accept(timestampNs: Long): Boolean {
        val interval = intervalNs()
        if (interval == Long.MAX_VALUE) return false
        if (interval <= 0L) return true
        if (timestampNs < 0L) return false

        if (!initialized) {
            initialized = true
            lastAcceptedNs = timestampNs
            nextDueNs = timestampNs + interval
            return true
        }

        if (timestampNs < lastAcceptedNs) return false

        val margin = minOf(interval / 4L, MAX_JITTER_NS)
        // Capping against lastAcceptedNs + interval lets an upward fps switch
        // (e.g. 15 -> 60 fps) land on the next frame instead of waiting out the
        // old slow deadline.
        val due = minOf(nextDueNs, lastAcceptedNs + interval)
        if (timestampNs < due - margin) return false

        lastAcceptedNs = timestampNs
        val advanced = due + interval
        nextDueNs = if (advanced < timestampNs) timestampNs + interval else advanced
        return true
    }

    companion object {
        const val MAX_JITTER_NS = 4_000_000L
    }
}

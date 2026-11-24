package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Expected
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Line
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Reported
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.SAMPLE_INTERVAL_MS
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.TIMEOUT_MS
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Verdict
import java.util.concurrent.atomic.AtomicReference

/**
 * The read-back's sampling state machine — when to look at a capture result
 * and when to conclude. [CaptureReadbackPolicy] decides what a result means;
 * CameraService feeds the frames and logs what this hands back (the full
 * account is CONTEXT.md's "Capture Read-back").
 *
 * One armed apply, one line. A submit arms the tracker with the values it
 * sent; the frame hook then examines at most one result per
 * [SAMPLE_INTERVAL_MS] and concludes exactly once: on the first match
 * (immediately), or when the window closes after [TIMEOUT_MS] with the last
 * state it saw. A mismatch on the first sample is deliberately NOT reported
 * at once — the results right behind a submit can still carry the previous
 * request — it keeps sampling, and only a device that still disagrees at the
 * end is reported as a mismatch.
 *
 * A submit that lands while an apply is pending re-arms the tracker with its
 * own values and a fresh window, and the superseded apply owes no line. The
 * settings path applies twice within milliseconds (the editor and the
 * Applier), and a line about an epoch that was never sampled — least of all
 * a mismatch it never observed — would only be noise.
 *
 * Pure and clock-free: every method takes the time in ms, so nothing here
 * reads a clock and the JVM tests drive it frame by frame. The tracker owns
 * no thread — the timeout is noticed by the next frame, so an apply whose
 * stream stalls is concluded (as unknown) whenever the next result arrives.
 *
 * Threads: submits arm from the applier's dispatchers and from main, frames
 * arrive on the camera's callback executor. The state is one
 * [AtomicReference] to an immutable [Epoch], and every step is a
 * compare-and-set loop: read, decide, swap — and on a lost race, read again.
 * A step built on a state that a newer arm has replaced is simply retried
 * against the new one; a step that lost to a concurrent frame of the same
 * epoch merges into the winner's state by timestamp, so the newest sample is
 * never the one dropped. The concluding swap is the right to log: only the
 * thread that moves the epoch out emits its line, and never for an epoch
 * that was superseded under it. While nothing is armed, [onFrame] is one
 * volatile read and a return — nothing is allocated and no capture result
 * is touched.
 */
class CaptureReadbackTracker {

    private data class Sample(val atMs: Long, val reported: Reported, val verdict: Verdict)

    /** One armed apply's state. Immutable: every step replaces it wholesale. */
    private data class Epoch(val expected: Expected, val armedAtMs: Long, val lastSample: Sample?)

    private val armed = AtomicReference<Epoch?>(null)

    /** Whether a submitted plan is waiting for its confirmation. */
    val isArmed: Boolean get() = armed.get() != null

    /**
     * Arms the read-back for a submitted plan — [expected] as submitted —
     * and opens its window at [nowMs]. Re-arms if an apply was pending.
     */
    fun arm(expected: Expected, nowMs: Long) {
        armed.set(Epoch(expected, nowMs, lastSample = null))
    }

    /**
     * One capture result, [reported], at [nowMs]. Returns the apply's single
     * line when this frame concludes it, and null for every other frame —
     * not armed, inside the sampling interval, or sampled without a
     * conclusion.
     */
    fun onFrame(nowMs: Long, reported: Reported): Line? {
        while (true) {
            val epoch = armed.get() ?: return null
            val last = epoch.lastSample
            // The window first: a frame at or past the timeout concludes with
            // what was seen inside it, and is itself not sampled.
            if (nowMs - epoch.armedAtMs >= TIMEOUT_MS) {
                if (armed.compareAndSet(epoch, null)) return conclude(epoch.expected, last)
                continue
            }
            if (last != null && nowMs - last.atMs < SAMPLE_INTERVAL_MS) return null

            val verdict = CaptureReadbackPolicy.compare(epoch.expected, reported)
            if (verdict == Verdict.Match) {
                if (armed.compareAndSet(epoch, null)) return CaptureReadbackPolicy.ok(epoch.expected)
                continue
            }

            // A mismatch or a missing value is not a conclusion: remember the
            // sample as the window's current state and keep looking.
            val sample = Sample(nowMs, reported, verdict)
            if (armed.compareAndSet(epoch, epoch.copy(lastSample = newest(last, sample)))) return null
            // Lost the race to another frame: take its state and merge.
        }
    }

    /** The line an epoch concludes with — the caller has already disarmed it, and alone. */
    private fun conclude(expected: Expected, last: Sample?): Line {
        val sample = last
        return when {
            sample == null -> CaptureReadbackPolicy.unknown(expected, reported = null)
            sample.verdict == Verdict.Mismatch -> CaptureReadbackPolicy.mismatch(expected, sample.reported)
            else -> CaptureReadbackPolicy.unknown(expected, sample.reported)
        }
    }

    /** The later of the two samples — whichever thread observed it. */
    private fun newest(a: Sample?, b: Sample): Sample = if (a != null && a.atMs > b.atMs) a else b
}

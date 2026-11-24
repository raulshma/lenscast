package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamDefaults

/**
 * A sliding window over (timestampMs, bytes, frames) samples — the one
 * measured-rate primitive behind every DISPLAYED stream stat (issue #25): the
 * per-client MJPEG throughput/fps windows in the NetworkQualityMonitor, the
 * encoded lane's encoder-output window in the [EncodedStreamHub], and the
 * WHEP lane's per-viewer windows in
 * [com.raulshma.lenscast.streaming.whep.WhepStreamStats] (the one consumer
 * whose samples carry a frame count other than 1 — see [record]).
 *
 * Semantics, and why the divisor is the whole window span: samples older than
 * [windowMs] slide out (pruned on every record and read — nothing is summed
 * forever), and the measured rate divides the surviving bytes by the FULL
 * window span. A steady stream therefore reads its true rate, a partially
 * filled window reads LOW until it fills, and a stream that stops decays to 0
 * as its samples age out. Dividing by the samples' own short span instead —
 * the "bytes over drain-ms" framing the old per-frame throughput used — is
 * exactly the failure issue #25 describes: a 50 KB frame over a 1 ms span
 * reads as 400 000 kbps. Here the smallest divisor a sample can ever meet is
 * the window itself, so the reading can only ramp up toward the truth, never
 * spike past it.
 *
 * Pure and JVM-testable: no Context, no Log, and the only clock is the
 * injected [nowMs] — reads pass the timestamp with the bytes, so tests drive
 * the window deterministically.
 *
 * Thread-safe: the hub's status getter reads from the status thread while the
 * encoder callback writes, and the monitor reads aggregates under its own
 * per-client locks — the internal lock keeps either interleaving correct.
 */
class SlidingRateWindow(
    private val windowMs: Long = StreamDefaults.STATS_WINDOW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private class Sample(val timestampMs: Long, val bytes: Long, val frames: Double)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    /**
     * One frame delivery: [bytes] at [timestampMs]. Samples never summed
     * forever — [pruneLocked] drops anything past the window on the way in.
     * A negative byte count is rejected; a zero-byte frame still counts
     * (it is a frame — [measuredFps] sees it, [measuredBitrateKbps] adds 0).
     *
     * [frames] is how many frames the sample stands for — 1 for a real frame
     * delivery, which is every pre-existing caller. The WHEP lane's
     * [com.raulshma.lenscast.streaming.whep.WhepStreamStats] is the caller
     * that spreads one poll's delta over several sub-samples, where a
     * sub-sample is a slice of the poll's interval and may stand for a
     * fractional number of frames (hence a Double, and a zero-frame sample
     * is legitimate: bytes without frames, or recorded silence). A negative
     * or non-finite count is rejected.
     */
    fun record(timestampMs: Long, bytes: Long, frames: Double = 1.0) {
        if (bytes < 0) return
        if (frames < 0.0 || !frames.isFinite()) return
        synchronized(lock) {
            samples.addLast(Sample(timestampMs, bytes, frames))
            pruneLocked(nowMs())
        }
    }

    /** Measured throughput over the window, in kbps; 0.0 while nothing is in it. */
    fun measuredBitrateKbps(): Double = synchronized(lock) {
        pruneLocked(nowMs())
        if (samples.isEmpty()) return 0.0
        return totalBytesLocked() * 8.0 / 1000.0 / (windowMs / 1000.0)
    }

    /** Measured frame rate over the window; 0.0 while nothing is in it. */
    fun measuredFps(): Double = synchronized(lock) {
        pruneLocked(nowMs())
        if (samples.isEmpty()) return 0.0
        return totalFramesLocked() / (windowMs / 1000.0)
    }

    /**
     * The bytes and frame count currently inside the window (post-prune) —
     * the raw sums, for a consumer that completes the window itself instead
     * of dividing by the full span. Both are 0 while nothing is in it.
     */
    fun totals(): Totals = synchronized(lock) {
        pruneLocked(nowMs())
        Totals(bytes = totalBytesLocked(), frames = totalFramesLocked())
    }

    /** The window's surviving bytes and frame count (see [totals]). */
    data class Totals(val bytes: Long, val frames: Double)

    private fun totalBytesLocked(): Long = samples.fold(0L) { acc, sample -> acc + sample.bytes }

    private fun totalFramesLocked(): Double = samples.sumOf { it.frames }

    /** Samples currently inside the window (post-prune). */
    fun sampleCount(): Int = synchronized(lock) {
        pruneLocked(nowMs())
        return samples.size
    }

    /** The slide-out: everything older than now − [windowMs] leaves, oldest first. */
    private fun pruneLocked(now: Long) {
        val cutoff = now - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }
}

package com.raulshma.lenscast.streaming.rtsp

/**
 * The wall-clock anchor one server run's RTP timestamp clocks share. The
 * anchor lands lazily on the first [elapsedMs] — the first access unit fed
 * after start — so a server that starts before any encoder output does not
 * burn its zero point on idle time. [elapsedMsOrNull] reads the elapsed span
 * without anchoring (PLAY's RTP-Info lower bound must not move the zero
 * point), and [reset] returns the epoch to unanchored for the next start.
 *
 * The injected [nowMs] is a monotonic millisecond clock in production
 * (SystemClock.elapsedRealtime); tests drive it deterministically. Plain
 * field on purpose: the drain thread anchors and reads it while session
 * threads peek — the same benign-race discipline as the session state, and
 * a peek that races the anchor only reports the not-yet-anchored lower bound.
 */
class RtpEpoch(private val nowMs: () -> Long) {

    private var anchorMs: Long = -1L

    /** Milliseconds since the first call anchored the epoch. */
    fun elapsedMs(): Long {
        val now = nowMs()
        if (anchorMs < 0) anchorMs = now
        return now - anchorMs
    }

    /** The elapsed span, or null while unanchored — never anchors. */
    fun elapsedMsOrNull(): Long? {
        if (anchorMs < 0) return null
        return nowMs() - anchorMs
    }

    fun reset() {
        anchorMs = -1L
    }
}

/**
 * One media stream's RTP timestamp clock: elapsed wall time on the shared
 * [RtpEpoch], scaled to [clockHz] (90 kHz video, the AAC sample rate audio).
 * This replaces the fixed per-frame increment (90000/fps) that froze players
 * during delivery gaps and fast-forwarded them through bursts: the timestamp
 * now advances by the time actually elapsed, whatever the throttle, the
 * encoder-lag gate, or thermal adaptation did to the frame cadence — the
 * same wall-clock anchor the HLS muxer already uses (HlsManager).
 *
 * [next] is strictly increasing even when the clock steps back or two AUs
 * land inside one millisecond (the HlsManager clamp shape). Long accumulators
 * are deliberate: the wire writers mask to 32 bits (RtpStreamState,
 * RtspSessionProtocol.senderReport), so growth past 2^32 is the clock's
 * honest value, not an overflow to manage here.
 *
 * Plain fields, single-writer discipline: [next] runs on the encoded-hub
 * drain thread only; [current]/[peekNext] are session-thread reads (PLAY's
 * RTP-Info) that race benignly, exactly like the timestamp fields they
 * replace.
 */
class RtpTimestampClock(
    private val clockHz: Long,
    private val epoch: RtpEpoch,
) {

    private var last: Long = -1L

    /** The last timestamp [next] emitted; 0 before the first AU. */
    val current: Long
        get() = if (last < 0) 0L else last

    /** The next timestamp, advancing the clock. Anchors the epoch if needed. */
    fun next(): Long {
        val computed = epoch.elapsedMs() * clockHz / 1000
        val ts = if (computed <= last) last + 1 else computed
        last = ts
        return ts
    }

    /**
     * What [next] would return right now, without advancing the clock or
     * anchoring the epoch — PLAY's RTP-Info lower bound for a stream that
     * may not have fed its first AU yet.
     */
    fun peekNext(): Long {
        val elapsed = epoch.elapsedMsOrNull() ?: return if (last < 0) 0L else last + 1
        val computed = elapsed * clockHz / 1000
        return if (computed <= last) last + 1 else computed
    }

    fun reset() {
        last = -1L
    }
}
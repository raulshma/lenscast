package com.raulshma.lenscast.streaming.rtsp

/**
 * The adaptive encoded-bitrate tap at a client's writer thread: accumulates
 * bytes and real blocking-write nanos, emits one (bytes, ms) sample only
 * once the window reaches [minWindowMs].
 *
 * The window is load-bearing: NetworkQualityMonitor.recordEncodedSend drops
 * samples with durationMs <= 0, and a per-AU LAN write finishes in well
 * under a millisecond — an unwindowed tap at the writer would round every
 * sample to 0 ms and silently disable the encoded-bitrate adaptation.
 * Windowing also keeps the sample cadence honest under congestion: a slow
 * client's long writes land in the same bytes/time ratio as fast ones.
 *
 * Single-writer by construction (one writer thread per client tap); the
 * aggregate consumer (StreamingManager → NetworkQualityMonitor) is
 * thread-safe on its own.
 */
class RtspSendTap(
    private val minWindowMs: Long = DEFAULT_MIN_WINDOW_MS,
    private val onSample: (bytes: Int, durationMs: Long) -> Unit,
) {

    private var windowBytes = 0L
    private var windowNanos = 0L

    /** One AU's write: [bytes] delivered over [nanos] of real send time. */
    fun record(bytes: Int, nanos: Long) {
        windowBytes += bytes
        windowNanos += nanos
        val durationMs = windowNanos / 1_000_000
        if (durationMs >= minWindowMs) {
            onSample(windowBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), durationMs)
            windowBytes = 0L
            windowNanos = 0L
        }
    }

    fun reset() {
        windowBytes = 0L
        windowNanos = 0L
    }

    companion object {
        const val DEFAULT_MIN_WINDOW_MS = 100L
    }
}
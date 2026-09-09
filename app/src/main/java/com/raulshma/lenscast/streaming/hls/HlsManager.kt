package com.raulshma.lenscast.streaming.hls

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS segment ring fed by the RTSP encoder path: H.264 + AAC in, TS segments out.
 * The production [HlsVideoSink]/[HlsSegmentSource]: [RtspServer] feeds through
 * the sink seam and [MediaResponder] serves through the source seam, both
 * injected at construction; [StreamingManager] owns the lifecycle (reset on
 * start/stop) via [setEnabled].
 *
 * Presentation timestamps are anchored to the injected wall clock, not a fixed
 * per-frame increment: the adaptive-bitrate controller changes the actual frame
 * rate at runtime, so a fixed step (e.g. 90kHz/24) silently desyncs video from
 * both the wall clock and the audio track. Anchoring both tracks to the same
 * clock keeps A/V muxed and lets the adaptive frame rate ride through.
 */
object HlsManager : HlsVideoSink, HlsSegmentSource {
    private const val TAG = "HlsManager"
    private const val MAX_SEGMENTS = 8
    // ~48 AUs at 24fps ≈ 2s per segment; EXTINF carries the real duration.
    private const val AUS_PER_SEGMENT = 48
    private const val PTS_HZ = 90_000L

    // How long a served playlist/segment keeps the shared encoders up after
    // the last request: comfortably beyond the served 5x2s playlist window,
    // so a player re-polling on a cold ring sees segments within one refresh.
    private const val DEMAND_WINDOW_MS = 15_000L

    /** Injectable clock (elapsed-realtime ms) — tests drive PTS deterministically. */
    internal var clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    class HlsSegment(val sequence: Long, val bytes: ByteArray, val durationSec: Double)

    private val lock = Any()
    private val segments = ArrayDeque<HlsSegment>()
    private val sequence = AtomicLong(0)
    private val pending = mutableListOf<ByteArray>()
    private var pendingStartPts = -1L
    private var lastVideoPts = -1L
    @Volatile private var enabled = false

    // The last playlist/segment serve, or the enable itself (epoch-ms via the
    // injectable clock) — the demand signal behind [isHot].
    @Volatile private var lastRequestMs = -1L

    @Volatile private var videoAnchorMs = -1L
    @Volatile private var audioAnchorMs = -1L

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            // A freshly enabled ring is hot: the first playlist can land
            // before any request exists, so the grace window starts now.
            lastRequestMs = clockMs()
        } else {
            lastRequestMs = -1L
            reset()
        }
    }

    /**
     * Whether the ring is actively wanted: enabled and asked-for inside the
     * demand window — a playlist or segment serve, or the enable itself. This
     * is the encoded-stream policy's hlsRequested input: once the window
     * passes with no reader, the shared H.264/AAC encoders may wind down.
     * The window comfortably exceeds one segment's fill time (~2s), so a
     * player that starts polling on a cold ring sees segments within one
     * playlist refresh.
     */
    fun isHot(): Boolean =
        enabled && lastRequestMs >= 0 && clockMs() - lastRequestMs <= DEMAND_WINDOW_MS

    fun reset() {
        synchronized(lock) {
            segments.clear()
            pending.clear()
            pendingStartPts = -1
            lastVideoPts = -1
            sequence.set(0)
        }
        videoAnchorMs = -1
        audioAnchorMs = -1
        TsPacketizer.reset()
    }

    override fun feedAudio(aacData: ByteArray) {
        if (!enabled || aacData.isEmpty()) return
        try {
            val now = clockMs()
            if (audioAnchorMs < 0) audioAnchorMs = now
            val audioPts = (now - audioAnchorMs) * PTS_HZ / 1000
            val ts = TsPacketizer.audioFrameToTs(aacData, audioPts)
            synchronized(lock) {
                // Audio rides the current open segment so A/V stay muxed.
                pending.add(ts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS audio feed failed", e)
        }
    }

    override fun feedVideo(nalus: List<com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit>) {
        if (!enabled || nalus.isEmpty()) return
        try {
            val now = clockMs()
            if (videoAnchorMs < 0) videoAnchorMs = now
            // Strictly monotonic even if the clock steps back or two frames
            // land inside the same millisecond.
            val computed = (now - videoAnchorMs) * PTS_HZ / 1000
            val pts = if (computed <= lastVideoPts) lastVideoPts + 1 else computed
            lastVideoPts = pts
            val ts = TsPacketizer.videoAuToTs(nalus.map { it.data }, pts)
            synchronized(lock) {
                if (pendingStartPts < 0) pendingStartPts = pts
                pending.add(ts)
                if (pending.size >= AUS_PER_SEGMENT) {
                    val seq = sequence.incrementAndGet()
                    val combined = pending.fold(ByteArray(0)) { acc, b -> acc + b }
                    val durationSec = (pts - pendingStartPts).toDouble() / PTS_HZ
                    segments.addLast(HlsSegment(seq, combined, durationSec))
                    while (segments.size > MAX_SEGMENTS) segments.removeFirst()
                    pending.clear()
                    pendingStartPts = -1
                    Log.d(TAG, "HLS segment $seq ready (${combined.size}B, ${String.format(java.util.Locale.US, "%.2f", durationSec)}s, window=${segments.size})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS feed failed", e)
        }
    }

    override fun playlist(): String {
        lastRequestMs = clockMs()
        return synchronized(lock) {
            val window = segments.toList().takeLast(HlsPlaylist.WINDOW_SEGMENTS)
            HlsPlaylist.build(
                segmentNames = window.map { HlsPlaylist.segmentName(it.sequence) },
                sequence = sequence.get(),
                segmentDurationsSec = window.map { it.durationSec },
            )
        }
    }

    override fun segment(name: String): ByteArray? {
        lastRequestMs = clockMs()
        return synchronized(lock) {
            segments.firstOrNull { HlsPlaylist.segmentName(it.sequence) == name }?.bytes
        }
    }

    override fun hasSegments(): Boolean = synchronized(lock) { segments.isNotEmpty() }
}

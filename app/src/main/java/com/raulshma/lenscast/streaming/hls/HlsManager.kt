package com.raulshma.lenscast.streaming.hls

import com.raulshma.lenscast.core.StreamDefaults
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
    // A segment closes on the NEXT keyframe once it has spanned at least this
    // long, so every segment opens on an IDR (with SPS/PPS) — a player joining
    // at any segment can initialize. The encoder's 2s GOP sets the real pace.
    private const val MIN_SEGMENT_MS = 1_000L
    private const val PTS_HZ = 90_000L
    private const val MIN_SEGMENT_PTS = PTS_HZ * MIN_SEGMENT_MS / 1000

    // How long a served playlist/segment keeps the shared encoders up after
    // the last request: comfortably beyond the served 5x2s playlist window,
    // so a player re-polling on a cold ring sees segments within one refresh.
    private const val DEMAND_WINDOW_MS = 15_000L

    /** Injectable clock (elapsed-realtime ms) — tests drive PTS deterministically. */
    internal var clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * The adaptive encoded-bitrate tap: segment bytes over the segment's
     * duration, recorded at segment completion while the ring is hot (a hot
     * ring is by definition being pulled). Injected by the Streaming Manager
     * like [clockMs].
     */
    internal var encodedSendTap: ((bytes: Int, durationMs: Long) -> Unit)? = null

    class HlsSegment(
        val sequence: Long,
        val bytes: ByteArray,
        val durationSec: Double,
        /** Wall-clock instant this segment's first AU belongs to — the playlist's PROGRAM-DATE-TIME anchor. */
        val startWallMs: Long = 0L,
        /**
         * True when this segment's first PTS jumps past the previous segment's
         * last (an encoder idle/rejoin gap). The playlist renders
         * `#EXT-X-DISCONTINUITY` before it so a player re-anchors its timestamp
         * mapping instead of carrying the pre-gap offset across the jump.
         */
        val discontinuity: Boolean = false,
    )

    private val lock = Any()
    private val segments = ArrayDeque<HlsSegment>()
    private val sequence = AtomicLong(0)
    private val pending = mutableListOf<ByteArray>()
    private var pendingStartPts = -1L
    private var pendingLastPts = -1L
    private var pendingPrevLastPts = -1L
    private var lastVideoPts = -1L
    // Set when a segment boundary absorbs an encoder-idle PTS gap; consumed by
    // the NEXT segment closed, which is the one that opens after the jump.
    private var nextDiscontinuity = false
    // Set when the upstream encoder reconfigures (orientation/resolution/codec):
    // the next keyframe closes any open segment immediately and tags the post-
    // reconfig segment with #EXT-X-DISCONTINUITY so players re-initialize cleanly.
    private var reconfigPending = false
    @Volatile private var enabled = false

    // DVR window: 0 keeps the sliding live ring (MAX_SEGMENTS + a 5-segment
    // playlist); above that the ring retains up to the setting's segments and
    // the playlist renders as EVENT. Clamped through [setDvrSegments].
    @Volatile private var dvrSegments = StreamDefaults.HLS_DVR_SEGMENTS_DEFAULT

    // The last playlist/segment serve, or the enable itself (epoch-ms via the
    // injectable clock) — the demand signal behind [isHot].
    @Volatile private var lastRequestMs = -1L

    // Both tracks share ONE anchor so audio and video PTS land on the same
    // timeline (a per-track anchor skews A/V by the audio-start offset).
    @Volatile private var videoAnchorMs = -1L

    // The AAC encoder's format for the TS muxer's ADTS headers; the hub
    // reports it whenever the shared encode (re)attaches audio.
    @Volatile private var audioSampleRateHz = 48_000
    @Volatile private var audioChannelCount = 1

    override fun setAudioFormat(sampleRateHz: Int, channelCount: Int) {
        audioSampleRateHz = sampleRateHz
        audioChannelCount = channelCount
    }

    /** Whether web streaming currently wants the ring (route gating + tests). */
    fun isEnabled(): Boolean = enabled

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
     * The DVR window in segments: 0 keeps the sliding live ring; above that
     * the ring retains up to the value (bounded by
     * [StreamDefaults.HLS_DVR_SEGMENTS_MAX]) and the playlist renders as
     * EVENT. Applies to the next segment completions — the existing ring is
     * trimmed lazily, never dropped.
     */
    fun setDvrSegments(segments: Int) {
        dvrSegments = segments.coerceIn(0, StreamDefaults.HLS_DVR_SEGMENTS_MAX)
    }

    fun dvrSegments(): Int = dvrSegments

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
            pendingLastPts = -1
            pendingPrevLastPts = -1
            lastVideoPts = -1
            nextDiscontinuity = false
            reconfigPending = false
            sequence.set(0)
        }
        videoAnchorMs = -1
        TsPacketizer.reset()
    }

    override fun feedAudio(aacData: ByteArray) {
        if (!enabled || aacData.isEmpty()) return
        try {
            val now = clockMs()
            synchronized(lock) {
                // Audio rides the open segment so A/V stay muxed; before the
                // first keyframe there is no segment to ride, and dropping the
                // pre-roll keeps the audio clock anchored to the video's.
                if (pendingStartPts < 0 || videoAnchorMs < 0) return
                // Same anchor as video, so the two tracks share one timeline
                // instead of skewing by the audio-start offset.
                val audioPts = (now - videoAnchorMs) * PTS_HZ / 1000
                pending.add(
                    TsPacketizer.audioFrameToTs(aacData, audioPts, audioSampleRateHz, audioChannelCount),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS audio feed failed", e)
        }
    }

    override fun onStreamReconfigured() {
        synchronized(lock) {
            if (pendingStartPts >= 0) {
                reconfigPending = true
            }
        }
    }

    override fun feedVideo(nalus: List<com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit>) {
        if (!enabled || nalus.isEmpty()) return
        try {
            val now = clockMs()
            val isKeyFrame = nalus.any { it.isKeyFrame }
            // Never open the ring mid-GOP: the first retained AU must be an
            // IDR carrying SPS/PPS, or a joining player can never initialize.
            if (videoAnchorMs < 0) {
                if (!isKeyFrame) return
                videoAnchorMs = now
            }
            // Strictly monotonic even if the clock steps back or two frames
            // land inside the same millisecond.
            val computed = (now - videoAnchorMs) * PTS_HZ / 1000
            val pts = if (computed <= lastVideoPts) lastVideoPts + 1 else computed
            lastVideoPts = pts
            val ts = TsPacketizer.videoAuToTs(nalus.map { it.data }, pts)
            synchronized(lock) {
                if (pendingStartPts < 0) {
                    pendingStartPts = pts
                    reconfigPending = false
                } else if (isKeyFrame && (reconfigPending || pts - pendingStartPts >= MIN_SEGMENT_PTS)) {
                    val markDiscontinuity = reconfigPending
                    reconfigPending = false
                    // Close on the keyframe that OPENS the next segment, so the
                    // duration spans exactly the AUs this segment holds.
                    closeSegmentLocked(pts, forceDiscontinuity = markDiscontinuity)
                    pendingStartPts = pts
                }
                pending.add(ts)
                pendingPrevLastPts = pendingLastPts
                pendingLastPts = pts
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS feed failed", e)
        }
    }

    /** Emits the open segment; [nextStartPts] is the PTS of the AU opening the next one. */
    private fun closeSegmentLocked(nextStartPts: Long, forceDiscontinuity: Boolean = false) {
        val seq = sequence.incrementAndGet()
        val combined = pending.fold(ByteArray(0)) { acc, b -> acc + b }
        // Declare the media the segment actually holds (last AU's display span
        // minus the first AU), never the span up to the next segment's opening
        // keyframe: an encoder stall (rung switch, screen sleep, camera
        // reconfig) opens a PTS gap, and declaring the gap as EXTINF leaves a
        // joining player stranded — it consumes the real media in less than
        // the declared duration, freezes short of the next segment's boundary
        // with nothing left to load, and buffers forever while the window
        // slides past it. The gap rides the boundary instead. Found on the
        // loopback E2E: a rung switch mid-join produced a 10.5s EXTINF holding
        // 0.6s of media and the companion's HLS rung died in exactly that pose.
        val lastFrameDelta =
            if (pendingPrevLastPts >= 0) pendingLastPts - pendingPrevLastPts else 0L
        val durationSec = (pendingLastPts + lastFrameDelta - pendingStartPts)
            .coerceAtLeast(1L)
            .toDouble() / PTS_HZ
        // [clockMs] is a monotonic source (uptime-shaped), not wall time: map
        // the segment start through the elapsed span since the video anchor,
        // measured on the same clock, onto the wall clock read now. This tracks
        // real wall time even across an idle gap (an incremental PDT would fall
        // behind wall time by the accumulated gaps, and media3 — which estimates
        // the live edge from the device clock — would join ahead of the media).
        // A stall-absorbing segment is the exception: its first AU predates the
        // gap, so the mapping pins its PDT to the pre-gap wall instant — and a
        // playlist that mixes pre-gap PDTs with post-gap ones teleports its
        // window start forward by the gap when the stale front ages out,
        // leaving any player joined before the jump stranded behind the window
        // with nothing to load (found on the loopback E2E: an ~6min encoder
        // idle moved winStart +370s under a playing companion). Clamp such a
        // segment's PDT to the wall it closed at (start = now − its honest
        // duration, end = now) and, in live mode, drop the pre-gap segments —
        // the served window then never spans the discontinuity, so the
        // timeline can never teleport. (#EXT-X-DISCONTINUITY on the next
        // segment still marks the TS timestamp jump for mid-GOP players.)
        val gapMs = (nextStartPts - pendingLastPts) / (PTS_HZ / 1000)
        val startWallMs = if (gapMs > 500) {
            System.currentTimeMillis() - (durationSec * 1000).toLong()
        } else if (videoAnchorMs >= 0) {
            val msSinceAnchor = clockMs() - (videoAnchorMs + pendingStartPts / (PTS_HZ / 1000))
            System.currentTimeMillis() - msSinceAnchor
        } else 0L
        if (gapMs > 500) {
            Log.w(TAG, "HLS encoder stall ${gapMs}ms absorbed at segment boundary")
            if (dvrSegments == 0) {
                segments.clear()
                Log.i(TAG, "HLS stall gap: live ring trimmed to the post-gap era (PDT clamped to wall)")
            }
        }
        segments.addLast(HlsSegment(seq, combined, durationSec, startWallMs, discontinuity = nextDiscontinuity))
        // The gap or stream reconfig opens the NEXT segment: flag it so the
        // playlist marks the discontinuity there and a joining player re-anchors.
        nextDiscontinuity = forceDiscontinuity || gapMs > 500
        // The ring bound: the DVR setting while raised, else the
        // compact sliding-live cap.
        val cap = if (dvrSegments > 0) dvrSegments else MAX_SEGMENTS
        while (segments.size > cap) segments.removeFirst()
        pending.clear()
        // Demand-gated sample: a hot ring is being pulled, so the
        // segment's bytes over its duration are honest send-side
        // throughput for the adaptive encoded-bitrate lane.
        if (isHot()) {
            encodedSendTap?.invoke(
                combined.size,
                (durationSec * 1000).toLong().coerceAtLeast(1),
            )
        }
        Log.d(TAG, "HLS segment $seq ready (${combined.size}B, ${String.format(java.util.Locale.US, "%.2f", durationSec)}s, window=${segments.size})")
    }

    override fun playlist(): String {
        lastRequestMs = clockMs()
        return synchronized(lock) {
            val dvr = dvrSegments > 0
            val window = if (dvr) segments.toList() else segments.toList().takeLast(HlsPlaylist.WINDOW_SEGMENTS)
            HlsPlaylist.build(
                segmentNames = window.map { HlsPlaylist.segmentName(it.sequence) },
                sequence = sequence.get(),
                segmentDurationsSec = window.map { it.durationSec },
                dvr = dvr,
                windowStartEpochMs = window.firstOrNull()?.startWallMs?.takeIf { it > 0 },
                discontinuities = window.map { it.discontinuity },
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

    override fun noteRequest() {
        lastRequestMs = clockMs()
    }
}

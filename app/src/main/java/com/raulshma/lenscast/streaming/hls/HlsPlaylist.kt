package com.raulshma.lenscast.streaming.hls

import kotlin.math.ceil

/**
 * Pure HLS playlist math: sliding-window m3u8 builder.
 * JVM-tested; the manager keeps segment bytes, this renders text.
 *
 * Segment durations come from the manager's wall-clock-anchored PTS spans;
 * [TARGET_DURATION_SEC] is only the fallback when a duration is unknown.
 *
 * Two shapes:
 *  - LIVE (default): a sliding window — only the most recent [WINDOW_SEGMENTS]
 *    segments are listed, and the media sequence advances as old segments
 *    drop off.
 *  - EVENT (DVR, [dvr] = true): `#EXT-X-PLAYLIST-TYPE:EVENT` and every
 *    retained segment listed — players may seek the full window; the media
 *    sequence stays anchored at the oldest retained segment. The manager
 *    bounds the ring itself (the DVR segment setting), so "EVENT" here means
 *    "everything the ring still holds".
 */
object HlsPlaylist {
    const val TARGET_DURATION_SEC = 2
    const val WINDOW_SEGMENTS = 5

    fun build(
        segmentNames: List<String>,
        sequence: Long,
        targetDurationSec: Int = TARGET_DURATION_SEC,
    ): String = build(segmentNames, sequence, List(segmentNames.size) { targetDurationSec.toDouble() })

    fun build(
        segmentNames: List<String>,
        sequence: Long,
        segmentDurationsSec: List<Double>,
        dvr: Boolean = false,
        windowStartEpochMs: Long? = null,
        discontinuities: List<Boolean> = emptyList(),
    ): String {
        val window: List<String>
        val durations: List<Double>
        val discont: List<Boolean>
        if (dvr) {
            // EVENT: no sliding removal — the caller's full retained list renders.
            window = segmentNames
            durations = segmentDurationsSec
            discont = discontinuities
        } else {
            window = segmentNames.takeLast(WINDOW_SEGMENTS)
            durations = segmentDurationsSec.takeLast(WINDOW_SEGMENTS)
            discont = discontinuities.takeLast(WINDOW_SEGMENTS)
        }
        val target = maxOf(
            TARGET_DURATION_SEC,
            ceil((durations.maxOrNull() ?: 0.0)).toInt().coerceAtLeast(1),
        )
        // An empty window anchors at 0 — (sequence + 1) would be the next
        // segment's number, which no listed segment can back.
        val startSeq = if (window.isEmpty()) 0 else (sequence - window.size + 1).coerceAtLeast(0)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            if (dvr) appendLine("#EXT-X-PLAYLIST-TYPE:EVENT")
            appendLine("#EXT-X-TARGETDURATION:$target")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$startSeq")
            // The absolute anchor for the window's first segment. Without it a
            // sliding no-PDT playlist rebases its timeline on every refresh and
            // media3 (ExoPlayer) pins the playback position to the front edge —
            // the window slides away underneath it and the player stalls in
            // BUFFERING forever (found on the loopback E2E; hls.js tolerates
            // the anchor-less shape, ExoPlayer does not).
            if (windowStartEpochMs != null && window.isNotEmpty()) {
                appendLine("#EXT-X-PROGRAM-DATE-TIME:${formatProgramDateTime(windowStartEpochMs)}")
            }
            window.forEachIndexed { index, name ->
                // A PTS jump (encoder idle/rejoin) opens this segment: tell the
                // player to re-anchor its timestamp mapping here, else a shared
                // adjuster carries the pre-gap offset and strands every sample
                // ahead of the playback position (the companion HLS stall).
                if (discont.getOrElse(index) { false }) appendLine("#EXT-X-DISCONTINUITY")
                appendLine("#EXTINF:${String.format(java.util.Locale.US, "%.3f", durations.getOrElse(index) { TARGET_DURATION_SEC.toDouble() })},")
                appendLine(name)
            }
        }
    }

    fun segmentName(sequence: Long): String = "seg$sequence.ts"

    /** The spec's ISO-8601 UTC instant (`2026-11-15T14:30:00.123Z`) — SimpleDateFormat, not java.time: minSdk 23. */
    internal fun formatProgramDateTime(epochMs: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(epochMs))
    }
}

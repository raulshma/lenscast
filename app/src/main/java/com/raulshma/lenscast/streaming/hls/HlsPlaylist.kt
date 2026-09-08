package com.raulshma.lenscast.streaming.hls

import kotlin.math.ceil

/**
 * Pure HLS playlist math: sliding-window m3u8 builder.
 * JVM-tested; the manager keeps segment bytes, this renders text.
 *
 * Segment durations come from the manager's wall-clock-anchored PTS spans;
 * [TARGET_DURATION_SEC] is only the fallback when a duration is unknown.
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
    ): String {
        val window = segmentNames.takeLast(WINDOW_SEGMENTS)
        val durations = segmentDurationsSec.takeLast(WINDOW_SEGMENTS)
        val target = maxOf(
            TARGET_DURATION_SEC,
            ceil((durations.maxOrNull() ?: 0.0)).toInt().coerceAtLeast(1),
        )
        val startSeq = (sequence - window.size + 1).coerceAtLeast(0)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:$target")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$startSeq")
            window.forEachIndexed { index, name ->
                appendLine("#EXTINF:${String.format(java.util.Locale.US, "%.3f", durations.getOrElse(index) { TARGET_DURATION_SEC.toDouble() })},")
                appendLine(name)
            }
        }
    }

    fun segmentName(sequence: Long): String = "seg$sequence.ts"
}

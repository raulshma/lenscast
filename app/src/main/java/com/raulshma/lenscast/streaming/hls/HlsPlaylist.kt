package com.raulshma.lenscast.streaming.hls

/**
 * Pure HLS playlist math: sliding-window m3u8 builder.
 * JVM-tested; the manager keeps segment bytes, this renders text.
 */
object HlsPlaylist {
    const val TARGET_DURATION_SEC = 2
    const val WINDOW_SEGMENTS = 5

    fun build(segmentNames: List<String>, sequence: Long, targetDurationSec: Int = TARGET_DURATION_SEC): String {
        val window = segmentNames.takeLast(WINDOW_SEGMENTS)
        val startSeq = (sequence - window.size + 1).coerceAtLeast(0)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:$targetDurationSec")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$startSeq")
            for (name in window) {
                appendLine("#EXTINF:${targetDurationSec.toDouble()},")
                appendLine(name)
            }
        }
    }

    fun segmentName(sequence: Long): String = "seg$sequence.ts"
}

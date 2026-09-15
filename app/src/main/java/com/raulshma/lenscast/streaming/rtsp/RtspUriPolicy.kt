package com.raulshma.lenscast.streaming.rtsp

/**
 * Pure RTSP URI knowledge: path extraction and normalization, the
 * aggregate/stream/track classification, track-ID resolution, and the
 * per-method URI acceptance set. Android-free so the acceptance behavior is
 * JVM-tested; [RtspServer] only applies the verdicts to responses.
 */
object RtspUriPolicy {

    /** Aggregate stream path segment — the RTSP URL is rtsp://host:port/stream. */
    const val DEFAULT_STREAM_PATH = "stream"

    /**
     * Aggregate sub-stream path — the low-res detect-role second encode, its
     * own RTSP URL (rtsp://host:port/sub) with its own SDP, not a second
     * MediaDescription inside the main SDP. Video-only.
     */
    const val SUB_STREAM_PATH = "sub"

    /**
     * The one track grammar every wire surface restates: `trackID=0` is the
     * video track (SDP control, SETUP path, RTP-Info), `trackID=1` its audio
     * twin. SdpBuilder advertises them and RtspServer resolves them through
     * these constants so the numbers never drift apart per call site.
     */
    const val VIDEO_TRACK_ID = 0
    const val AUDIO_TRACK_ID = 1

    /** Whether [method] may address [requestUri] at all (404 verdict otherwise). */
    fun isRequestUriAllowed(method: String, requestUri: String): Boolean {
        return when (method) {
            "OPTIONS", "DESCRIBE" -> isAggregateOrStreamUri(requestUri) || isSubStreamUri(requestUri)
            "SETUP" -> isStreamControlUri(requestUri) || isTrackUri(requestUri) || isSubControlUri(requestUri)
            "PLAY", "TEARDOWN" ->
                isAggregateOrStreamUri(requestUri) || isStreamControlUri(requestUri) || isSubControlUri(requestUri)
            "GET_PARAMETER", "SET_PARAMETER" -> true
            else -> true
        }
    }

    fun isAggregateOrStreamUri(requestUri: String): Boolean {
        val path = normalizedPath(requestUri)
        return path == "/" || path == "/$DEFAULT_STREAM_PATH"
    }

    /** The sub-stream's aggregate path: exactly `/sub`. */
    fun isSubStreamUri(requestUri: String): Boolean =
        normalizedPath(requestUri) == "/$SUB_STREAM_PATH"

    /** The sub-stream's video track: `/sub/trackID=0` (or the bare `/sub` aggregate). */
    fun isSubControlUri(requestUri: String): Boolean {
        if (isSubStreamUri(requestUri)) return true
        val path = normalizedPath(requestUri)
        return path.equals("/$SUB_STREAM_PATH/trackID=$VIDEO_TRACK_ID", ignoreCase = true)
    }

    fun isStreamControlUri(requestUri: String): Boolean {
        val path = normalizedPath(requestUri)
        if (path == "/$DEFAULT_STREAM_PATH") return true
        if (path.equals("/trackid=$VIDEO_TRACK_ID", ignoreCase = true)) return true
        if (path.startsWith("/$DEFAULT_STREAM_PATH/trackid=", ignoreCase = true)) return true
        if (path.startsWith("/$DEFAULT_STREAM_PATH/track", ignoreCase = true)) return true
        return false
    }

    fun isTrackUri(requestUri: String): Boolean {
        val path = normalizedPath(requestUri)
        if (path.equals("/trackID=$VIDEO_TRACK_ID", ignoreCase = true)) return true
        if (path.equals("/trackID=$AUDIO_TRACK_ID", ignoreCase = true)) return true
        if (path.startsWith("/$DEFAULT_STREAM_PATH/trackID=", ignoreCase = true)) return true
        return false
    }

    /** Track 0 = video, track 1 = audio; the aggregate/stream path means video. */
    fun resolveTrackId(requestUri: String): Int? {
        val path = normalizedPath(requestUri)
        // Aggregate or stream path defaults to video (track 0)
        if (path == "/$DEFAULT_STREAM_PATH" || path == "/") return VIDEO_TRACK_ID
        // Explicit trackID matching — toIntOrNull, never toInt: a hostile
        // SETUP `.../trackID=99999999999999` is a 404, not a dead session.
        val trackMatch = Regex("""/trackID=(\d+)$""", RegexOption.IGNORE_CASE).find(path)
        if (trackMatch != null) {
            val id = trackMatch.groupValues[1].toIntOrNull() ?: return null
            return if (id == VIDEO_TRACK_ID || id == AUDIO_TRACK_ID) id else null
        }
        return null
    }

    /** Extract + normalize in one step — the form every check consumes. */
    fun normalizedPath(requestUri: String): String = normalizeRtspPath(extractRtspPath(requestUri))

    /** Strips scheme/authority from an absolute rtsp:// URI; anything not starting with / gets one. */
    fun extractRtspPath(requestUri: String): String {
        val path = if (requestUri.startsWith("rtsp://", ignoreCase = true)) {
            val schemeSep = requestUri.indexOf("://")
            val afterScheme = if (schemeSep >= 0) requestUri.substring(schemeSep + 3) else requestUri
            val slashIndex = afterScheme.indexOf('/')
            if (slashIndex >= 0) afterScheme.substring(slashIndex) else "/"
        } else {
            requestUri
        }
        return if (path.startsWith('/')) path else "/$path"
    }

    /** Drops query/fragment, collapses `//`, strips the trailing `/`, and guarantees a leading `/`. */
    fun normalizeRtspPath(path: String): String {
        var normalized = path.substringBefore('?').substringBefore('#').trim()
        if (normalized.isEmpty()) return "/"
        if (!normalized.startsWith('/')) normalized = "/$normalized"
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/")
        }
        if (normalized.length > 1 && normalized.endsWith('/')) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }
}

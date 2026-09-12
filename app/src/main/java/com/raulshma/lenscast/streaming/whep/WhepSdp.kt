package com.raulshma.lenscast.streaming.whep

/**
 * The pure, hostile-input-hardened SDP offer gate for the WHEP endpoint: the
 * only code that touches a viewer's offer before libwebrtc does. It answers
 * one question — "is this plausibly an SDP offer that wants our video?" —
 * so garbage never reaches the native SDP parser and the route can answer a
 * readable 400 instead of an opaque setRemoteDescription failure.
 *
 * Deliberately minimal: libwebrtc owns full RFC 8829 validation and rejects
 * malformed descriptions itself; this gate only enforces the shape and the
 * size bounds (a bounded byte cap, a bounded line count, printable-text
 * only, `v=0` first, at least one `m=video` section). CRLF and LF line
 * endings are both accepted; NUL bytes and other control characters are not.
 */
object WhepSdp {

    /** The largest offer body the route accepts (a real browser offer is 1–4 KB). */
    const val MAX_OFFER_BYTES = 64 * 1024

    /** The most lines parsed before the offer is declared hostile — 64 KB of one-line SDP is not SDP. */
    const val MAX_LINES = 2_000

    /** What the offer asks for, as far as this gate cares. */
    data class Offer(val hasVideo: Boolean, val hasAudio: Boolean)

    /**
     * The offer verdict: non-null when the body may proceed to
     * setRemoteDescription. The verdict requires a `m=video` section — a
     * LensCast viewer without video is not a session worth a hardware
     * encoder — while audio is optional (the mic-arbitration verdict, not
     * the offer, decides whether audio is answered).
     */
    fun parseOffer(sdp: String): Offer? {
        if (sdp.isEmpty() || sdp.length > MAX_OFFER_BYTES) return null
        var hasVideo = false
        var hasAudio = false
        var lines = 0
        for (rawLine in sdp.lineSequence()) {
            if (++lines > MAX_LINES) return null
            val line = rawLine.trimEnd('\r')
            when {
                lines == 1 -> if (line != "v=0") return null
                line.startsWith("m=") -> {
                    val media = line.substring(2).trim().substringBefore(' ')
                    when (media) {
                        "video" -> hasVideo = true
                        "audio" -> hasAudio = true
                    }
                }
            }
            // SDP is printable ASCII text: NUL or any other C0 control (and
            // DEL) beyond the line breaks already split marks the body
            // hostile.
            for (ch in line) {
                if (ch < ' ' || ch == '\u007F') return null
            }
        }
        return if (hasVideo) Offer(hasVideo = true, hasAudio = hasAudio) else null
    }
}

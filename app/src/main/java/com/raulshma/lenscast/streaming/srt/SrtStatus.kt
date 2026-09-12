package com.raulshma.lenscast.streaming.srt

/**
 * The SRT push output's lifecycle state — what the settings screen's status
 * row and the `/api/status` snapshot report. Same wire names as the RTMP
 * status line ([com.raulshma.lenscast.streaming.rtmp.RtmpStatus]); [Error]
 * carries the readable message (invalid URL, H.265 refusal, handshake
 * failure) so a failed push surfaces why, not just that.
 */
sealed class SrtStatus(val wireName: String) {

    /** Not started (or stopped). */
    data object Idle : SrtStatus("idle")

    /** Handshaking / reconnecting — the auto-reconnect loop's between-attempts state. */
    data object Connecting : SrtStatus("connecting")

    /** Conclusion accepted — MPEG-TS is flowing to the listener. */
    data object Connected : SrtStatus("connected")

    /** The last attempt failed; [message] says why. Reconnects continue while the output is on. */
    data class Error(val message: String) : SrtStatus("error")
}

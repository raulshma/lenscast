package com.raulshma.lenscast.streaming.srt

import com.raulshma.lenscast.streaming.PushStatus

/**
 * The SRT push output's lifecycle state — what the settings screen's status
 * row and the `/api/status` snapshot report. Same wire names as the RTMP
 * status line ([com.raulshma.lenscast.streaming.rtmp.RtmpStatus]); [Error]
 * carries the readable message (invalid URL, H.265 refusal, handshake
 * failure) so a failed push surfaces why, not just that.
 *
 * The exact public shape is the web contract (same names, same
 * constructors, same `wireName` values), so this extends the shared
 * [PushStatus] base without changing anything the consumers see — the deep
 * [com.raulshma.lenscast.streaming.PushOutput] module holds statuses through
 * that base.
 */
sealed class SrtStatus(wireName: String) : PushStatus(wireName) {

    /** Not started (or stopped). */
    data object Idle : SrtStatus("idle")

    /** Handshaking / reconnecting — the auto-reconnect loop's between-attempts state. */
    data object Connecting : SrtStatus("connecting")

    /** Conclusion accepted — MPEG-TS is flowing to the listener. */
    data object Connected : SrtStatus("connected")

    /** The last attempt failed; [message] says why. Reconnects continue while the output is on. */
    data class Error(val message: String) : SrtStatus("error")
}

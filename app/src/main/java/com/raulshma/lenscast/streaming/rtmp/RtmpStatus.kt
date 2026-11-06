package com.raulshma.lenscast.streaming.rtmp

import com.raulshma.lenscast.streaming.PushStatus

/**
 * The RTMP push output's lifecycle state — what the settings screen's status
 * row and the `/api/status` snapshot report. [Error] carries the readable
 * message (invalid URL, H.265 refusal, rejected connect/publish) so a failed
 * push surfaces why, not just that.
 *
 * The exact public shape is the web contract (same names, same
 * constructors, same `wireName` values), so this extends the shared
 * [PushStatus] base without changing anything the consumers see — the deep
 * [com.raulshma.lenscast.streaming.PushOutput] module holds statuses through
 * that base.
 */
sealed class RtmpStatus(wireName: String) : PushStatus(wireName) {

    /** Not started (or stopped). */
    data object Idle : RtmpStatus("idle")

    /** Connecting/handshaking/publishing — the auto-reconnect loop's between-attempts state. */
    data object Connecting : RtmpStatus("connecting")

    /** Publish confirmed — media is flowing to the server. */
    data object Connected : RtmpStatus("connected")

    /** The last attempt failed; [message] says why. Reconnects continue while the output is on. */
    data class Error(val message: String) : RtmpStatus("error")
}

package com.raulshma.lenscast.streaming.whip

/**
 * The WHIP push output's lifecycle state — what the settings screen's status
 * row and the `/api/status` snapshot report. [Error] carries the readable
 * message (invalid URL, rejected offer, failed ICE) so a failed push surfaces
 * why, not just that. The [RtmpStatus] twin minus the codec gate: libwebrtc
 * encodes its own H.264, so the RTMP-only H.265 refusal never applies here.
 */
sealed class WhipStatus(val wireName: String) {

    /** Not started (or stopped). */
    data object Idle : WhipStatus("idle")

    /** Building the session / gathering ICE / POSTing the offer — the between-attempts state. */
    data object Connecting : WhipStatus("connecting")

    /** Offer accepted (201 + answer applied) — media is flowing toward the server. */
    data object Connected : WhipStatus("connected")

    /** The last attempt failed; [message] says why. Reconnects continue while the output is on. */
    data class Error(val message: String) : WhipStatus("error")
}

package com.raulshma.lenscast.streaming

/**
 * The pure start/stop verdict for the shared H.264/AAC encode pipeline
 * ([EncodedStreamHub]): the pipeline runs whenever ANY encoded sink is
 * active — the RTSP output, a requested HLS ring, or WS video clients on a
 * live web output. This is the decision that used to live implicitly in
 * "pushFrameToRtsp no-ops unless the RTSP output is active", which left HLS
 * and WS video without a source whenever RTSP was off.
 */
object EncodedStreamPolicy {

    /** The sink-activity snapshot the verdict reads. */
    data class Inputs(
        val webActive: Boolean,
        val rtspActive: Boolean,
        val hlsRequested: Boolean,
        val wsVideoClients: Int,
    )

    fun shouldRun(inputs: Inputs): Boolean =
        inputs.rtspActive || inputs.hlsRequested || (inputs.webActive && inputs.wsVideoClients > 0)

    /**
     * The hub's audio (re)attach verdict: a capture that is running wins a
     * fresh subscriber pipe + encoder start unless a healthy encoder is
     * already attached to the same capture format. A running encoder whose
     * format drifted (bitrate/channels/sample rate changed) reattaches — the
     * hub's version of the RTSP output's audio restart ladder.
     */
    fun shouldAttachAudio(
        aacRunning: Boolean,
        captureRunning: Boolean,
        formatMatches: Boolean,
    ): Boolean = captureRunning && !(aacRunning && formatMatches)
}

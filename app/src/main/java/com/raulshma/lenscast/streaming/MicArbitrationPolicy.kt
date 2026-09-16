package com.raulshma.lenscast.streaming

/**
 * Pure mic-arbitration verdicts: who holds the shared capture and when a
 * dedicated capture (WHIP push) may coexist or must go video-only.
 * JVM-tested; [StreamingManager] keeps the capture handle and the flows.
 */
object MicArbitrationPolicy {

    /** The arbitration inputs, snapshotted from the manager's live state. */
    data class Inputs(
        val webStreamingActive: Boolean,
        val webStreamingEnabled: Boolean,
        val streamAudioEnabled: Boolean,
        val recordingAudioCaptureActive: Boolean,
        val ecoIdleActive: Boolean,
        val soundDetectionEnabled: Boolean,
    )

    /**
     * Viewer-facing web audio: would the shared capture serve stream
     * consumers (talkback, the /audio feed, the encoded hub) right now?
     */
    fun webAudioWanted(inputs: Inputs): Boolean =
        inputs.webStreamingActive &&
            inputs.webStreamingEnabled &&
            inputs.streamAudioEnabled &&
            !inputs.recordingAudioCaptureActive &&
            !inputs.ecoIdleActive

    /**
     * Sound detection's surveillance hold: the RMS detector must stay fed
     * with no stream active, no viewers, and through eco idle — exactly like
     * motion runs on frames with zero viewers. Recording audio capture wins:
     * while it holds the mic, nobody else captures.
     */
    fun detectionWantsMic(inputs: Inputs): Boolean =
        inputs.soundDetectionEnabled && !inputs.recordingAudioCaptureActive

    /**
     * The WHIP publisher's audio verdict: a dedicated AudioRecord may join
     * unless recording owns the mic or the shared capture is already running
     * for viewer-facing web audio. A capture held up only by sound detection
     * does not block the push — the two records coexist in-process, and
     * disarming detection while a push runs would leave surveillance deaf.
     */
    fun whipAudioAllowed(inputs: Inputs, sharedCaptureRunning: Boolean): Boolean =
        inputs.streamAudioEnabled &&
            !inputs.recordingAudioCaptureActive &&
            !inputs.ecoIdleActive &&
            !(sharedCaptureRunning && webAudioWanted(inputs))
}

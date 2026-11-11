package com.raulshma.lenscast.streaming.rtsp

/**
 * One RTSP connection's /sub-stream phase: the SETUP gate, the PLAY gate,
 * the keyframe wait, and the negotiated interleaved channel pair — the twin
 * of the aggregate machine (SessionState + TrackState.isSetup +
 * awaitingKeyframe), extracted so the twin's verdicts are named and
 * JVM-tested (RtspSubStreamStateTest — the first JVM pin on the sub session
 * decisions; no JVM test drives RtspServer itself).
 *
 * Plain mutable fields on purpose: written by the session thread and
 * read/written by the encoded-hub drain thread exactly as the fields this
 * replaces — no volatile, no lock, same benign-race story as the aggregate
 * isPlaying read. Swapping to volatile/immutable would publish the
 * keyframe-gate transitions at different visibility points and change which
 * access unit is first delivered after a mid-GOP PLAY.
 */
class RtspSubStreamState {

    var isSetup = false // was subSetup
        private set
    var isWanted = false // was isSubWanted
        private set
    var awaitingKeyframe = true // was awaitingSubKeyframe
        private set
    var rtpChannel = 0 // was subRtpChannel
        private set
    var rtcpChannel = 1 // was subRtcpChannel
        private set

    /**
     * SETUP's channel landing — call only under the TransportVerdict
     * Interleaved-with-channels branch, before [onSetupAccepted] (a header
     * absent/unparseable pair skips this and keeps the defaults).
     */
    fun onChannelsNegotiated(rtp: Int, rtcp: Int) {
        rtpChannel = rtp
        rtcpChannel = rtcp
    }

    fun onSetupAccepted() {
        isSetup = true
    }

    /**
     * The sub PLAY's arming: marks the stream wanted and (re)arms the
     * keyframe wait. Must stay immediately adjacent to the caller's
     * encodedSource.requestSubKeyFrame() call — every PLAY (re)arms the
     * wait, then forces a sync frame, and nothing may read the gate between
     * the two.
     */
    fun onPlayArmed() {
        isWanted = true
        awaitingKeyframe = true
    }

    /** TEARDOWN is aggregate: everything drops, the keyframe wait re-arms. */
    fun onTeardown() {
        isWanted = false
        isSetup = false
        awaitingKeyframe = true
    }

    /**
     * The send queue dropped queued AUs: the next access unit must start at
     * a fresh sync frame, so the keyframe wait re-arms. Same plain-field
     * discipline as [onPlayArmed] — consulted on the drain thread between
     * the gate check and the enqueue.
     */
    fun onDropRearm() {
        awaitingKeyframe = true
    }

    /** Per-AU gate (was acceptSubAu): sub PLAY + own keyframe wait. */
    fun acceptAu(isKeyframeAu: Boolean): Boolean {
        if (!isWanted || !isSetup) return false
        if (awaitingKeyframe) {
            if (!isKeyframeAu) return false
            awaitingKeyframe = false
        }
        return true
    }

    /** describe(): the media-list entry rides the SETUP gate. */
    val advertised: Boolean
        get() = isSetup
}

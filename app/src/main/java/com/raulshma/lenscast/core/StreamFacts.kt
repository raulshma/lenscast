package com.raulshma.lenscast.core

import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.streaming.RtspHealth
import kotlinx.coroutines.flow.StateFlow

/**
 * The live reads the reliability runtime consumes from the streaming
 * substrate — the review's "stream facts". [StreamingManager] is the
 * production implementation (the members carry its existing names), and a
 * JVM test can fake the whole interface, so [StreamWatchdog]'s loop
 * choreography — the arming handoff, the ticks, the escalation ladder, the
 * verification windows — runs under a TestScope with no server, no camera,
 * and no real time.
 *
 * Deliberately a seam, not an abstraction of the manager: every member is a
 * read the watchdog's decisions already consumed through the manager's
 * public surface, and nothing more. The write side stays on
 * [StreamWatchdog]'s constructor — the recovery lever, the loopback probe,
 * and the wall clock are actions and clocks, not facts.
 */
interface StreamFacts {

    /**
     * The web server's up/down flag, as the server lifecycle publishes it —
     * the arming handoff's read (a session end hands the loop to the
     * server-liveness baseline only while this is up) and the snapshot's
     * [WatchdogPolicy.HealthSnapshot.serverRunning].
     */
    val isServerRunning: StateFlow<Boolean>

    /**
     * Whether the RTSP output holds a serving server — the snapshot's
     * [WatchdogPolicy.HealthSnapshot.rtspActive], gating the RTSP-health
     * branches.
     */
    val isRtspRunning: StateFlow<Boolean>

    /**
     * Frames the M-JPEG pipeline processed — moves only for real viewers,
     * which is the viewer-side stall check's counter and the baseline the
     * loop re-origins on every healthy tick.
     */
    val processedFrames: StateFlow<Int>

    /** Connected web viewers, as the transport reports them. */
    val clientCount: StateFlow<Int>

    /**
     * The camera HAL's published state — the CAMERA_ERROR gate and the
     * camera-Ready half of the HARD recovery verification.
     */
    val cameraState: StateFlow<CameraState>

    /**
     * The monotonic camera-frame count: advances for every sensor frame
     * regardless of viewers — the headless-stall signal (the old
     * client-gated check was blind on a locked mount with zero clients) and
     * the viewer-independent measure of the SOFT recovery verification.
     */
    fun cameraFrameCount(): Long

    /**
     * The RTSP output's health snapshot (playing clients, accepted frames,
     * healthy). May throw while the output is wedged — callers guard it, a
     * failed read counting as "no health data", never as a crash.
     */
    fun getRtspHealth(): RtspHealth

    /**
     * Whether any output is live right now (web, RTSP, RTMP, WHIP, SRT) —
     * the STREAM_STOPPED check's read and the MEDIUM/HARD verification's
     * verdict input.
     */
    fun isLiveStreaming(): Boolean
}

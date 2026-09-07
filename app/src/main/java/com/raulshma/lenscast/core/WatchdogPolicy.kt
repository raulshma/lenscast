package com.raulshma.lenscast.core

/**
 * Pure decisions for the Stream Watchdog: the recovery-tier ladder, the
 * stream-health evaluation over a [HealthSnapshot], the user-facing failure
 * strings, the frame-tracking bookkeeping a healthy check leaves behind, and
 * the recovery verification windows. The watchdog's coroutine loop keeps the
 * timing, the recovery calls, and the state; every decision delegates here.
 */
object WatchdogPolicy {

    /** With clients connected, frames must advance within this window. */
    const val FRAME_STALL_THRESHOLD_MS = 15_000L

    /** Soft/medium recovery: wait this long, then verify. */
    const val RECOVERY_VERIFICATION_DELAY_MS = 2_000L
    /** Soft recovery observes the frame counters for this long. */
    const val RECOVERY_VERIFICATION_WINDOW_MS = 3_000L
    /** Hard recovery re-initialized everything — give it twice the delay. */
    const val HARD_VERIFICATION_DELAY_MS = 2 * RECOVERY_VERIFICATION_DELAY_MS

    enum class RecoveryTier {
        SOFT,   // Rebind use cases
        MEDIUM, // Restart server + rebind
        HARD,   // Full re-init
    }

    enum class StallReason {
        CAMERA_ERROR,
        SERVER_STOPPED,
        STREAM_STOPPED,
        FRAME_STALL,
    }

    /** Everything [evaluate] needs — a snapshot of the watchdog's live inputs. */
    data class HealthSnapshot(
        val cameraError: Boolean,
        val cameraErrorMessage: String? = null,
        val wasStreamingActive: Boolean,
        val serverRunning: Boolean,
        val liveStreaming: Boolean,
        val clientCount: Int,
        val processedFrames: Int,
        val lastProcessedFrameCount: Int,
        val lastFrameCheckTimeMs: Long,
        val nowMs: Long,
    )

    /** Escalation ladder: first two failures soft, next two medium, then hard. */
    fun tierFor(consecutiveFailures: Int): RecoveryTier {
        return when {
            consecutiveFailures <= 2 -> RecoveryTier.SOFT
            consecutiveFailures <= 4 -> RecoveryTier.MEDIUM
            else -> RecoveryTier.HARD
        }
    }

    /**
     * The health verdict in branch order — camera error, server stopped,
     * stream stopped, frame stall — or null when healthy. Frame-stall
     * detection applies only while clients are connected.
     */
    fun evaluate(snapshot: HealthSnapshot): StallReason? {
        // 1. Camera state check
        if (snapshot.cameraError) {
            return StallReason.CAMERA_ERROR
        }

        // 2. Streaming should be active but server isn't running
        if (snapshot.wasStreamingActive && !snapshot.serverRunning) {
            return StallReason.SERVER_STOPPED
        }

        // 3. Stream was active but is no longer live
        if (snapshot.wasStreamingActive && !snapshot.liveStreaming) {
            return StallReason.STREAM_STOPPED
        }

        // 4. Frame stall detection — only when clients are connected
        if (snapshot.clientCount > 0 &&
            snapshot.nowMs - snapshot.lastFrameCheckTimeMs >= FRAME_STALL_THRESHOLD_MS &&
            snapshot.processedFrames == snapshot.lastProcessedFrameCount
        ) {
            return StallReason.FRAME_STALL
        }

        return null
    }

    /** The exact failure strings the watchdog logs and publishes. */
    fun failureMessage(reason: StallReason, snapshot: HealthSnapshot): String {
        return when (reason) {
            StallReason.CAMERA_ERROR -> "Camera error: ${snapshot.cameraErrorMessage}"
            StallReason.SERVER_STOPPED -> "Streaming server stopped unexpectedly"
            StallReason.STREAM_STOPPED -> "Stream stopped unexpectedly"
            StallReason.FRAME_STALL ->
                "Frame delivery stalled (no frames for ${(snapshot.nowMs - snapshot.lastFrameCheckTimeMs) / 1000}s)"
        }
    }

    data class FrameTracking(
        val processedFrameCount: Int,
        val frameCheckTimeMs: Long,
    )

    /**
     * The frame-tracking update a healthy check leaves behind. With no
     * clients the tracking resets to now (avoiding false stalls once clients
     * reconnect); with clients it advances only when frames are flowing.
     * Null = keep the current tracking (a stall was detected).
     */
    fun updatedTracking(snapshot: HealthSnapshot): FrameTracking? {
        if (snapshot.clientCount <= 0) {
            // No clients — reset frame tracking to avoid false positives once clients reconnect
            return FrameTracking(snapshot.processedFrames, snapshot.nowMs)
        }
        // Update tracking if frames are flowing
        if (snapshot.processedFrames != snapshot.lastProcessedFrameCount) {
            return FrameTracking(snapshot.processedFrames, snapshot.nowMs)
        }
        return null
    }
}

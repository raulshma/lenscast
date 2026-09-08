package com.raulshma.lenscast.core

/**
 * Pure decisions for the Stream Watchdog: the recovery-tier ladder, the
 * stream-health evaluation over a [HealthSnapshot], the user-facing failure
 * strings, the frame-tracking bookkeeping a healthy check leaves behind, the
 * recovery verification windows plus the per-tier verification verdicts and
 * specs ([verificationSuccess], [verificationSpecFor]), and the monitor
 * loop's tick verdict ([nextTick]) with the backoff it schedules. The
 * watchdog's coroutine loop keeps the timing, the recovery calls, and the
 * state; every decision delegates here.
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

    /** Backoff before a recovery attempt starts here and doubles per failure. */
    private const val BASE_BACKOFF_MS = 2_000L
    /** The doubling backoff never waits longer than this. */
    private const val MAX_BACKOFF_MS = 60_000L

    /**
     * The post-recovery verdict, per tier — what the watchdog's verification
     * window is for:
     * - SOFT: frames advanced across the observation window, or no clients
     *   are connected to verify against (assume OK).
     * - MEDIUM: the stream is live again.
     * - HARD: the stream is live and the camera is Ready.
     * Every call site passes every argument explicitly; a tier that doesn't
     * consult an input ignores it.
     */
    fun verificationSuccess(
        tier: RecoveryTier,
        framesAdvanced: Boolean,
        clientCount: Int,
        isLive: Boolean,
        cameraReady: Boolean,
    ): Boolean = when (tier) {
        RecoveryTier.SOFT -> framesAdvanced || clientCount == 0
        RecoveryTier.MEDIUM -> isLive
        RecoveryTier.HARD -> isLive && cameraReady
    }

    /**
     * The per-tier verification window: how long to wait after the recovery
     * call before judging it, and whether the verdict reads the frame
     * counters across an observation window ([RECOVERY_VERIFICATION_WINDOW_MS]).
     */
    data class VerificationSpec(
        val delayMs: Long,
        val measureFrames: Boolean,
    )

    /** The window the watchdog observes before rendering [verificationSuccess]'s verdict. */
    fun verificationSpecFor(tier: RecoveryTier): VerificationSpec = when (tier) {
        RecoveryTier.SOFT -> VerificationSpec(delayMs = RECOVERY_VERIFICATION_DELAY_MS, measureFrames = true)
        RecoveryTier.MEDIUM -> VerificationSpec(delayMs = RECOVERY_VERIFICATION_DELAY_MS, measureFrames = false)
        RecoveryTier.HARD -> VerificationSpec(delayMs = HARD_VERIFICATION_DELAY_MS, measureFrames = false)
    }

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
        RTSP_UNHEALTHY,
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
        val rtspActive: Boolean = false,
        val rtspPlayingClients: Int = 0,
        val rtspHealthy: Boolean = true,
        val rtspAcceptedFrames: Long = 0L,
        val lastRtspAcceptedFrames: Long = 0L,
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

        // 5. RTSP-only health — catches an RTSP hang while MJPEG looks idle-healthy.
        if (snapshot.rtspActive && !snapshot.rtspHealthy) {
            return StallReason.RTSP_UNHEALTHY
        }
        if (snapshot.rtspActive && snapshot.rtspPlayingClients > 0 &&
            snapshot.nowMs - snapshot.lastFrameCheckTimeMs >= FRAME_STALL_THRESHOLD_MS &&
            snapshot.rtspAcceptedFrames == snapshot.lastRtspAcceptedFrames
        ) {
            return StallReason.RTSP_UNHEALTHY
        }

        return null
    }

    /** The exact failure strings the watchdog logs and publishes. */
    fun failureMessage(reason: StallReason, snapshot: HealthSnapshot): String {
        return when (reason) {
            StallReason.CAMERA_ERROR -> "Camera error: ${snapshot.cameraErrorMessage}"
            StallReason.SERVER_STOPPED -> "Streaming server stopped unexpectedly"
            StallReason.STREAM_STOPPED -> "Stream stopped unexpectedly"
            StallReason.RTSP_UNHEALTHY -> "RTSP output unhealthy (no frames to ${snapshot.rtspPlayingClients} players)"
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

    /** The watchdog's published lifecycle statuses — the verdicts name them, the loop publishes them. */
    enum class WatchdogStatus {
        IDLE,          // Not monitoring (streaming not active or watchdog disabled)
        MONITORING,    // Actively checking health
        RECOVERING,    // Recovery in progress
        FAILED,        // Max retries exhausted — operator intervention needed
        COOLDOWN,      // Waiting before next retry attempt
    }

    /** What the monitor loop should do with a tick's [TickDecision]. */
    enum class TickAction {
        CONTINUE, // Keep the loop going; publish the decision's status if it has one
        RESET,    // Zero the failure tracking — the stream is healthy or a recovery held
        RECOVER,  // Run the decision's tier's recovery after its backoff delay
        FAIL,     // Max retries exhausted — publish FAILED and stop the loop
    }

    /**
     * The monitor loop's next move: the [TickAction] to perform, the
     * [WatchdogStatus] to publish (null = publish nothing), the backoff to
     * wait before a RECOVER, the tier to attempt, and whether a RESET also
     * clears the exposed failure reason — true only for the healthy-tick
     * reset, so a recovery-success RESET leaves the last failure visible
     * until the next clean health check.
     */
    data class TickDecision(
        val action: TickAction,
        val status: WatchdogStatus?,
        val backoffMs: Long = 0L,
        val tier: RecoveryTier? = null,
        val clearsFailureReason: Boolean = false,
    )

    /** Pure exponential backoff with a 6-attempt doubling cap — the wait before a recovery attempt. */
    fun backoffMs(attempt: Int): Long {
        val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(6))
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * One verdict for the monitor loop's tick — the escalation choreography
     * in data-in/data-out form. In execution order:
     * - A health-check failure whose count (the loop increments before
     *   asking) exceeds `maxRetries` fails the watchdog; within budget it
     *   escalates — [tierFor] names the tier, [backoffMs] sizes the wait
     *   before the attempt, and the status goes RECOVERING.
     * - A healthy check with failures on the books resets to MONITORING and
     *   clears the exposed failure reason; an already-clean check publishes
     *   nothing.
     * - When a recovery attempt has run this tick (`recoverySucceeded` is
     *   non-null), its outcome is the verdict: success resets to MONITORING
     *   (the failure reason stays on the books), failure falls back to
     *   COOLDOWN — the other inputs are then ignored.
     */
    fun nextTick(
        failureReason: String?,
        consecutiveFailures: Int,
        maxRetries: Int,
        recoverySucceeded: Boolean? = null,
    ): TickDecision {
        // The recovery attempt's outcome, when one ran this tick, is the verdict.
        if (recoverySucceeded != null) {
            return if (recoverySucceeded) {
                TickDecision(TickAction.RESET, WatchdogStatus.MONITORING)
            } else {
                TickDecision(TickAction.CONTINUE, WatchdogStatus.COOLDOWN)
            }
        }

        // Healthy — reset only when failures are on the books.
        if (failureReason == null) {
            return if (consecutiveFailures > 0) {
                TickDecision(TickAction.RESET, WatchdogStatus.MONITORING, clearsFailureReason = true)
            } else {
                TickDecision(TickAction.CONTINUE, null)
            }
        }

        // Failure — max retries (counting this tick's) exhaust the watchdog.
        if (consecutiveFailures > maxRetries) {
            return TickDecision(TickAction.FAIL, WatchdogStatus.FAILED)
        }

        return TickDecision(
            action = TickAction.RECOVER,
            status = WatchdogStatus.RECOVERING,
            backoffMs = backoffMs(consecutiveFailures),
            tier = tierFor(consecutiveFailures),
        )
    }
}

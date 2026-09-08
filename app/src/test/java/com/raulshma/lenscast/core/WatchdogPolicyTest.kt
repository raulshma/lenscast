package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogPolicyTest {

    private fun snapshot(
        cameraError: Boolean = false,
        cameraErrorMessage: String? = null,
        wasStreamingActive: Boolean = true,
        serverRunning: Boolean = true,
        liveStreaming: Boolean = true,
        clientCount: Int = 1,
        processedFrames: Int = 100,
        lastProcessedFrameCount: Int = 90, // frames advancing by default → healthy
        lastFrameCheckTimeMs: Long = 0L,
        nowMs: Long = WatchdogPolicy.FRAME_STALL_THRESHOLD_MS,
    ) = WatchdogPolicy.HealthSnapshot(
        cameraError = cameraError,
        cameraErrorMessage = cameraErrorMessage,
        wasStreamingActive = wasStreamingActive,
        serverRunning = serverRunning,
        liveStreaming = liveStreaming,
        clientCount = clientCount,
        processedFrames = processedFrames,
        lastProcessedFrameCount = lastProcessedFrameCount,
        lastFrameCheckTimeMs = lastFrameCheckTimeMs,
        nowMs = nowMs,
    )

    // ── tierFor escalation ladder ──

    @Test
    fun `tierFor keeps the first two failures soft`() {
        assertEquals(WatchdogPolicy.RecoveryTier.SOFT, WatchdogPolicy.tierFor(1))
        assertEquals(WatchdogPolicy.RecoveryTier.SOFT, WatchdogPolicy.tierFor(2))
    }

    @Test
    fun `tierFor escalates the next two failures to medium`() {
        assertEquals(WatchdogPolicy.RecoveryTier.MEDIUM, WatchdogPolicy.tierFor(3))
        assertEquals(WatchdogPolicy.RecoveryTier.MEDIUM, WatchdogPolicy.tierFor(4))
    }

    @Test
    fun `tierFor goes hard from the fifth failure on`() {
        assertEquals(WatchdogPolicy.RecoveryTier.HARD, WatchdogPolicy.tierFor(5))
        assertEquals(WatchdogPolicy.RecoveryTier.HARD, WatchdogPolicy.tierFor(6))
        assertEquals(WatchdogPolicy.RecoveryTier.HARD, WatchdogPolicy.tierFor(50))
    }

    // ── evaluate ──

    @Test
    fun `a healthy stream yields no stall reason`() {
        assertNull(WatchdogPolicy.evaluate(snapshot()))
    }

    @Test
    fun `camera error is the first branch and wins over everything`() {
        val reason = WatchdogPolicy.evaluate(
            snapshot(
                cameraError = true,
                cameraErrorMessage = "lens disconnected",
                serverRunning = false,
                liveStreaming = false,
                processedFrames = 100,
                lastProcessedFrameCount = 100,
                nowMs = 600_000,
            )
        )
        assertEquals(WatchdogPolicy.StallReason.CAMERA_ERROR, reason)
    }

    @Test
    fun `server stopped while streaming was active is detected`() {
        assertEquals(
            WatchdogPolicy.StallReason.SERVER_STOPPED,
            WatchdogPolicy.evaluate(snapshot(serverRunning = false))
        )
    }

    @Test
    fun `server stopped takes precedence over a stopped live stream`() {
        assertEquals(
            WatchdogPolicy.StallReason.SERVER_STOPPED,
            WatchdogPolicy.evaluate(snapshot(serverRunning = false, liveStreaming = false))
        )
    }

    @Test
    fun `server stopped is ignored when the stream was never active`() {
        assertNull(WatchdogPolicy.evaluate(snapshot(serverRunning = false, wasStreamingActive = false)))
    }

    @Test
    fun `live stream lost is detected only when streaming was active`() {
        assertEquals(
            WatchdogPolicy.StallReason.STREAM_STOPPED,
            WatchdogPolicy.evaluate(snapshot(liveStreaming = false))
        )
        assertNull(
            WatchdogPolicy.evaluate(snapshot(liveStreaming = false, wasStreamingActive = false))
        )
    }

    @Test
    fun `frame stall fires only with clients frozen frames and past the threshold`() {
        val stalled = snapshot(
            processedFrames = 100,
            lastProcessedFrameCount = 100,
            nowMs = WatchdogPolicy.FRAME_STALL_THRESHOLD_MS,
        )
        assertEquals(WatchdogPolicy.StallReason.FRAME_STALL, WatchdogPolicy.evaluate(stalled))
    }

    @Test
    fun `frame stall boundary is exclusive below the threshold`() {
        assertNull(
            WatchdogPolicy.evaluate(
                snapshot(
                    processedFrames = 100,
                    lastProcessedFrameCount = 100,
                    nowMs = WatchdogPolicy.FRAME_STALL_THRESHOLD_MS - 1,
                )
            )
        )
    }

    @Test
    fun `advancing frames keep the stream healthy past the threshold`() {
        assertNull(
            WatchdogPolicy.evaluate(
                snapshot(
                    processedFrames = 200,
                    lastProcessedFrameCount = 100,
                    nowMs = 10 * WatchdogPolicy.FRAME_STALL_THRESHOLD_MS,
                )
            )
        )
    }

    @Test
    fun `no clients means no frame stall even with frozen frames`() {
        assertNull(
            WatchdogPolicy.evaluate(
                snapshot(
                    clientCount = 0,
                    processedFrames = 100,
                    lastProcessedFrameCount = 100,
                    nowMs = 10 * WatchdogPolicy.FRAME_STALL_THRESHOLD_MS,
                )
            )
        )
    }

    // ── failureMessage ──

    @Test
    fun `failure messages are the exact user-facing strings`() {
        assertEquals(
            "Camera error: lens disconnected",
            WatchdogPolicy.failureMessage(WatchdogPolicy.StallReason.CAMERA_ERROR, snapshot(cameraErrorMessage = "lens disconnected"))
        )
        assertEquals(
            "Camera error: null",
            WatchdogPolicy.failureMessage(WatchdogPolicy.StallReason.CAMERA_ERROR, snapshot())
        )
        assertEquals(
            "Streaming server stopped unexpectedly",
            WatchdogPolicy.failureMessage(WatchdogPolicy.StallReason.SERVER_STOPPED, snapshot())
        )
        assertEquals(
            "Stream stopped unexpectedly",
            WatchdogPolicy.failureMessage(WatchdogPolicy.StallReason.STREAM_STOPPED, snapshot())
        )
        assertEquals(
            "Frame delivery stalled (no frames for 15s)",
            WatchdogPolicy.failureMessage(
                WatchdogPolicy.StallReason.FRAME_STALL,
                snapshot(lastFrameCheckTimeMs = 0, nowMs = WatchdogPolicy.FRAME_STALL_THRESHOLD_MS)
            )
        )
        assertEquals(
            "Frame delivery stalled (no frames for 45s)",
            WatchdogPolicy.failureMessage(
                WatchdogPolicy.StallReason.FRAME_STALL,
                snapshot(lastFrameCheckTimeMs = 1_000, nowMs = 46_000)
            )
        )
    }

    // ── updatedTracking ──

    @Test
    fun `tracking resets to now when no clients are connected`() {
        val tracking = WatchdogPolicy.updatedTracking(
            snapshot(clientCount = 0, processedFrames = 55, nowMs = 9_000)
        )!!
        assertEquals(55, tracking.processedFrameCount)
        assertEquals(9_000, tracking.frameCheckTimeMs)
    }

    @Test
    fun `tracking advances when frames are flowing with clients`() {
        val tracking = WatchdogPolicy.updatedTracking(
            snapshot(clientCount = 2, processedFrames = 120, lastProcessedFrameCount = 100, nowMs = 7_000)
        )!!
        assertEquals(120, tracking.processedFrameCount)
        assertEquals(7_000, tracking.frameCheckTimeMs)
    }

    @Test
    fun `tracking is kept when clients are connected but frames are frozen`() {
        assertNull(
            WatchdogPolicy.updatedTracking(
                snapshot(clientCount = 1, processedFrames = 100, lastProcessedFrameCount = 100)
            )
        )
    }

    @Test
    fun `the healthy evaluate plus tracking pair mirrors the watchdog loop`() {
        // Frames flowing with clients: healthy, and the tracking advances.
        val healthy = snapshot(processedFrames = 130, lastProcessedFrameCount = 100)
        assertNull(WatchdogPolicy.evaluate(healthy))
        val tracking = WatchdogPolicy.updatedTracking(healthy)!!
        assertTrue(tracking.processedFrameCount == 130 && tracking.frameCheckTimeMs == WatchdogPolicy.FRAME_STALL_THRESHOLD_MS)

        // A stalled stream evaluates to FRAME_STALL and keeps the old tracking.
        val stalled = snapshot(processedFrames = 130, lastProcessedFrameCount = 130)
        assertEquals(WatchdogPolicy.StallReason.FRAME_STALL, WatchdogPolicy.evaluate(stalled))
        assertNull(WatchdogPolicy.updatedTracking(stalled))
    }

    // ── verificationSuccess ──

    @Test
    fun `soft recovery succeeds when frames advanced across the window`() {
        assertTrue(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.SOFT,
                framesAdvanced = true,
                clientCount = 1,
                isLive = false,
                cameraReady = false,
            )
        )
    }

    @Test
    fun `soft recovery assumes success when no clients are connected to verify against`() {
        assertTrue(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.SOFT,
                framesAdvanced = false,
                clientCount = 0,
                isLive = false,
                cameraReady = false,
            )
        )
        // Advancing frames succeed with or without an audience.
        assertTrue(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.SOFT,
                framesAdvanced = true,
                clientCount = 0,
                isLive = false,
                cameraReady = false,
            )
        )
    }

    @Test
    fun `soft recovery fails when frames are stalled with clients connected`() {
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.SOFT,
                framesAdvanced = false,
                clientCount = 3,
                isLive = false,
                cameraReady = false,
            )
        )
    }

    @Test
    fun `medium recovery succeeds only when the stream is live again`() {
        assertTrue(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.MEDIUM,
                framesAdvanced = false,
                clientCount = 1,
                isLive = true,
                cameraReady = false,
            )
        )
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.MEDIUM,
                framesAdvanced = false,
                clientCount = 1,
                isLive = false,
                cameraReady = false,
            )
        )
    }

    @Test
    fun `hard recovery needs the stream live and the camera ready`() {
        assertTrue(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.HARD,
                framesAdvanced = false,
                clientCount = 1,
                isLive = true,
                cameraReady = true,
            )
        )
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.HARD,
                framesAdvanced = false,
                clientCount = 1,
                isLive = true,
                cameraReady = false,
            )
        )
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.HARD,
                framesAdvanced = false,
                clientCount = 1,
                isLive = false,
                cameraReady = true,
            )
        )
    }

    @Test
    fun `unconsulted inputs never flip another tier's verdict`() {
        // Clients alone pass neither MEDIUM nor HARD.
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.MEDIUM,
                framesAdvanced = false,
                clientCount = 5,
                isLive = false,
                cameraReady = false,
            )
        )
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.HARD,
                framesAdvanced = false,
                clientCount = 5,
                isLive = false,
                cameraReady = false,
            )
        )
        // SOFT stays conservative without an observed frame advance: stalled
        // frames with clients connected is not a recovery.
        assertFalse(
            WatchdogPolicy.verificationSuccess(
                WatchdogPolicy.RecoveryTier.SOFT,
                framesAdvanced = false,
                clientCount = 1,
                isLive = true,
                cameraReady = false,
            )
        )
    }

    // ── verificationSpecFor ──

    @Test
    fun `soft verification waits the shared delay and measures frames across the window`() {
        val spec = WatchdogPolicy.verificationSpecFor(WatchdogPolicy.RecoveryTier.SOFT)
        assertEquals(WatchdogPolicy.RECOVERY_VERIFICATION_DELAY_MS, spec.delayMs)
        assertTrue(spec.measureFrames)
    }

    @Test
    fun `medium verification waits the shared delay without measuring frames`() {
        val spec = WatchdogPolicy.verificationSpecFor(WatchdogPolicy.RecoveryTier.MEDIUM)
        assertEquals(WatchdogPolicy.RECOVERY_VERIFICATION_DELAY_MS, spec.delayMs)
        assertFalse(spec.measureFrames)
    }

    @Test
    fun `hard verification waits the doubled delay without measuring frames`() {
        val spec = WatchdogPolicy.verificationSpecFor(WatchdogPolicy.RecoveryTier.HARD)
        assertEquals(WatchdogPolicy.HARD_VERIFICATION_DELAY_MS, spec.delayMs)
        assertEquals(2 * WatchdogPolicy.RECOVERY_VERIFICATION_DELAY_MS, spec.delayMs)
        assertFalse(spec.measureFrames)
    }

    // ── nextTick ──

    @Test
    fun `a clean healthy tick keeps the loop going without publishing`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = null,
            consecutiveFailures = 0,
            maxRetries = 5,
        )
        assertEquals(WatchdogPolicy.TickAction.CONTINUE, tick.action)
        assertNull(tick.status)
        assertNull(tick.tier)
        assertEquals(0L, tick.backoffMs)
    }

    @Test
    fun `a healthy check with failures on the books resets to monitoring`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = null,
            consecutiveFailures = 3,
            maxRetries = 5,
        )
        assertEquals(WatchdogPolicy.TickAction.RESET, tick.action)
        assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, tick.status)
        // Only the healthy-tick reset clears the exposed failure reason.
        assertTrue(tick.clearsFailureReason)
    }

    @Test
    fun `the first failure recovers soft in recovering state after one backoff step`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = "Stream stopped unexpectedly",
            consecutiveFailures = 1,
            maxRetries = 5,
        )
        assertEquals(WatchdogPolicy.TickAction.RECOVER, tick.action)
        assertEquals(WatchdogPolicy.WatchdogStatus.RECOVERING, tick.status)
        assertEquals(WatchdogPolicy.RecoveryTier.SOFT, tick.tier)
        assertEquals(2_000L, tick.backoffMs)
    }

    @Test
    fun `backoff grows per the exponential policy while the ladder escalates`() {
        val expectedTiers = listOf(
            WatchdogPolicy.RecoveryTier.SOFT,
            WatchdogPolicy.RecoveryTier.SOFT,
            WatchdogPolicy.RecoveryTier.MEDIUM,
            WatchdogPolicy.RecoveryTier.MEDIUM,
            WatchdogPolicy.RecoveryTier.HARD,
        )
        val expectedBackoffMs = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        expectedTiers.forEachIndexed { index, tier ->
            val tick = WatchdogPolicy.nextTick(
                failureReason = "Frame delivery stalled (no frames for 15s)",
                consecutiveFailures = index + 1,
                maxRetries = 5,
            )
            assertEquals(WatchdogPolicy.TickAction.RECOVER, tick.action)
            assertEquals(tier, tick.tier)
            assertEquals(expectedBackoffMs[index], tick.backoffMs)
        }
    }

    @Test
    fun `the failure at max retries still recovers hard`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = "Streaming server stopped unexpectedly",
            consecutiveFailures = 5,
            maxRetries = 5,
        )
        assertEquals(WatchdogPolicy.TickAction.RECOVER, tick.action)
        assertEquals(WatchdogPolicy.RecoveryTier.HARD, tick.tier)
        assertEquals(32_000L, tick.backoffMs)
    }

    @Test
    fun `the failure past max retries fails the watchdog with nothing left to run`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = "Streaming server stopped unexpectedly",
            consecutiveFailures = 6,
            maxRetries = 5,
        )
        assertEquals(WatchdogPolicy.TickAction.FAIL, tick.action)
        assertEquals(WatchdogPolicy.WatchdogStatus.FAILED, tick.status)
        assertNull(tick.tier)
        assertEquals(0L, tick.backoffMs)
    }

    @Test
    fun `a held recovery resets the watchdog to monitoring without clearing the failure reason`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = "Frame delivery stalled (no frames for 15s)",
            consecutiveFailures = 2,
            maxRetries = 5,
            recoverySucceeded = true,
        )
        assertEquals(WatchdogPolicy.TickAction.RESET, tick.action)
        assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, tick.status)
        // The last failure stays exposed until the next clean health check.
        assertFalse(tick.clearsFailureReason)
    }

    @Test
    fun `a failed recovery falls back to cooldown and keeps the failure count`() {
        val tick = WatchdogPolicy.nextTick(
            failureReason = "Frame delivery stalled (no frames for 15s)",
            consecutiveFailures = 2,
            maxRetries = 5,
            recoverySucceeded = false,
        )
        assertEquals(WatchdogPolicy.TickAction.CONTINUE, tick.action)
        assertEquals(WatchdogPolicy.WatchdogStatus.COOLDOWN, tick.status)
    }
}

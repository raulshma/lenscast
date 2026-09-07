package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
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
}

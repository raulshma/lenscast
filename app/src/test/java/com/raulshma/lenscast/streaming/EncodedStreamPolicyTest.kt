package com.raulshma.lenscast.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedStreamPolicyTest {

    // ── shouldRun: the encode pipeline's start/stop verdict over sink activity ──

    @Test
    fun `an active rtsp output alone runs the pipeline`() {
        assertTrue(
            EncodedStreamPolicy.shouldRun(
                EncodedStreamPolicy.Inputs(
                    webActive = false,
                    rtspActive = true,
                    hlsRequested = false,
                    wsVideoClients = 0,
                )
            )
        )
    }

    @Test
    fun `a requested hls ring alone runs the pipeline`() {
        assertTrue(
            EncodedStreamPolicy.shouldRun(
                EncodedStreamPolicy.Inputs(
                    webActive = false,
                    rtspActive = false,
                    hlsRequested = true,
                    wsVideoClients = 0,
                )
            )
        )
    }

    @Test
    fun `ws video clients on an active web output run the pipeline`() {
        assertTrue(
            EncodedStreamPolicy.shouldRun(
                EncodedStreamPolicy.Inputs(
                    webActive = true,
                    rtspActive = false,
                    hlsRequested = false,
                    wsVideoClients = 1,
                )
            )
        )
    }

    @Test
    fun `ws video clients without an active web output do not run it`() {
        assertFalse(
            EncodedStreamPolicy.shouldRun(
                EncodedStreamPolicy.Inputs(
                    webActive = false,
                    rtspActive = false,
                    hlsRequested = false,
                    wsVideoClients = 3,
                )
            )
        )
    }

    @Test
    fun `an active web output with zero ws video clients does not run it on that account`() {
        assertFalse(
            EncodedStreamPolicy.shouldRun(
                EncodedStreamPolicy.Inputs(
                    webActive = true,
                    rtspActive = false,
                    hlsRequested = false,
                    wsVideoClients = 0,
                )
            )
        )
    }

    @Test
    fun `no sink activity means the pipeline stays stopped`() {
        assertFalse(
            EncodedStreamPolicy.shouldRun(
                EncodedStreamPolicy.Inputs(
                    webActive = false,
                    rtspActive = false,
                    hlsRequested = false,
                    wsVideoClients = 0,
                )
            )
        )
    }

    // ── shouldAttachAudio: the hub's AAC (re)attach ladder ──

    @Test
    fun `audio attaches when the capture runs and no healthy encoder exists`() {
        assertTrue(
            EncodedStreamPolicy.shouldAttachAudio(
                aacRunning = false,
                captureRunning = true,
                formatMatches = false,
            )
        )
    }

    @Test
    fun `a healthy format-matched encoder stays attached`() {
        assertFalse(
            EncodedStreamPolicy.shouldAttachAudio(
                aacRunning = true,
                captureRunning = true,
                formatMatches = true,
            )
        )
    }

    @Test
    fun `a running encoder with drifted audio config reattaches`() {
        assertTrue(
            EncodedStreamPolicy.shouldAttachAudio(
                aacRunning = true,
                captureRunning = true,
                formatMatches = false,
            )
        )
    }

    @Test
    fun `a stopped capture never attaches`() {
        assertFalse(
            EncodedStreamPolicy.shouldAttachAudio(
                aacRunning = false,
                captureRunning = false,
                formatMatches = false,
            )
        )
    }
}

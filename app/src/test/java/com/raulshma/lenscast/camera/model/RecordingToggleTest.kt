package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingToggleTest {

    // ── the stop verdict ──

    @Test
    fun `a live recording stops`() {
        val decision = RecordingToggle.decide(
            currentState = RecordingState.Recording(startedAtMs = 0L, config = null),
            audioWanted = true,
        )

        assertEquals(RecordingToggle.ToggleDecision.Stop, decision)
    }

    @Test
    fun `a still-finalizing recording stops too`() {
        val decision = RecordingToggle.decide(
            currentState = RecordingState.Recording(startedAtMs = 0L, config = null, finalizing = true),
            audioWanted = true,
        )

        assertEquals(RecordingToggle.ToggleDecision.Stop, decision)
    }

    @Test
    fun `a scheduled start stops`() {
        val decision = RecordingToggle.decide(
            currentState = RecordingState.Scheduled(startAtMs = 0L, config = RecordingConfig()),
            audioWanted = true,
        )

        assertEquals(RecordingToggle.ToggleDecision.Stop, decision)
    }

    // ── the start verdict + config ──

    @Test
    fun `an idle state starts`() {
        val decision = RecordingToggle.decide(RecordingState.Idle, audioWanted = true)

        assertTrue(decision is RecordingToggle.ToggleDecision.Start)
    }

    @Test
    fun `the start config carries the audio setting`() {
        val withAudio = RecordingToggle.decide(RecordingState.Idle, audioWanted = true)
            as RecordingToggle.ToggleDecision.Start
        val videoOnly = RecordingToggle.decide(RecordingState.Idle, audioWanted = false)
            as RecordingToggle.ToggleDecision.Start

        assertTrue(withAudio.config.includeAudio)
        assertFalse(videoOnly.config.includeAudio)
    }

    @Test
    fun `a fresh start keeps the other config defaults`() {
        val decision = RecordingToggle.decide(RecordingState.Idle, audioWanted = true)
            as RecordingToggle.ToggleDecision.Start

        assertEquals(RecordingConfig(includeAudio = true), decision.config)
    }

    // ── the pre-start hook ──

    @Test
    fun `the mic consult runs on the start path only`() {
        var consults = 0
        val consult: () -> Unit = { consults++ }

        RecordingToggle.decide(RecordingState.Idle, audioWanted = true, onBeforeStart = consult)
        assertEquals(1, consults)

        RecordingToggle.decide(
            currentState = RecordingState.Recording(startedAtMs = 0L, config = null),
            audioWanted = true,
            onBeforeStart = consult,
        )
        // A stop never refreshes permissions or warns.
        assertEquals(1, consults)
    }

    @Test
    fun `the pre-start hook defaults to a no-op`() {
        val decision = RecordingToggle.decide(RecordingState.Idle, audioWanted = false)

        assertTrue(decision is RecordingToggle.ToggleDecision.Start)
    }

    // ── sticky camera state ──

    @Test
    fun `an incoming idle is dropped in favor of the current state`() {
        assertEquals(CameraState.Ready, stickyCameraState(CameraState.Ready, CameraState.Idle))
        assertEquals(
            CameraState.Error("boom"),
            stickyCameraState(CameraState.Error("boom"), CameraState.Idle),
        )
    }

    @Test
    fun `any other incoming state replaces the current one`() {
        assertEquals(
            CameraState.Initializing,
            stickyCameraState(CameraState.Ready, CameraState.Initializing),
        )
    }

    @Test
    fun `idle over idle stays idle`() {
        assertEquals(CameraState.Idle, stickyCameraState(CameraState.Idle, CameraState.Idle))
    }

    // ── retry budget ──

    @Test
    fun `the retry budget allows exactly max retries`() {
        assertTrue(CameraInitRetry.shouldRetry(0))
        assertTrue(CameraInitRetry.shouldRetry(1))
        assertTrue(CameraInitRetry.shouldRetry(2))
        assertFalse(CameraInitRetry.shouldRetry(3))
        assertEquals(3, CameraInitRetry.MAX_RETRIES)
    }
}

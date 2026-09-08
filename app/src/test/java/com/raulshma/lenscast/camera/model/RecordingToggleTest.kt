package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
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
            startConfig = RecordingConfig(),
        )

        assertEquals(RecordingToggle.ToggleDecision.Stop, decision)
    }

    @Test
    fun `a still-finalizing recording stops too`() {
        val decision = RecordingToggle.decide(
            currentState = RecordingState.Recording(startedAtMs = 0L, config = null, finalizing = true),
            startConfig = RecordingConfig(),
        )

        assertEquals(RecordingToggle.ToggleDecision.Stop, decision)
    }

    @Test
    fun `a scheduled start stops`() {
        val decision = RecordingToggle.decide(
            currentState = RecordingState.Scheduled(startAtMs = 0L, config = RecordingConfig()),
            startConfig = RecordingConfig(),
        )

        assertEquals(RecordingToggle.ToggleDecision.Stop, decision)
    }

    // ── the start verdict + config ──

    @Test
    fun `an idle state starts`() {
        val decision = RecordingToggle.decide(
            RecordingState.Idle,
            startConfig = RecordingConfig(includeAudio = true),
        )

        assertTrue(decision is RecordingToggle.ToggleDecision.Start)
    }

    @Test
    fun `the same decide answers a default config and a full draft config`() {
        val defaultStart = RecordingToggle.decide(RecordingState.Idle, startConfig = RecordingConfig())
            as RecordingToggle.ToggleDecision.Start
        val draft = RecordingConfig(
            durationSeconds = 600L,
            repeatIntervalSeconds = 30L,
            quality = RecordingQuality.MEDIUM,
            includeAudio = false,
            startTimeMs = 1234L,
        )
        val draftStart = RecordingToggle.decide(RecordingState.Idle, startConfig = draft)
            as RecordingToggle.ToggleDecision.Start

        // The camera screen's default and the capture screen's full draft
        // ride the same verdict: both start, each with its own config.
        assertEquals(RecordingConfig(), defaultStart.config)
        assertEquals(draft, draftStart.config)
    }

    @Test
    fun `a start carries the caller's config verbatim`() {
        val withAudio = RecordingToggle.decide(
            RecordingState.Idle,
            startConfig = RecordingConfig(includeAudio = true),
        ) as RecordingToggle.ToggleDecision.Start
        val videoOnly = RecordingToggle.decide(
            RecordingState.Idle,
            startConfig = RecordingConfig(includeAudio = false),
        ) as RecordingToggle.ToggleDecision.Start

        assertTrue(withAudio.config.includeAudio)
        assertFalse(videoOnly.config.includeAudio)
    }

    // ── the pre-start hook ──

    @Test
    fun `the mic consult runs on the start path only`() {
        var consults = 0
        val consult: (RecordingConfig) -> RecordingConfig = { consults++; it }

        RecordingToggle.decide(RecordingState.Idle, startConfig = RecordingConfig(), onBeforeStart = consult)
        assertEquals(1, consults)

        RecordingToggle.decide(
            currentState = RecordingState.Recording(startedAtMs = 0L, config = null),
            startConfig = RecordingConfig(),
            onBeforeStart = consult,
        )
        // A stop never consults the hook — never refreshes permissions or warns.
        assertEquals(1, consults)
    }

    @Test
    fun `the pre-start hook decides the config the start carries`() {
        val decision = RecordingToggle.decide(
            RecordingState.Idle,
            startConfig = RecordingConfig(includeAudio = true),
            onBeforeStart = { it.copy(includeAudio = false) },
        ) as RecordingToggle.ToggleDecision.Start

        assertFalse(decision.config.includeAudio)
    }

    @Test
    fun `the pre-start hook defaults to a no-op`() {
        val decision = RecordingToggle.decide(
            RecordingState.Idle,
            startConfig = RecordingConfig(includeAudio = false),
        )

        assertEquals(
            RecordingToggle.ToggleDecision.Start(RecordingConfig(includeAudio = false)),
            decision,
        )
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

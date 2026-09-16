package com.raulshma.lenscast.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mic-arbitration rules: who holds the shared capture, when sound
 * detection keeps it up headless, and when the WHIP push may still carry
 * audio. These verdicts are the fix for "sound detection is dead with no
 * viewers" — the regression this suite pins is that an armed detector is
 * itself a mic consumer.
 */
class MicArbitrationPolicyTest {

    private fun inputs(
        webStreamingActive: Boolean = false,
        webStreamingEnabled: Boolean = true,
        streamAudioEnabled: Boolean = true,
        recordingAudioCaptureActive: Boolean = false,
        ecoIdleActive: Boolean = false,
        soundDetectionEnabled: Boolean = false,
    ) = MicArbitrationPolicy.Inputs(
        webStreamingActive = webStreamingActive,
        webStreamingEnabled = webStreamingEnabled,
        streamAudioEnabled = streamAudioEnabled,
        recordingAudioCaptureActive = recordingAudioCaptureActive,
        ecoIdleActive = ecoIdleActive,
        soundDetectionEnabled = soundDetectionEnabled,
    )

    // ── viewer-facing web audio (the historical verdict, unchanged) ──

    @Test
    fun `web audio wanted only while web streaming is active and enabled`() {
        assertTrue(MicArbitrationPolicy.webAudioWanted(inputs(webStreamingActive = true)))
        assertFalse(MicArbitrationPolicy.webAudioWanted(inputs(webStreamingActive = false)))
        assertFalse(MicArbitrationPolicy.webAudioWanted(inputs(webStreamingActive = true, webStreamingEnabled = false)))
        assertFalse(MicArbitrationPolicy.webAudioWanted(inputs(webStreamingActive = true, streamAudioEnabled = false)))
    }

    @Test
    fun `web audio not wanted while recording owns the mic or eco idle dropped`() {
        assertFalse(MicArbitrationPolicy.webAudioWanted(inputs(webStreamingActive = true, recordingAudioCaptureActive = true)))
        assertFalse(MicArbitrationPolicy.webAudioWanted(inputs(webStreamingActive = true, ecoIdleActive = true)))
    }

    // ── sound detection's surveillance hold ──

    @Test
    fun `armed sound detection wants the mic with no stream, no viewers`() {
        assertTrue(MicArbitrationPolicy.detectionWantsMic(inputs(soundDetectionEnabled = true)))
    }

    @Test
    fun `sound detection keeps the mic through eco idle`() {
        assertTrue(MicArbitrationPolicy.detectionWantsMic(inputs(soundDetectionEnabled = true, ecoIdleActive = true)))
    }

    @Test
    fun `recording capture wins over sound detection`() {
        assertFalse(
            MicArbitrationPolicy.detectionWantsMic(
                inputs(soundDetectionEnabled = true, recordingAudioCaptureActive = true),
            ),
        )
    }

    @Test
    fun `disarmed sound detection wants no mic`() {
        assertFalse(MicArbitrationPolicy.detectionWantsMic(inputs(soundDetectionEnabled = false)))
    }

    // ── WHIP push arbitration ──

    @Test
    fun `whip audio blocked while shared capture serves web audio`() {
        val i = inputs(webStreamingActive = true)
        assertFalse(MicArbitrationPolicy.whipAudioAllowed(i, sharedCaptureRunning = true))
    }

    @Test
    fun `whip audio allowed while capture runs purely for sound detection`() {
        val i = inputs(soundDetectionEnabled = true)
        assertTrue(MicArbitrationPolicy.whipAudioAllowed(i, sharedCaptureRunning = true))
    }

    @Test
    fun `whip audio allowed with no capture running`() {
        assertTrue(MicArbitrationPolicy.whipAudioAllowed(inputs(), sharedCaptureRunning = false))
    }

    @Test
    fun `whip audio blocked while recording owns the mic`() {
        val i = inputs(recordingAudioCaptureActive = true, soundDetectionEnabled = false)
        assertFalse(MicArbitrationPolicy.whipAudioAllowed(i, sharedCaptureRunning = false))
    }

    @Test
    fun `whip audio blocked during eco idle`() {
        val i = inputs(ecoIdleActive = true, soundDetectionEnabled = true)
        assertFalse(MicArbitrationPolicy.whipAudioAllowed(i, sharedCaptureRunning = true))
    }
}

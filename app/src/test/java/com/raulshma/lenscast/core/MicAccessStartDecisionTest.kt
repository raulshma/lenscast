package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicAccessStartDecisionTest {

    @Test
    fun `enabled and granted proceeds`() {
        val decision = MicAccess.startDecision(
            featureEnabled = true,
            granted = true,
            featureLabel = "Streaming video",
        )
        assertTrue(decision is MicStartDecision.Proceed)
    }

    @Test
    fun `enabled but ungranted degrades with the shared warning`() {
        val decision = MicAccess.startDecision(
            featureEnabled = true,
            granted = false,
            featureLabel = "Streaming video",
        )
        assertEquals(
            MicStartDecision.Degrade("Microphone permission not granted. Streaming video without audio."),
            decision,
        )
    }

    @Test
    fun `recording label carries its own feature text`() {
        val decision = MicAccess.startDecision(
            featureEnabled = true,
            granted = false,
            featureLabel = "Recording video",
        )
        assertEquals(
            MicStartDecision.Degrade("Microphone permission not granted. Recording video without audio."),
            decision,
        )
    }

    @Test
    fun `disabled feature proceeds even without the permission`() {
        assertTrue(
            MicAccess.startDecision(false, granted = false, featureLabel = "Recording video")
                is MicStartDecision.Proceed
        )
        assertTrue(
            MicAccess.startDecision(false, granted = true, featureLabel = "Recording video")
                is MicStartDecision.Proceed
        )
    }

    @Test
    fun `auto request fires once, only when ready and ungranted`() {
        assertTrue(MicAccess.shouldAutoRequest(featureReady = true, granted = false, alreadyRequested = false))
        assertFalse(MicAccess.shouldAutoRequest(featureReady = true, granted = false, alreadyRequested = true))
        assertFalse(MicAccess.shouldAutoRequest(featureReady = true, granted = true, alreadyRequested = false))
        assertFalse(MicAccess.shouldAutoRequest(featureReady = false, granted = false, alreadyRequested = false))
    }

    @Test
    fun `degraded message keeps its exact phrasing`() {
        assertEquals(
            "Microphone permission not granted. Streaming video without audio.",
            MicAccess.degradedMessage("Streaming video"),
        )
    }
}

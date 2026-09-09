package com.raulshma.lenscast.capture

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterrence automation verdicts: flag gating, cooldown suppression, and
 * the siren-duration clamp.
 */
class DeterrenceAutomationPolicyTest {

    // ── Flag gating ──

    @Test
    fun `both flags off is the no-op verdict regardless of the cooldown`() {
        val verdict = DeterrenceAutomationPolicy.decide(
            autoSiren = false,
            autoTorch = false,
            sirenDurationSeconds = 10,
            cooldownRemainingMs = 0L,
        )
        assertTrue(verdict.isNoop)
        assertFalse(verdict.startSiren)
        assertFalse(verdict.triggerTorch)
    }

    @Test
    fun `a running cooldown suppresses every action`() {
        val verdict = DeterrenceAutomationPolicy.decide(
            autoSiren = true,
            autoTorch = true,
            sirenDurationSeconds = 10,
            cooldownRemainingMs = 1L,
        )
        assertTrue(verdict.isNoop)
    }

    @Test
    fun `an expired cooldown allows the enabled actions`() {
        val verdict = DeterrenceAutomationPolicy.decide(
            autoSiren = true,
            autoTorch = true,
            sirenDurationSeconds = 10,
            cooldownRemainingMs = 0L,
        )
        assertTrue(verdict.startSiren)
        assertTrue(verdict.triggerTorch)
        assertFalse(verdict.isNoop)
        assertEquals(10_000L, verdict.sirenDurationMs)
    }

    @Test
    fun `each flag acts independently`() {
        val sirenOnly = DeterrenceAutomationPolicy.decide(
            autoSiren = true, autoTorch = false,
            sirenDurationSeconds = 10, cooldownRemainingMs = 0L,
        )
        assertTrue(sirenOnly.startSiren)
        assertFalse(sirenOnly.triggerTorch)

        val torchOnly = DeterrenceAutomationPolicy.decide(
            autoSiren = false, autoTorch = true,
            sirenDurationSeconds = 10, cooldownRemainingMs = 0L,
        )
        assertFalse(torchOnly.startSiren)
        assertTrue(torchOnly.triggerTorch)
    }

    // ── Siren duration clamp ──

    @Test
    fun `siren duration clamps to the StreamDefaults bounds`() {
        val low = DeterrenceAutomationPolicy.decide(
            autoSiren = true, autoTorch = false,
            sirenDurationSeconds = 0, cooldownRemainingMs = 0L,
        )
        assertEquals(StreamDefaults.SIREN_DURATION_MIN_SECONDS * 1_000L, low.sirenDurationMs)

        val high = DeterrenceAutomationPolicy.decide(
            autoSiren = true, autoTorch = false,
            sirenDurationSeconds = 999, cooldownRemainingMs = 0L,
        )
        assertEquals(StreamDefaults.SIREN_DURATION_MAX_SECONDS * 1_000L, high.sirenDurationMs)

        val inside = DeterrenceAutomationPolicy.decide(
            autoSiren = true, autoTorch = false,
            sirenDurationSeconds = 25, cooldownRemainingMs = 0L,
        )
        assertEquals(25_000L, inside.sirenDurationMs)
    }
}

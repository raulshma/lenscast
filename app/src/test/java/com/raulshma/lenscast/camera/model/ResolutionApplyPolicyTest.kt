package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.camera.model.ResolutionApplyPolicy.ResolutionDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionApplyPolicyTest {

    private fun decision(
        demandActive: Boolean,
        exclusiveActive: Boolean,
        resolutionChanged: Boolean,
    ) = ResolutionApplyPolicy.decide(demandActive, exclusiveActive, resolutionChanged)

    // ── the full decision matrix ──

    @Test
    fun `a free camera rebinds now with the resolution change`() {
        assertEquals(
            ResolutionDecision.RebindNow(withResolutionChange = true),
            decision(demandActive = true, exclusiveActive = false, resolutionChanged = true),
        )
    }

    @Test
    fun `a free camera rebinds now restoring the current resolution`() {
        assertEquals(
            ResolutionDecision.RebindNow(withResolutionChange = false),
            decision(demandActive = true, exclusiveActive = false, resolutionChanged = false),
        )
    }

    @Test
    fun `no active demand defers the change`() {
        assertEquals(
            ResolutionDecision.Defer,
            decision(demandActive = false, exclusiveActive = false, resolutionChanged = true),
        )
        assertEquals(
            ResolutionDecision.Defer,
            decision(demandActive = false, exclusiveActive = false, resolutionChanged = false),
        )
    }

    @Test
    fun `an exclusive session defers even with an active demand`() {
        assertEquals(
            ResolutionDecision.Defer,
            decision(demandActive = true, exclusiveActive = true, resolutionChanged = true),
        )
        assertEquals(
            ResolutionDecision.Defer,
            decision(demandActive = true, exclusiveActive = true, resolutionChanged = false),
        )
    }

    @Test
    fun `neither demand nor an open camera ever rebinds`() {
        assertEquals(
            ResolutionDecision.Defer,
            decision(demandActive = false, exclusiveActive = true, resolutionChanged = true),
        )
        assertEquals(
            ResolutionDecision.Defer,
            decision(demandActive = false, exclusiveActive = true, resolutionChanged = false),
        )
    }

    // ── the freedom predicate behind the verdict ──

    @Test
    fun `an active demand with no exclusive session leaves the camera free`() {
        assertEquals(
            true,
            ResolutionApplyPolicy.isCameraFree(demandActive = true, exclusiveActive = false),
        )
    }

    @Test
    fun `an exclusive session blocks the camera even with an active demand`() {
        assertEquals(
            false,
            ResolutionApplyPolicy.isCameraFree(demandActive = true, exclusiveActive = true),
        )
    }

    @Test
    fun `no active demand leaves the camera not free`() {
        assertEquals(
            false,
            ResolutionApplyPolicy.isCameraFree(demandActive = false, exclusiveActive = false),
        )
        assertEquals(
            false,
            ResolutionApplyPolicy.isCameraFree(demandActive = false, exclusiveActive = true),
        )
    }

    @Test
    fun `the freedom predicate agrees with the verdict over the whole matrix`() {
        for (demand in listOf(true, false)) {
            for (exclusive in listOf(true, false)) {
                val free = ResolutionApplyPolicy.isCameraFree(demand, exclusive)
                val rebindsNow =
                    decision(demand, exclusive, resolutionChanged = true) is
                        ResolutionDecision.RebindNow
                assertEquals("demand=$demand exclusive=$exclusive", free, rebindsNow)
            }
        }
    }
}

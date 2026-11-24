package com.raulshma.lenscast.streaming.whep

import com.raulshma.lenscast.streaming.whep.WhepStatsPollGuard.Verdict.ISSUE
import com.raulshma.lenscast.streaming.whep.WhepStatsPollGuard.Verdict.REISSUE
import com.raulshma.lenscast.streaming.whep.WhepStatsPollGuard.Verdict.WAIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stats-poll protocol behind the WHEP lane's measurements (issue #25):
 * one report owed at a time, a quiet gap after every delivered report, and a
 * lost report declared stale instead of silencing its session. The clock is
 * the caller's, so every boundary is exact.
 */
class WhepStatsPollGuardTest {

    @Test
    fun `the first tick grants a request and none is granted while the report is owed`() {
        val guard = WhepStatsPollGuard()
        assertEquals(ISSUE, guard.arm(1_000))
        // Every tick until the report arrives: the endpoint's 2 s cadence.
        assertEquals(WAIT, guard.arm(3_000))
        assertEquals(WAIT, guard.arm(5_000))
    }

    @Test
    fun `a delivered report grants the next request once the quiet gap has passed`() {
        val guard = WhepStatsPollGuard(quietMs = 250)
        assertEquals(ISSUE, guard.arm(1_000))
        guard.onDelivered(1_100)

        // Still inside the gap… at its very last millisecond included…
        assertEquals(WAIT, guard.arm(1_349))
        // …and granted on the first millisecond after it.
        assertEquals(ISSUE, guard.arm(1_350))
        // The grant came with a new obligation.
        assertEquals(WAIT, guard.arm(1_351))
    }

    @Test
    fun `a report landing right before a tick waits out the gap instead of pushing into the callback's wake`() {
        val guard = WhepStatsPollGuard()
        assertEquals(ISSUE, guard.arm(0))
        guard.onDelivered(1_999)
        // The tick that lands a millisecond after the report: too close.
        assertEquals(WAIT, guard.arm(2_000))
        assertEquals(ISSUE, guard.arm(2_249))
    }

    @Test
    fun `a report that never arrives is declared lost and asked again`() {
        val guard = WhepStatsPollGuard(staleMs = 6_000)
        assertEquals(ISSUE, guard.arm(0))
        assertEquals(WAIT, guard.arm(2_000))
        assertEquals(WAIT, guard.arm(5_999))
        // Three ticks with nothing back: the request is declared lost.
        assertEquals(REISSUE, guard.arm(6_000))
        // The replacement is owed like any request.
        assertEquals(WAIT, guard.arm(8_000))
    }

    @Test
    fun `a request libwebrtc refused is asked again on the next tick`() {
        val guard = WhepStatsPollGuard()
        assertEquals(ISSUE, guard.arm(0))
        guard.onFailed()
        assertEquals(ISSUE, guard.arm(2_000))
    }

    @Test
    fun `a lost request's report arriving late is taken as the answer and starts a quiet gap`() {
        val guard = WhepStatsPollGuard()
        assertEquals(ISSUE, guard.arm(0))
        assertEquals(REISSUE, guard.arm(6_000))
        guard.onDelivered(6_050)
        // Nothing is owed now, but the next push still keeps its distance.
        assertEquals(WAIT, guard.arm(6_100))
        assertEquals(ISSUE, guard.arm(6_300))
    }

    @Test
    fun `each session's guard is its own`() {
        val a = WhepStatsPollGuard()
        val b = WhepStatsPollGuard()
        assertEquals(ISSUE, a.arm(0))
        assertEquals(WAIT, a.arm(2_000))
        // b's session has never been asked.
        assertEquals(ISSUE, b.arm(2_000))
    }

    @Test
    fun `the guard's own windows keep the endpoint's 2 s cadence viable`() {
        // A healthy report (tens of ms) plus the gap must never push a poll
        // out by a tick, and a stale verdict must span several polls — never
        // fire on a report that is merely late.
        assertTrue(WhepStatsPollGuard.QUIET_MS < 2_000)
        assertTrue(WhepStatsPollGuard.STALE_MS >= 3 * 2_000)
    }
}

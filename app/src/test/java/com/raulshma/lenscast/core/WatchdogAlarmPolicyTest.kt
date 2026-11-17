package com.raulshma.lenscast.core

import com.raulshma.lenscast.core.WatchdogAlarmPolicy.Action
import com.raulshma.lenscast.core.WatchdogAlarmPolicy.Decision
import com.raulshma.lenscast.core.WatchdogAlarmPolicy.EscalationTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heartbeat's escalation verdict (blueprint P0 item 3, self-healing §3's
 * integration sketch): one escalation per consecutive miss, RELAUNCH as the
 * death-and-relaunch top, STAND_DOWN on the user-stopped flag.
 */
class WatchdogAlarmPolicyTest {

    private fun decide(
        healthy: Boolean = false,
        miss: Int,
        maxMisses: Int = WatchdogAlarmPolicy.DEFAULT_MAX_MISSES,
        userStopped: Boolean = false,
        serverOnlyMode: Boolean = false,
    ): WatchdogAlarmPolicy.Decision =
        WatchdogAlarmPolicy.decide(healthy, miss, maxMisses, userStopped, serverOnlyMode)

    // ── healthy: reset the miss bookkeeping ──

    @Test
    fun `a healthy heartbeat resets the misses`() {
        assertEquals(Action.RESET, decide(healthy = true, miss = 0).action)
        // A healthy verdict after misses: the reset is the decision.
        assertEquals(Action.RESET, decide(healthy = true, miss = 2).action)
    }

    // ── one escalation per miss ──

    @Test
    fun `first miss escalates to soft`() {
        assertEquals(Decision(Action.ESCALATE, EscalationTier.SOFT), decide(miss = 1))
    }

    @Test
    fun `second miss escalates to medium`() {
        assertEquals(Decision(Action.ESCALATE, EscalationTier.MEDIUM), decide(miss = 2))
    }

    @Test
    fun `third miss escalates to the soft-restart tier`() {
        // Wave 4's insertion (P1 item 9): miss 3 is the in-process soft
        // restart — the quit ladder minus process death — not the relaunch.
        assertEquals(Decision(Action.ESCALATE, EscalationTier.SOFT_RESTART), decide(miss = 3))
    }

    @Test
    fun `fourth miss reaches the relaunch tier`() {
        assertEquals(Decision(Action.ESCALATE, EscalationTier.RELAUNCH), decide(miss = 4))
    }

    @Test
    fun `relaunch holds until the max misses are spent`() {
        assertEquals(Decision(Action.ESCALATE, EscalationTier.RELAUNCH), decide(miss = WatchdogAlarmPolicy.DEFAULT_MAX_MISSES))
    }

    @Test
    fun `past the max misses the heartbeat stands down`() {
        assertEquals(Action.STAND_DOWN, decide(miss = WatchdogAlarmPolicy.DEFAULT_MAX_MISSES + 1).action)
        assertEquals(Action.STAND_DOWN, decide(miss = 99).action)
    }

    @Test
    fun `a custom max tightens the relaunch hold`() {
        // Beyond the ladder (miss 5 with 4 tiers) the max decides; miss 4 is
        // the ordinal RELAUNCH regardless of the max — the ladder's own order
        // owns the tiers, the max owns only the hold past it.
        assertEquals(Action.STAND_DOWN, decide(miss = 5, maxMisses = 3).action)
        assertEquals(Decision(Action.ESCALATE, EscalationTier.RELAUNCH), decide(miss = 4, maxMisses = 3))
    }

    // ── the user-stopped flag: never resurrect a stream the user killed ──

    @Test
    fun `user-stopped stands down at every miss count`() {
        for (miss in 0..7) {
            assertEquals(Action.STAND_DOWN, decide(miss = miss, userStopped = true).action)
        }
    }

    @Test
    fun `user-stopped wins even when the snapshot is healthy`() {
        assertEquals(Action.STAND_DOWN, decide(healthy = true, miss = 0, userStopped = true).action)
    }

    // ── extensibility: wave 4's soft-restart tier inserts before RELAUNCH ──

    @Test
    fun `relaunch stays the last tier of the ordered ladder`() {
        // The insertion point invariant (P1 item 9 inserts SOFT_RESTART
        // between MEDIUM and RELAUNCH): RELAUNCH must remain the ladder's
        // final entry so "beyond the ladder" keeps meaning the relaunch tier.
        val ladder = EscalationTier.entries
        assertTrue(ladder.isNotEmpty())
        assertEquals(EscalationTier.RELAUNCH, ladder.last())
    }

    @Test
    fun `the wave 4 insertion sits between medium and relaunch`() {
        // The inserted tier's exact position: SOFT → MEDIUM → SOFT_RESTART →
        // RELAUNCH — the deepest in-process tier rides directly under the
        // death-and-relaunch tier it postpones.
        assertEquals(
            listOf(
                EscalationTier.SOFT,
                EscalationTier.MEDIUM,
                EscalationTier.SOFT_RESTART,
                EscalationTier.RELAUNCH,
            ),
            EscalationTier.entries.toList(),
        )
    }

    @Test
    fun `tier mapping is strictly ordinal — miss n runs the n-th tier`() {
        EscalationTier.entries.forEachIndexed { index, tier ->
            assertEquals(tier, WatchdogAlarmPolicy.tierFor(index + 1))
        }
        assertNull(WatchdogAlarmPolicy.tierFor(0))
        assertNull(WatchdogAlarmPolicy.tierFor(EscalationTier.entries.size + 1))
    }

    // ── the server-only shift: the first tier that does real work ──

    @Test
    fun `server-only first miss lands on medium — the first tier that works without a session`() {
        // Spec tension-2 honesty (fix B): SOFT is StreamingSession.recover(SOFT),
        // a camera rebind — and after a process-death relaunch there is no
        // session (and no camera bound), so a SOFT verdict there would burn a
        // 15-minute interval doing nothing. MEDIUM's session-less recover
        // restarts the server (StreamWatchdog's server-seam behavior), so the
        // ladder's head drops and miss 1 lands there.
        assertEquals(
            Decision(Action.ESCALATE, EscalationTier.MEDIUM),
            decide(miss = 1, serverOnlyMode = true),
        )
    }

    @Test
    fun `server-only ladder shifts one tier down with relaunch still last`() {
        assertEquals(
            Decision(Action.ESCALATE, EscalationTier.SOFT_RESTART),
            decide(miss = 2, serverOnlyMode = true),
        )
        assertEquals(
            Decision(Action.ESCALATE, EscalationTier.RELAUNCH),
            decide(miss = 3, serverOnlyMode = true),
        )
        // Beyond the shifted ladder the top tier holds, exactly like the
        // session ladder's hold — RELAUNCH stays the budget tier.
        assertEquals(
            Decision(Action.ESCALATE, EscalationTier.RELAUNCH),
            decide(miss = WatchdogAlarmPolicy.DEFAULT_MAX_MISSES, serverOnlyMode = true),
        )
        assertEquals(
            Action.STAND_DOWN,
            decide(miss = WatchdogAlarmPolicy.DEFAULT_MAX_MISSES + 1, serverOnlyMode = true).action,
        )
    }

    @Test
    fun `server-only mode does not disturb the healthy and stand-down verdicts`() {
        assertEquals(Action.RESET, decide(healthy = true, miss = 0, serverOnlyMode = true).action)
        assertEquals(Action.STAND_DOWN, decide(miss = 2, userStopped = true, serverOnlyMode = true).action)
    }

    @Test
    fun `server-only tierFor drops only the session-only head`() {
        assertEquals(EscalationTier.MEDIUM, WatchdogAlarmPolicy.tierFor(1, serverOnlyMode = true))
        assertEquals(EscalationTier.SOFT_RESTART, WatchdogAlarmPolicy.tierFor(2, serverOnlyMode = true))
        assertEquals(EscalationTier.RELAUNCH, WatchdogAlarmPolicy.tierFor(3, serverOnlyMode = true))
        assertNull(WatchdogAlarmPolicy.tierFor(4, serverOnlyMode = true))
        // The session ladder is untouched by the shift.
        assertEquals(EscalationTier.SOFT, WatchdogAlarmPolicy.tierFor(1))
    }
}

package com.raulshma.lenscast.core

import com.raulshma.lenscast.core.EcoIdlePolicy.State
import com.raulshma.lenscast.core.EcoIdlePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the eco idle-fps decision core: the drop ladder (enabled +
 * off charger + no consumers + thermal NORMAL, held long enough, past the
 * restore cooldown), the immediate restore on any consumer/charger/thermal/
 * toggle event, and the anti-thrash cooldown in between.
 */
class EcoIdlePolicyTest {

    private val t0 = 1_000_000L
    private val inactive = State()

    private fun evaluate(
        enabled: Boolean = true,
        charging: Boolean = false,
        hasConsumers: Boolean = false,
        thermalNormal: Boolean = true,
        nowMs: Long = t0,
        state: State = inactive,
    ): EcoIdlePolicy.Decision = EcoIdlePolicy.evaluate(
        enabled = enabled,
        charging = charging,
        hasConsumers = hasConsumers,
        thermalNormal = thermalNormal,
        nowMs = nowMs,
        state = state,
    )

    // ── Drop ladder ──

    @Test
    fun `disabled never drops`() {
        val decision = evaluate(enabled = false)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `idle but not yet stable holds with the idle stamp set`() {
        val decision = evaluate(nowMs = t0 + 1_000)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(t0 + 1_000, decision.nextState.idleSinceMs)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `idle stable past the cooldown drops`() {
        val decision = evaluate(
            nowMs = t0 + EcoIdlePolicy.IDLE_STABLE_MS,
            state = State(idleSinceMs = t0),
        )
        assertEquals(Verdict.Drop, decision.verdict)
        assertEquals(true, decision.nextState.active)
    }

    @Test
    fun `charging never drops`() {
        val decision = evaluate(charging = true, nowMs = t0 + 10 * EcoIdlePolicy.IDLE_STABLE_MS)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `consumers present never drop`() {
        val decision = evaluate(hasConsumers = true, nowMs = t0 + 10 * EcoIdlePolicy.IDLE_STABLE_MS)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    // ── Hold while active ──

    @Test
    fun `already active stays active while idle holds`() {
        val decision = evaluate(nowMs = t0, state = State(active = true, idleSinceMs = t0 - 10_000))
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(true, decision.nextState.active)
    }

    // ── Restore on any break of the idle conditions ──

    @Test
    fun `client appearing restores and arms the cooldown`() {
        val active = State(active = true, idleSinceMs = t0 - 60_000)
        val decision = evaluate(hasConsumers = true, nowMs = t0, state = active)
        assertEquals(Verdict.Restore, decision.verdict)
        val next = decision.nextState
        assertEquals(false, next.active)
        assertEquals(null, next.idleSinceMs)
        assertEquals(t0 + EcoIdlePolicy.RESTORE_COOLDOWN_MS, next.restoreNotBeforeMs)
    }

    @Test
    fun `charger connecting restores`() {
        val decision = evaluate(charging = true, nowMs = t0, state = State(active = true))
        assertEquals(Verdict.Restore, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `thermal escalation restores - thermal wins over eco`() {
        val decision = evaluate(thermalNormal = false, nowMs = t0, state = State(active = true))
        assertEquals(Verdict.Restore, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `thermal non-normal without an active mode just holds`() {
        val decision = evaluate(thermalNormal = false, nowMs = t0)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `toggle disabled while active restores`() {
        val decision = evaluate(enabled = false, nowMs = t0, state = State(active = true))
        assertEquals(Verdict.Restore, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    // ── Anti-thrash cooldown ──

    @Test
    fun `re-drop inside the restore cooldown is blocked even when idle again`() {
        val restored = State(active = false, idleSinceMs = null, restoreNotBeforeMs = t0 + EcoIdlePolicy.RESTORE_COOLDOWN_MS)
        // Idle continuously from before the cooldown lapses.
        val decision = evaluate(nowMs = t0 + 1_000, state = restored)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `drop lands once the cooldown has lapsed`() {
        val state = State(active = false, idleSinceMs = t0, restoreNotBeforeMs = t0 - 1)
        val decision = evaluate(nowMs = t0 + EcoIdlePolicy.IDLE_STABLE_MS, state = state)
        assertEquals(Verdict.Drop, decision.verdict)
    }

    @Test
    fun `idle stability alone is not enough inside the cooldown`() {
        val state = State(active = false, idleSinceMs = t0, restoreNotBeforeMs = t0 + 60_000)
        val decision = evaluate(nowMs = t0 + EcoIdlePolicy.IDLE_STABLE_MS, state = state)
        assertEquals(Verdict.Hold, decision.verdict)
        assertEquals(false, decision.nextState.active)
    }

    @Test
    fun `idle stability restarts after a restore`() {
        // Restored at t0 with a lapsed cooldown: idle observed again at t0+1s
        // must not drop until IDLE_STABLE_MS has passed since that observation.
        val restored = State(active = false, idleSinceMs = null, restoreNotBeforeMs = 0)
        val held = evaluate(nowMs = t0 + 1_000, state = restored)
        assertEquals(Verdict.Hold, held.verdict)
        val dropped = evaluate(nowMs = t0 + 1_000 + EcoIdlePolicy.IDLE_STABLE_MS, state = held.nextState)
        assertEquals(Verdict.Drop, dropped.verdict)
    }

    // ── Floor fps ──

    @Test
    fun `floor is the eco fps under a normal user rate`() {
        assertEquals(StreamDefaults.ECO_IDLE_FPS, EcoIdlePolicy.floorFps(userFps = 24))
    }

    @Test
    fun `floor never exceeds the user rate`() {
        assertEquals(4, EcoIdlePolicy.floorFps(userFps = 4))
    }

    @Test
    fun `floor respects the adaptive minimum`() {
        val floor = EcoIdlePolicy.floorFps(userFps = 2, minFps = StreamDefaults.ADAPTIVE_FPS_MIN)
        assertTrue(floor >= StreamDefaults.ADAPTIVE_FPS_MIN)
    }
}

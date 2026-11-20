package com.raulshma.lenscast.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one grace-windowed arm/disarm bridge both server-lifecycle consumers
 * run (the composition root's server-liveness watch leg, HeartbeatController's
 * alarm): the up leg arms immediately, the down leg waits out the grace, the
 * pending stop cancels on a promote, and the delayed stop re-checks the
 * flow at FIRE time — the #14 race class handled once, not once per leg.
 */
class GraceWindowedArmingTest {

    private val isServerRunning = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val armCalls = mutableListOf<Int>()
    private val disarmCalls = mutableListOf<Int>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun bridge(graceMs: Long = 300) = GraceWindowedArming(
        isServerRunning = isServerRunning,
        stopGraceMs = graceMs,
        arm = { armCalls += armCalls.size },
        disarm = { disarmCalls += disarmCalls.size },
    )

    @Test
    fun `the up leg arms immediately and the initial down leg settles through the grace`() {
        val target = bridge()
        target.start(scope)
        // The initial false emission schedules the stop; it lands only
        // after the grace — the fire-time re-check passes (still down).
        eventually { assertEquals(listOf(0), disarmCalls) }
        assertEquals(0, armCalls.size)

        isServerRunning.value = true
        eventually { assertEquals(listOf(0), armCalls) }
    }

    @Test
    fun `a restart inside the grace cancels the pending stop`() {
        // Issue #14's race: the down leg of a restart must not tear down
        // what the up leg is about to re-arm.
        val target = bridge(graceMs = 400)
        target.start(scope)
        eventually { assertEquals(1, disarmCalls.size) }

        isServerRunning.value = true
        eventually { assertEquals(1, armCalls.size) }
        // One restart: the down leg schedules the stop, the up leg cancels it.
        isServerRunning.value = false
        Thread.sleep(150) // still inside the grace window
        isServerRunning.value = true
        eventually { assertEquals(2, armCalls.size) }
        Thread.sleep(600) // outlive the original grace window
        // The restart never disarmed: the canceled pending stop must not
        // have fired behind the up leg.
        assertEquals(1, disarmCalls.size)
    }

    @Test
    fun `a server down past the stop grace disarms exactly once`() {
        val target = bridge(graceMs = 300)
        target.start(scope)
        eventually { assertEquals(1, disarmCalls.size) }

        isServerRunning.value = true
        eventually { assertEquals(1, armCalls.size) }
        isServerRunning.value = false
        Thread.sleep(150) // inside the grace: the delayed stop has not landed yet
        assertEquals(1, disarmCalls.size)
        eventually { assertEquals(2, disarmCalls.size) }
        Thread.sleep(400) // outlive the grace: no double-fire behind it
        assertEquals(2, disarmCalls.size)
    }

    @Test
    fun `the fire-time re-check gates the delayed disarm on the live flow`() {
        // The re-check's honest form: a pending stop whose grace elapses
        // while the server is still down MUST disarm (the down state is
        // re-read, and it holds) — pinned here against the cancel-wins
        // restart test above, which pins the other half (a server that
        // came back inside the window keeps its watch/alarm). Together the
        // two tests pin both arms of `if (!isServerRunning.value)` at the
        // delayed stop's fire point.
        val target = bridge(graceMs = 150)
        target.start(scope)
        // The initial down leg must land first — flipping true before the
        // collector consumed it would conflate the two emissions and never
        // schedule the pending stop this test exercises.
        eventually { assertEquals(1, disarmCalls.size) }
        isServerRunning.value = true
        eventually { assertEquals(1, armCalls.size) }
        isServerRunning.value = false
        Thread.sleep(400) // the grace elapses with the server still down
        eventually { assertEquals(2, disarmCalls.size) }
    }

    @Test
    fun `an up leg that arms while the stop job is past its delay never loses the arm to a late disarm`() {
        // The residual #14 shape: a job past its delay cannot be canceled,
        // so the up leg's arm must hold the monitor the stop leg's re-check
        // + disarm needs — the re-check then sees the fresh true and skips.
        val target = bridge(graceMs = 200)
        target.start(scope)
        eventually { assertEquals(1, disarmCalls.size) } // initial down leg lands; collector idle
        target.applyNow(false) // schedule the stop under test deterministically
        synchronized(target) {
            Thread.sleep(400) // grace elapses with the monitor held: the stop job queues on it
            isServerRunning.value = true
            target.applyNow(true) // the up leg arms while the stop job waits on the monitor
        }
        eventually { assertTrue(armCalls.isNotEmpty()) }
        Thread.sleep(300) // outlive the grace: the released stop job must not disarm behind the arm
        assertEquals(1, disarmCalls.size)
    }

    @Test
    fun `a stop leg that wins the monitor first disarms atomically and a later up leg re-arms`() {
        // The symmetric ordering: the grace elapses with the server still
        // down, the stop leg takes the monitor and disarms; the up leg that
        // follows re-arms and no second disarm lands behind it.
        val target = bridge(graceMs = 200)
        target.start(scope)
        eventually { assertEquals(1, disarmCalls.size) }
        target.applyNow(false)
        synchronized(target) {
            Thread.sleep(400) // grace elapses with the monitor held, server still down
        }
        eventually { assertEquals(2, disarmCalls.size) } // stop leg wins the monitor, disarms
        isServerRunning.value = true
        eventually { assertEquals(listOf(0), armCalls) } // subsequent up leg re-arms
        Thread.sleep(300)
        assertEquals(2, disarmCalls.size)
    }

    private fun eventually(block: () -> Unit) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            try {
                block()
                return
            } catch (_: AssertionError) {
                Thread.sleep(10)
            }
        }
        block()
    }
}

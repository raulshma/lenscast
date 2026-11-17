package com.raulshma.lenscast.core

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import com.raulshma.lenscast.streaming.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The heartbeat shell under the framework's AlarmManager shadow: the arming
 * seam (server lifecycle in, alarm scheduled/canceled out) and the verdict
 * execution (fake health/recover/relaunch seams in, escalation order out).
 * The decisions themselves are pinned by [WatchdogAlarmPolicyTest]; this
 * pins the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatControllerTest {

    private lateinit var context: Context
    private val isServerRunning = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val recoverCalls = mutableListOf<String>()
    private val softRestartCalls = mutableListOf<Int>()
    private val relaunchCalls = mutableListOf<Int>()

    private var healthy = true
    private var serverOnlyMode = false
    private var userStopped = false

    private lateinit var controller: HeartbeatController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        controller = HeartbeatController(
            context = context,
            isServerRunning = isServerRunning,
            healthCheck = {
                HeartbeatController.HealthRead(healthy = healthy, serverOnlyMode = serverOnlyMode)
            },
            recover = { tier -> recoverCalls += tier.name },
            softRestart = { softRestartCalls += softRestartCalls.size },
            relaunch = { relaunchCalls += relaunchCalls.size },
            userStopped = { userStopped },
            // Tight grace: the disarm leg must not stall the test.
            stopGraceMs = 250,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // peek, not next: getNextScheduledAlarm POLLS the shadow's queue and
    // would consume the very alarm these assertions are looking at.
    private fun scheduledAlarm() = shadowOf(alarmManager()).peekNextScheduledAlarm()

    // ── the arming seam: server lifecycle in, alarm out ──

    @Test
    fun `the server lifecycle arms and disarms the heartbeat alarm`() {
        controller.start(scope)
        assertNull(scheduledAlarm())

        isServerRunning.value = true
        eventually { assertNotNull(scheduledAlarm()) }

        val alarm = scheduledAlarm() ?: throw AssertionError("alarm not scheduled")
        val now = SystemClock.elapsedRealtime()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        assertTrue(
            "trigger should be one interval out (now=$now, trigger=${alarm.triggerAtMs})",
            alarm.triggerAtMs in now until now + HeartbeatController.INTERVAL_MS + 5_000,
        )
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals(StreamingService.ACTION_HEARTBEAT, intent.action)
    }

    @Test
    fun `a server stop past the grace cancels the alarm`() {
        controller.start(scope)
        isServerRunning.value = true
        eventually { assertNotNull(scheduledAlarm()) }

        isServerRunning.value = false
        eventually { assertNull(scheduledAlarm()) }
    }

    @Test
    fun `a server restart inside the grace never disarms the alarm`() {
        controller.start(scope)
        isServerRunning.value = true
        eventually { assertNotNull(scheduledAlarm()) }

        // The down leg of a restart must not cancel the (just re-armed)
        // alarm before the up leg replaces it.
        isServerRunning.value = false
        isServerRunning.value = true
        eventually { assertNotNull(scheduledAlarm()) }
        Thread.sleep(400) // outlive the grace: the pending stop was canceled
        // eventually, not a bare read: under a loaded suite JVM the bridge
        // collector can trail the sleep by more than the grace, and the
        // settled end-state (up leg armed, pending stop canceled) is what
        // this pins — a disarm that never re-arms still fails here.
        eventually { assertNotNull(scheduledAlarm()) }
    }

    // ── the verdict execution: one escalation per miss ──

    @Test
    fun `consecutive misses escalate soft then medium then soft-restart then relaunch`() {
        healthy = false
        isServerRunning.value = true
        controller.start(scope)
        eventually { assertTrue(controller.state.value.armed) }

        controller.onHeartbeatFired()
        eventually { assertEquals(listOf("SOFT"), recoverCalls) }

        controller.onHeartbeatFired()
        eventually { assertEquals(listOf("SOFT", "MEDIUM"), recoverCalls) }

        // Wave 4's insertion: miss 3 runs the in-process soft-restart ladder
        // (the same seam the dashboard's ?mode=soft route runs), postponing
        // the death-and-relaunch tier to miss 4.
        controller.onHeartbeatFired()
        eventually { assertEquals(listOf(0), softRestartCalls) }
        assertEquals(listOf("SOFT", "MEDIUM"), recoverCalls)
        assertEquals(WatchdogAlarmPolicy.EscalationTier.SOFT_RESTART, controller.state.value.lastTier)

        controller.onHeartbeatFired()
        eventually { assertEquals(1, relaunchCalls.size) }
        assertEquals(listOf(0), softRestartCalls)
    }

    @Test
    fun `a healthy heartbeat resets the miss bookkeeping`() {
        isServerRunning.value = true
        controller.start(scope)
        eventually { assertTrue(controller.state.value.armed) }

        healthy = false
        controller.onHeartbeatFired()
        eventually { assertEquals(1, controller.state.value.consecutiveMisses) }

        healthy = true
        controller.onHeartbeatFired()
        eventually { assertEquals(0, controller.state.value.consecutiveMisses) }
        // Settle the reset fire's coroutine before the next miss: the reset
        // verdict's own `misses.set(0)` trails the state update, and a third
        // fire racing it would count as miss 2 (MEDIUM) instead of a fresh
        // miss 1 — the flake this line exists to close.
        eventually { assertEquals(WatchdogAlarmPolicy.Action.RESET, controller.state.value.lastAction) }

        // The next miss starts the ladder from the top again, not at MEDIUM.
        healthy = false
        controller.onHeartbeatFired()
        eventually { assertEquals(listOf("SOFT", "SOFT"), recoverCalls) }
    }

    @Test
    fun `the user-stopped flag stands the heartbeat down`() {
        healthy = false
        userStopped = true
        isServerRunning.value = true
        controller.start(scope)
        eventually { assertTrue(controller.state.value.armed) }

        controller.onHeartbeatFired()
        eventually { assertEquals(WatchdogAlarmPolicy.Action.STAND_DOWN, controller.state.value.lastAction) }
        assertEquals(0, recoverCalls.size)
        assertEquals(0, relaunchCalls.size)
    }

    @Test
    fun `misses past the budget stand down instead of re-launching forever`() {
        val tight = HeartbeatController(
            context = context,
            isServerRunning = isServerRunning,
            healthCheck = { HeartbeatController.HealthRead(healthy = false, serverOnlyMode = false) },
            recover = { tier -> recoverCalls += tier.name },
            softRestart = { softRestartCalls += softRestartCalls.size },
            relaunch = { relaunchCalls += relaunchCalls.size },
            userStopped = { false },
            maxMisses = 3,
            stopGraceMs = 250,
        )
        isServerRunning.value = true
        tight.start(scope)
        eventually { assertTrue(tight.state.value.armed) }

        // Miss 3 is the ordinal SOFT_RESTART; miss 4 the ordinal RELAUNCH
        // (the ladder's order owns the tiers, the max owns only the hold
        // past it) — then miss 5 is beyond the ladder and past the max:
        // stand down, never re-launch forever.
        tight.onHeartbeatFired()
        tight.onHeartbeatFired()
        tight.onHeartbeatFired()
        eventually { assertEquals(listOf(0), softRestartCalls) }
        assertEquals(0, relaunchCalls.size)

        tight.onHeartbeatFired()
        eventually { assertEquals(1, relaunchCalls.size) }

        tight.onHeartbeatFired()
        eventually { assertEquals(WatchdogAlarmPolicy.Action.STAND_DOWN, tight.state.value.lastAction) }
        assertEquals(1, relaunchCalls.size)
    }

    @Test
    fun `a stale fire with the server down never evaluates`() {
        controller.start(scope)
        controller.onHeartbeatFired()
        Thread.sleep(300)
        assertEquals(0, recoverCalls.size)
        assertEquals(0, relaunchCalls.size)
    }

    @Test
    fun `server-only misses skip the session-only soft tier and start at medium`() {
        // Fix B: after a process-death relaunch there is no session, so the
        // first miss must land on the first tier that does real work without
        // one — MEDIUM's session-less recover restarts the server — instead
        // of wasting the interval on a SOFT camera rebind with no camera.
        // The rest of the ladder follows shifted, RELAUNCH still the top.
        serverOnlyMode = true
        healthy = false
        isServerRunning.value = true
        controller.start(scope)
        eventually { assertTrue(controller.state.value.armed) }

        controller.onHeartbeatFired()
        eventually { assertEquals(listOf("MEDIUM"), recoverCalls) }

        controller.onHeartbeatFired()
        eventually { assertEquals(listOf(0), softRestartCalls) }
        assertEquals(listOf("MEDIUM"), recoverCalls)

        controller.onHeartbeatFired()
        eventually { assertEquals(1, relaunchCalls.size) }
        assertEquals(WatchdogAlarmPolicy.EscalationTier.RELAUNCH, controller.state.value.lastTier)
    }

    @Test
    fun `a throwing health seam counts as a miss`() {
        val throwing = HeartbeatController(
            context = context,
            isServerRunning = isServerRunning,
            healthCheck = { throw IllegalStateException("wedged") },
            recover = { tier -> recoverCalls += tier.name },
            relaunch = { relaunchCalls += relaunchCalls.size },
            userStopped = { false },
            stopGraceMs = 250,
        )
        isServerRunning.value = true
        throwing.start(scope)
        eventually { assertTrue(throwing.state.value.armed) }

        throwing.onHeartbeatFired()
        eventually { assertEquals(listOf("SOFT"), recoverCalls) }
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

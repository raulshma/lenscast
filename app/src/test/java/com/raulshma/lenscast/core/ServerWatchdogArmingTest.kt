package com.raulshma.lenscast.core

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

class ServerWatchdogArmingTest {

    private val isServerRunning = MutableStateFlow(false)
    private val watchdog: StreamWatchdog = mockk(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val arming = ServerWatchdogArming(watchdog, isServerRunning)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the server lifecycle arms and disarms the liveness watch`() {
        arming.start(scope)
        eventually { verify(exactly = 1) { watchdog.stopServerLivenessWatch() } }

        isServerRunning.value = true
        eventually { verify(exactly = 1) { watchdog.startServerLivenessWatch() } }

        isServerRunning.value = false
        eventually { verify(atLeast = 2) { watchdog.stopServerLivenessWatch() } }
    }

    @Test
    fun `the session seam's arming is never touched`() {
        // The bridge drives ONLY the server-liveness pair — the session's
        // arming (startMonitoring/stopMonitoring) stays StreamingSession's.
        arming.start(scope)
        isServerRunning.value = true
        eventually { verify { watchdog.startServerLivenessWatch() } }
        verify(exactly = 0) { watchdog.startMonitoring() }
        verify(exactly = 0) { watchdog.stopMonitoring() }
    }

    @Test
    fun `a transient server restart inside the stop grace never disarms the watch`() {
        // Issue #14's race class on the watchdog's side: the down leg of a
        // restart (port apply, or the watchdog's own MEDIUM recovery) must
        // not cancel the loop that is performing the recovery.
        val grace = ServerWatchdogArming(watchdog, isServerRunning, stopGraceMs = 400)
        grace.start(scope)
        // The initial down state settles through the grace first.
        eventually { verify(exactly = 1) { watchdog.stopServerLivenessWatch() } }

        isServerRunning.value = true
        eventually { verify(atLeast = 1) { watchdog.startServerLivenessWatch() } }
        // One restart: down leg schedules the stop, up leg must cancel it.
        isServerRunning.value = false
        Thread.sleep(150) // still inside the grace window
        isServerRunning.value = true
        eventually { verify(atLeast = 2) { watchdog.startServerLivenessWatch() } }
        Thread.sleep(600) // outlive the original grace window: 150 + 600 > 400
        // The restart never disarmed the watch: only the initial stop exists.
        verify(exactly = 1) { watchdog.stopServerLivenessWatch() }
    }

    @Test
    fun `a server down past the stop grace still disarms exactly once`() {
        val grace = ServerWatchdogArming(watchdog, isServerRunning, stopGraceMs = 300)
        grace.start(scope)
        eventually { verify(exactly = 1) { watchdog.stopServerLivenessWatch() } }

        isServerRunning.value = true
        eventually { verify(atLeast = 1) { watchdog.startServerLivenessWatch() } }
        isServerRunning.value = false
        Thread.sleep(150) // inside the grace: the delayed stop has not landed yet
        verify(exactly = 1) { watchdog.stopServerLivenessWatch() }
        eventually { verify(exactly = 2) { watchdog.stopServerLivenessWatch() } }
        Thread.sleep(400) // outlive the grace: no double-fire behind it
        verify(exactly = 2) { watchdog.stopServerLivenessWatch() }
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

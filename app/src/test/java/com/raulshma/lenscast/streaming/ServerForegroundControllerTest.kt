package com.raulshma.lenscast.streaming

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

class ServerForegroundControllerTest {

    private val isServerRunning = MutableStateFlow(false)
    private val session: StreamingSession = mockk(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = ServerForegroundController(session, isServerRunning)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `server lifecycle switches between foreground server mode and idle stop`() {
        controller.start(scope)
        eventually { verify { session.stopServerForegroundIfIdle() } }

        isServerRunning.value = true
        eventually { verify { session.ensureServerForeground() } }

        isServerRunning.value = false
        eventually { verify(atLeast = 2) { session.stopServerForegroundIfIdle() } }
    }

    @Test
    fun `sync retries the current server state without waiting for a new emission`() {
        isServerRunning.value = true

        controller.sync()

        verify(exactly = 1) { session.ensureServerForeground() }
        verify(exactly = 0) { session.stopServerForegroundIfIdle() }
    }

    @Test
    fun `a transient server restart inside the stop grace never bounces the service`() {
        // Issue #14's fatal pattern: a port change bounces isServerRunning
        // false → true inside one restart. Stopping on the down leg races the
        // promotion that immediately follows and can throw
        // ForegroundServiceDidNotStartInTimeException, so the promote must
        // cancel the pending stop and the service must never be torn down.
        val grace = ServerForegroundController(session, isServerRunning, stopGraceMs = 400)
        grace.start(scope)
        // The initial down state settles through the grace first.
        eventually { verify(exactly = 1) { session.stopServerForegroundIfIdle() } }

        isServerRunning.value = true
        eventually { verify(atLeast = 1) { session.ensureServerForeground() } }
        // One restart: down leg schedules the stop, up leg must cancel it.
        isServerRunning.value = false
        Thread.sleep(150) // still inside the grace window
        isServerRunning.value = true
        eventually { verify(atLeast = 2) { session.ensureServerForeground() } }
        Thread.sleep(600) // outlive the original grace window: 150 + 600 > 400
        // The restart never bounced the service: only the initial stop exists.
        verify(exactly = 1) { session.stopServerForegroundIfIdle() }
    }

    @Test
    fun `a server down past the stop grace still drops the service exactly once`() {
        val grace = ServerForegroundController(session, isServerRunning, stopGraceMs = 300)
        grace.start(scope)
        // The initial down state lands one delayed stop.
        eventually { verify(exactly = 1) { session.stopServerForegroundIfIdle() } }

        isServerRunning.value = true
        eventually { verify(atLeast = 1) { session.ensureServerForeground() } }
        isServerRunning.value = false
        Thread.sleep(150) // inside the grace: the delayed stop has not landed yet
        verify(exactly = 1) { session.stopServerForegroundIfIdle() }
        eventually { verify(exactly = 2) { session.stopServerForegroundIfIdle() } }
        Thread.sleep(400) // outlive the grace: no double-fire behind it
        verify(exactly = 2) { session.stopServerForegroundIfIdle() }
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

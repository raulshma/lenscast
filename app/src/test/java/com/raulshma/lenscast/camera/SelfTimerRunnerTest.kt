package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CountdownState
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.camera.model.SelfTimerPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The shared self-timer countdown holder's contract, on a fake clock: the
 * policy-dispatched shutter press (capture-now vs countdown), the tick walk
 * (whole seconds remaining, rounded up), the Fire boundary calling [SelfTimerRunner.onFire]
 * exactly once, tap-cancel, the mode→OFF unconditional cancel, and the
 * derived remaining-seconds flow. No virtual time — the runner's injected
 * `pauseMs` advances the fake clock per tick (the real-threads stance this
 * package's tests share), so a full countdown runs in microseconds. The
 * cancel test additionally runs its runner on a `StandardTestDispatcher` —
 * not for virtual time (the fake clock still paces it), but so the
 * post-cancel drain (`runCurrent` / `advanceUntilIdle`) runs every runnable
 * piece of countdown work deterministically before the fire verdict is
 * asserted: a fixed real-time delay can only guess whether a cancelled job
 * has actually resumed.
 */
class SelfTimerRunnerTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `an off timer captures immediately without touching the countdown`() = runBlocking {
        val fired = AtomicBoolean(false)
        val runner = SelfTimerRunner(scope, onFire = { fired.set(true) })
        var capturedNow = false
        runner.onShutterPress(SelfTimerMode.OFF) { capturedNow = true }
        assertTrue(capturedNow)
        assertEquals(false, fired.get())
        assertEquals(CountdownState.Idle, runner.state.value)
        assertNull(runner.secondsRemaining.first())
        runner.dispose()
    }

    @Test
    fun `an armed timer counts whole seconds and fires once at the end`() = runBlocking {
        val clock = AtomicLong(0)
        val fires = AtomicLong(0)
        // Park the FIRST tick on a gate so the synchronous Counting write is
        // observable before the (instant, fake-clocked) countdown runs away.
        val parked = CompletableDeferred<Unit>()
        val firstPause = AtomicBoolean(true)
        val runner = SelfTimerRunner(
            scope,
            onFire = { fires.incrementAndGet() },
            elapsedMs = clock::get,
            pauseMs = { _ ->
                if (firstPause.getAndSet(false)) parked.await()
                clock.addAndGet(SelfTimerPolicy.TICK_MS.toLong())
            },
        )

        runner.onShutterPress(SelfTimerMode.S3) { }
        // The initial Counting lands synchronously at start, before the job,
        // and the gated park holds it for the assertion.
        assertEquals(3, (runner.state.value as CountdownState.Counting).secondsRemaining)
        // The derived flow STARTS at null (the eager chain's initial value),
        // so a null seen here is not the countdown's end — require a real
        // emission of 3 before releasing the first tick.
        withTimeout(10_000) { runner.secondsRemaining.first { it == 3 } }
        parked.complete(Unit) // release the walk: 15 fake ticks to Fire
        // The countdown lands Idle via Firing.
        withTimeout(10_000) { runner.state.first { it == CountdownState.Idle } }
        assertEquals(1L, fires.get())
        // Only after the observed 3 may the flow be accepted back at null.
        withTimeout(10_000) { runner.secondsRemaining.first { it == null } }
        runner.dispose()
    }

    @Test
    fun `a mid-countdown tap cancels and no capture fires`() = runBlocking {
        val clock = AtomicLong(0)
        val fired = AtomicBoolean(false)
        // Park the FIRST tick on a gate so the countdown cannot reach Fire
        // mid-test; the gate's pause advances the fake clock once released,
        // so a loop the cancel failed to stop would walk on to Fire — the
        // final assertion checks the job stopped, not just that the state
        // reset. The runner's scope runs on a TEST dispatcher: after the
        // gate releases, the drain runs every runnable piece of countdown
        // work (the cancelled job's resumption — which its in-loop
        // ensureActive() turns into cancellation — or, if the cancel failed,
        // a whole broken loop's ticks) deterministically before the fire
        // verdict is asserted.
        val parked = CompletableDeferred<Unit>()
        val firstPause = AtomicBoolean(true)
        val dispatcher = StandardTestDispatcher()
        val countdownScope = CoroutineScope(SupervisorJob() + dispatcher)
        val runner = SelfTimerRunner(
            countdownScope,
            onFire = { fired.set(true) },
            elapsedMs = clock::get,
            pauseMs = { _ ->
                if (firstPause.getAndSet(false)) parked.await()
                clock.addAndGet(SelfTimerPolicy.TICK_MS.toLong())
            },
        )
        runner.onShutterPress(SelfTimerMode.S10) { }
        // Run the queued launch: the job walks straight into the gated park,
        // so the tap below cancels an ACTIVE (parked) countdown, not one
        // that never started.
        dispatcher.scheduler.runCurrent()
        assertTrue(runner.state.value is CountdownState.Counting)
        runner.cancelSelfTimer()
        assertEquals(CountdownState.Idle, runner.state.value)
        // Release the walk, then drain: a loop the cancel failed to stop
        // would resume here and tick (50 fake ticks) all the way to Fire
        // inside the drain, and the assert below would see it.
        parked.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        // Give a (cancelled) loop no chance to have fired.
        assertEquals(false, fired.get())
        runner.dispose()
        countdownScope.cancel()
    }

    @Test
    fun `a tap with no countdown passes through without cancelling`() {
        val runner = SelfTimerRunner(CoroutineScope(SupervisorJob()), onFire = {})
        // PASS_THROUGH is the policy's verdict — nothing to assert beyond no-crash.
        runner.cancelSelfTimer()
        assertEquals(CountdownState.Idle, runner.state.value)
    }
}

package com.raulshma.lenscast.core

import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.streaming.RtspHealth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The watchdog's loop choreography under the injected [TestScope]: the
 * single-loop ownership (the on-device double-recovery-ladder bug class),
 * the arming handoff between the session loop and the server-liveness
 * baseline, the frame-stall escalation through the injected recover lever,
 * the [StreamWatchdog.evaluateOnce] parity, and the [StreamWatchdog.enabled]
 * gating. The decisions themselves are pinned by [WatchdogPolicyTest]; this
 * pins the loop that acts on them.
 *
 * Every `delay` the loop takes — the tick interval, the backoff, the
 * verification windows — runs on the test scheduler's virtual clock, and the
 * fake [StreamFacts]' wall clock IS that same clock, so the 15s stall
 * window advances virtually too. [TestScope.advanceUntilIdle] is never
 * used here: the monitor loop's `while (true)` reschedules itself forever
 * and would never idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamWatchdogTest {

    private val testScope = TestScope(StandardTestDispatcher())

    // ── the fake StreamFacts ──

    private val serverRunning = MutableStateFlow(false)
    private val rtspRunning = MutableStateFlow(false)
    private val processedFrames = MutableStateFlow(0)
    private val clientCount = MutableStateFlow(0)
    private val cameraState = MutableStateFlow<CameraState>(CameraState.Idle)

    /** The camera-frame counter: frozen at zero until the lever "fixes" the stream, then advancing with the virtual clock. */
    @Volatile
    private var framesFlowing = false
    @Volatile
    private var liveStreaming = false
    /** null = the probe is not applicable; the loop skips it (server flag down). */
    @Volatile
    private var probeAnswer: Boolean? = null
    @Volatile
    private var recoverResult = true

    /** The health-check count — one arming read plus one per tick (plus the recovery-success re-read). */
    private val rtspHealthCalls = AtomicInteger(0)
    private val probeStarts = AtomicInteger(0)
    private val recoverCalls = ArrayList<String>()

    private val fakeFacts = object : StreamFacts {
        // Explicit outer qualification: the overrides share names with the
        // interface members, and bare references recurse the inference.
        override val isServerRunning = this@StreamWatchdogTest.serverRunning
        override val isRtspRunning = this@StreamWatchdogTest.rtspRunning
        override val processedFrames = this@StreamWatchdogTest.processedFrames
        override val clientCount = this@StreamWatchdogTest.clientCount
        override val cameraState = this@StreamWatchdogTest.cameraState

        override fun cameraFrameCount(): Long = if (framesFlowing) testScope.testScheduler.currentTime else 0L

        override fun getRtspHealth(): RtspHealth {
            rtspHealthCalls.incrementAndGet()
            return RtspHealth()
        }

        override fun isLiveStreaming(): Boolean = liveStreaming
    }

    private lateinit var watchdog: StreamWatchdog

    @Before
    fun setUp() {
        watchdog = StreamWatchdog(
            streamFacts = { fakeFacts },
            recover = { tier ->
                recoverCalls += tier.name
                framesFlowing = true // the lever's mechanics bring the frames back
                recoverResult
            },
            loopbackProbe = {
                probeStarts.incrementAndGet()
                probeAnswer
            },
            // The facts' wall clock and the loop's delays: one virtual clock.
            nowMs = { testScope.testScheduler.currentTime },
            scope = testScope,
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // ── driving the virtual clock ──

    private val checkIntervalMs = StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS * 1000L

    /**
     * Advance the virtual clock by one planned leg, then drain it fully.
     * [expectedCalls] — the cumulative health-check count the leg leaves
     * behind — is the settle assertion. The drain matters for correctness,
     * not just convenience: a tick fires at the boundary this advance
     * reaches, then parks in the probe's `Dispatchers.IO` hop, whose
     * continuation posts back a few real microseconds later — and only its
     * run schedules the NEXT delay. Returning before that run would let the
     * next advance hop over the unposted continuation and drop the next
     * delay one interval into the future, knocking the loop off the grid
     * these advances plan. So: real-time cycles of `runCurrent` + sleep
     * (the virtual clock untouched — an unplanned advance would skip past
     * the choreography's milestones, and `advanceUntilIdle` would never
     * return against the loop's `while (true)`), generous enough for the
     * fake probe's microseconds.
     */
    private fun advance(ms: Long, expectedCalls: Int) {
        testScope.advanceTimeBy(ms)
        repeat(20) {
            testScope.runCurrent()
            Thread.sleep(5)
        }
        assertEquals(expectedCalls, rtspHealthCalls.get())
    }

    /**
     * HeartbeatControllerTest's eventually, scoped to the scheduler:
     * [TestScope.runCurrent] runs the tasks already due — including the
     * IO-hop continuations that posted while we slept.
     */
    private fun eventually(block: () -> Unit) {
        val deadline = System.currentTimeMillis() + 5_000
        var last: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            testScope.runCurrent()
            try {
                block()
                return
            } catch (e: AssertionError) {
                last = e
                Thread.sleep(10)
            }
        }
        throw last ?: AssertionError("condition not reached within 5s")
    }

    // ── single-loop ownership ──

    @Test
    fun `starting the session loop twice arms one loop, not two`() {
        watchdog.setEnabled(true)
        serverRunning.value = true
        probeAnswer = true
        liveStreaming = true
        framesFlowing = true

        watchdog.startMonitoring()
        watchdog.startMonitoring() // the double-arm call

        // Only the first start reset the tick bookkeeping (one arming read);
        // the second hit the already-monitoring guard.
        assertEquals(1, rtspHealthCalls.get())
        assertTrue(watchdog.isMonitoring)

        // One loop: exactly one health check per interval on top of the
        // arming read. The on-device double loop doubled every check —
        // two interleaved recovery ladders healing one stream.
        advance(checkIntervalMs, 2)
        advance(checkIntervalMs, 3)
        advance(checkIntervalMs, 4)
        assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, watchdog.state.value.status)
    }

    // ── the arming handoff ──

    @Test
    fun `a session end hands the loop to the server-liveness baseline and back`() {
        watchdog.setEnabled(true)
        serverRunning.value = true
        probeAnswer = true
        framesFlowing = true

        watchdog.startMonitoring()
        runBlocking { assertFalse(watchdog.evaluateOnce().serverOnlyMode) }

        // Session end with the server up: the loop does NOT go dark — the
        // liveness baseline owns it now.
        watchdog.stopMonitoring()
        assertTrue(watchdog.isMonitoring)
        runBlocking { assertTrue(watchdog.evaluateOnce().serverOnlyMode) }

        // A new session takes the loop back: the session's full analysis
        // strictly covers the baseline.
        watchdog.startMonitoring()
        assertTrue(watchdog.isMonitoring)
        runBlocking { assertFalse(watchdog.evaluateOnce().serverOnlyMode) }

        // And the server seam's own stop is what finally retires it.
        watchdog.stopMonitoring() // hands to the baseline again
        watchdog.stopServerLivenessWatch()
        assertFalse(watchdog.isMonitoring)
        assertEquals(WatchdogPolicy.WatchdogStatus.IDLE, watchdog.state.value.status)
    }

    // ── frame-stall escalation ──

    @Test
    fun `a stalled frame counter fires the policy recover tier through the lever, then verifies`() {
        watchdog.setEnabled(true)
        serverRunning.value = true
        probeAnswer = true
        liveStreaming = true
        // framesFlowing stays false: the camera-frame counter freezes at the
        // arming baseline, the headless-stall shape.

        watchdog.startMonitoring() // the stall baselines origin at t=0 (count 1)

        // Two healthy intervals — the 15s stall window is not yet met.
        advance(checkIntervalMs, 2)
        advance(checkIntervalMs, 3)

        // Third tick: the window (t=15s vs origin 0) closes with the counter
        // unmoved — FRAME_STALL, the ladder's SOFT tier, RECOVERING.
        advance(checkIntervalMs, 4)
        eventually {
            assertEquals(WatchdogPolicy.WatchdogStatus.RECOVERING, watchdog.state.value.status)
            assertEquals("Frame delivery stalled (no frames for 15s)", watchdog.state.value.lastFailureReason)
        }

        // The backoff, then the injected lever.
        advance(WatchdogPolicy.backoffMs(1), 4)
        eventually { assertEquals(listOf("SOFT"), recoverCalls) }

        // SOFT verification: the policy's delay, then its observation window
        // across which the lever-made frames must (and do) advance. The
        // success path re-reads RTSP health to re-origin the baselines (+1).
        advance(WatchdogPolicy.RECOVERY_VERIFICATION_DELAY_MS, 4)
        advance(WatchdogPolicy.RECOVERY_VERIFICATION_WINDOW_MS, 5)
        eventually {
            assertEquals(1, watchdog.state.value.totalRecoveries)
            assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, watchdog.state.value.status)
            // The recovery-success reset keeps the last failure visible.
            assertEquals("Frame delivery stalled (no frames for 15s)", watchdog.state.value.lastFailureReason)
        }

        // The next clean check confirms the recovery held: the ladder stays
        // at the bottom (no relapse, no second attempt). The clean check
        // itself does not clear the exposed reason — with the failure count
        // already zeroed by the recovery reset it takes the CONTINUE branch,
        // so the stale reason rides along until the next failure cycle.
        advance(checkIntervalMs, 6)
        assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, watchdog.state.value.status)
        assertEquals("Frame delivery stalled (no frames for 15s)", watchdog.state.value.lastFailureReason)
        assertEquals(listOf("SOFT"), recoverCalls)
    }

    // ── evaluateOnce parity ──

    @Test
    fun `evaluateOnce renders the same verdict the loop tick acts on`() {
        watchdog.setEnabled(true)
        recoverResult = false // the lever fails here; the tick lands in COOLDOWN
        // Camera in error, server down: the camera branch fires first in the
        // session mode, and the skipped probe keeps the read on-thread.
        cameraState.value = CameraState.Error("sensor dead")

        watchdog.startMonitoring()

        // One tick: the loop records the failure (the message rendering the
        // snapshot's own cameraErrorMessage) and escalates to the ladder's
        // first tier.
        advance(checkIntervalMs, 2)
        advance(WatchdogPolicy.backoffMs(1), 2)
        eventually {
            assertEquals("Camera error: sensor dead", watchdog.state.value.lastFailureReason)
            assertEquals(listOf("SOFT"), recoverCalls)
            assertEquals(WatchdogPolicy.WatchdogStatus.COOLDOWN, watchdog.state.value.status)
        }

        // The one-shot observer over the SAME facts: the same verdict the
        // tick just acted on, the same mode (session, not server-only) —
        // one collectSnapshot pass, no drift between the two observers.
        val verdict = runBlocking { watchdog.evaluateOnce() }
        assertEquals(WatchdogPolicy.StallReason.CAMERA_ERROR, verdict.stall)
        assertFalse(verdict.serverOnlyMode)
    }

    // ── the enabled=false gating ──

    @Test
    fun `a disabled watchdog refuses the session arm but still runs the server-liveness baseline`() {
        // The user-facing setting is off (the default): session arming
        // refused outright.
        watchdog.startMonitoring()
        assertFalse(watchdog.isMonitoring)
        assertEquals(0, rtspHealthCalls.get())

        // The server-liveness baseline does not answer to the setting.
        watchdog.startServerLivenessWatch()
        assertTrue(watchdog.isMonitoring)
        assertEquals(1, rtspHealthCalls.get()) // the arming read

        // It keeps ticking with the setting off — and publishes MONITORING:
        // the status is about the loop, the flag about the session analysis.
        advance(checkIntervalMs, 2)
        advance(checkIntervalMs, 3)
        assertFalse(watchdog.state.value.enabled)
        assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, watchdog.state.value.status)
    }

    @Test
    fun `turning the setting off mid-session hands the loop to the server-liveness baseline`() {
        watchdog.setEnabled(true)
        serverRunning.value = true
        probeAnswer = true
        framesFlowing = true
        watchdog.startMonitoring() // count 1

        advance(checkIntervalMs, 2) // the session tick ran

        // The user flips the setting off: the session-armed loop is torn
        // down — and the surviving server keeps the loop as the baseline
        // (the handoff re-arm's read takes the count to 3).
        watchdog.setEnabled(false)
        assertTrue(watchdog.isMonitoring)
        assertEquals(3, rtspHealthCalls.get())

        // The baseline keeps ticking with the setting off.
        advance(checkIntervalMs, 4)
        advance(checkIntervalMs, 5)
        assertFalse(watchdog.state.value.enabled)
        assertEquals(WatchdogPolicy.WatchdogStatus.MONITORING, watchdog.state.value.status)
        runBlocking { assertTrue(watchdog.evaluateOnce().serverOnlyMode) }
    }
}

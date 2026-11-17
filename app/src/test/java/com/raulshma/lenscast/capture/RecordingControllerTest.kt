package com.raulshma.lenscast.capture

import android.app.Application
import android.content.Context
import android.content.Intent
import com.raulshma.lenscast.capture.model.ContinuousRecordingPolicy
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingTrigger
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * First event-sequence pass over the REAL [RecordingController] — the repo's
 * model deep module, pinned here through its existing public interface
 * (start / stop / cancelSchedule / the `RecordingState` flow / the
 * `onService*` report callbacks). No production seam was opened for this
 * suite: the controller is constructed exactly as production constructs it
 * (real context, an injected scope), the service is never started —
 * Robolectric's shadow application records every intent the controller
 * queues for [RecordingService], and the test plays the service by calling
 * the `onService*` report callbacks in the order the real service would.
 *
 * Pinned CURRENT behavior, one behavior per test:
 * - start/stop choreography: the start intent carries the config JSON
 *   verbatim; the state flow is written by service reports alone (start and
 *   stop make no optimistic writes); stop is idempotent and every stop
 *   queues its own stop intent — even straight over Idle;
 * - delay-until scheduling: a future start goes `Scheduled` with the
 *   trigger-folded config ([RecordingTriggerPolicy] provenance fold), the
 *   delayed job fires the folded config, and cancel / supersede / stop all
 *   retire the pending schedule;
 * - the bounded-recording cycle: a config with `durationSeconds` auto-stops
 *   through a stop intent after its duration, holds the state with the
 *   service until the stop report lands (`Recording` → `finalizing` →
 *   `Idle`), re-arms the repeat on every confirmed start, and re-starts the
 *   same config once the service has reported back to `Idle`;
 * - the epoch/lock dance through the surface: a user stop or a fresh start
 *   kills the armed cycle so no stale auto-stop or repeat fires behind the
 *   new round; a late stop report from the previous round lands as `Idle`
 *   (reports are the only state writers and none is epoch-checked — the
 *   correctness rests on the service's serialized `onStartCommand` order,
 *   and the two "stale report" pins document exactly that boundary,
 *   including the out-of-order clobber pinned-not-fixed);
 * - service death / error semantics: a stop report with nothing pending
 *   resets to `Idle` and tears the armed cycle down;
 * - the [ContinuousRecordingController] interplay: the loop arms, is never
 *   double-started over a live recording, tears down on disable, and never
 *   overrides a foreign stop — all through the same public surface. The
 *   loop's store inputs are faked at its constructor seam (see
 *   [armContinuousLoop] for why the real store cannot be touched here).
 *
 * Deliberately NOT covered here (needs a real foreground service): the
 * service's own lifecycle and foreground vs non-foreground start dispatch
 * (the Robolectric shadow folds `startForegroundService` into
 * `startService`, so the `foreground` flag is not observable). Sub-second
 * race interleaving lives in the pure RecordingDurationPolicy verdicts,
 * already event-sequence-tested — except the user-stop suppression tail,
 * which IS covered here through the loop's injected `suppressionMs`/`graceMs`
 * windows (the rearm job's coroutine delay is the injected suppression
 * length, so the production minute-scale tail is tested at a 4 s scale).
 *
 * Timing is VIRTUAL end to end. The controller and the loop share one
 * [TestScope]: the auto-stop/repeat/schedule delays and the loop's grace
 * checks and suppression rearm jobs all wait on the same virtual clock,
 * moved only by the test's explicit [advance] calls. A wait is an advance;
 * a "never fires" pin is proven by advancing past every armed would-be
 * timing ([settle]) — exhaustive, instant, and load-immune (a resumed
 * coroutine cannot lag: the clock only moves when the test moves it). The
 * one real-clock input left is the service report stamps — they ride
 * through the controller's duration math untouched (a real stamp against a
 * virtual delay computes a positive remain; the stamps themselves are never
 * compared to the loop's virtual verdicts, which read
 * `scheduler.currentTime` through the loop's `nowMs` seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingControllerTest {

    private lateinit var context: Context
    private lateinit var appShadow: ShadowApplication
    private lateinit var controller: RecordingController

    /**
     * The one virtual clock for this test: the controller's delayed jobs
     * (schedule, auto-stop, repeat) and the loop's (grace check, rearm)
     * all queue on this scope's scheduler. The test drives them with
     * [advance] / [tick]; nothing runs between calls.
     */
    private lateinit var testScope: TestScope
    private lateinit var scheduler: TestCoroutineScheduler

    /**
     * Every intent the controller has queued for the service so far in this
     * test, in queue order. Drained from the shadow's queue (popped one
     * synchronized element at a time — this test thread is the only
     * remover, so draining is race-free against the controller's scope
     * threads appending concurrently).
     */
    private val drained = mutableListOf<Intent>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        appShadow = Shadows.shadowOf(context as Application)
        testScope = TestScope()
        scheduler = testScope.testScheduler
        controller = RecordingController(context, testScope)
        drained.clear()
    }

    @After
    fun tearDown() {
        // Dead loops/jobs cannot fire into a later test's queue: the shared
        // scope's cancellation kills the controller's armed cycles and every
        // loop this test armed (Robolectric records a started service
        // against whatever test is current, so a survivor would pollute the
        // next test's intent counts).
        testScope.cancel()
    }

    // ── start: intent construction + service-confirmed state ──

    @Test
    fun `an immediate start queues the start intent with the config and reports Recording exactly as the service confirmed`() {
        val config = RecordingConfig(durationSeconds = 0, repeatIntervalSeconds = 0)

        controller.start(config)

        // The start intent is queued synchronously: the explicit component,
        // the START action, and the config JSON that round-trips byte-equal
        // (the wire format the service decodes back). No schedule fold: an
        // immediate start keeps its MANUAL provenance.
        val start = awaitServiceIntents(RecordingService.ACTION_START, 1, "the start intent was never queued").single()
        assertEquals(RecordingService::class.java.name, start.component?.className)
        assertEquals(config, decodeConfig(start))
        // The controller never guesses live state: start() alone writes
        // nothing — the state stays Idle until the service confirms.
        assertEquals(RecordingState.Idle, controller.state.value)

        // The report lands verbatim: the reported stamp and config are the
        // state, no controller-side reinterpretation.
        val reportedAt = 1_700_000_000_000L
        controller.onServiceStarted(reportedAt, config)
        assertEquals(
            RecordingState.Recording(startedAtMs = reportedAt, config = config, finalizing = false),
            controller.state.value,
        )
        // The derived isRecording flow follows the state (Eagerly-shared on
        // the virtual scheduler — one tick materializes the share).
        tick()
        assertTrue("isRecording never flipped true", controller.isRecording.value)
    }

    // ── delay-until scheduling ──

    @Test
    fun `a start in the future goes Scheduled with the schedule-folded config and fires the folded start intent when the delay elapses`() {
        val config = RecordingConfig() // MANUAL provenance
        val startAt = System.currentTimeMillis() + SCHEDULE_DELAY_MS

        controller.start(config, startAt)

        // The Scheduled state is written synchronously and carries the
        // provenance fold: a MANUAL config reaching the scheduled path is
        // stamped SCHEDULED from here on (the state and the fired intent
        // both hold the folded config — the service never guesses).
        assertEquals(
            RecordingState.Scheduled(startAt, config.copy(trigger = RecordingTrigger.SCHEDULED)),
            controller.state.value,
        )
        // Nothing has been launched yet — the service is not running before
        // the delay elapses.
        pumpStartedServices()
        assertTrue("a scheduled start must not queue anything early", drained.isEmpty())

        // The schedule job's real-clock gap (startAt minus System.currentTime-
        // Milliseconds, a few microseconds when scheduled) is a ~SCHEDULE_DELAY_MS
        // virtual delay: advancing past it fires.
        advance(SCHEDULE_DELAY_MS + SCHEDULE_ADVANCE_MARGIN_MS)
        val start = awaitServiceIntents(RecordingService.ACTION_START, 1, "the scheduled start never fired").single()
        assertEquals(RecordingService::class.java.name, start.component?.className)
        // The fired intent carries the folded config too.
        assertEquals(RecordingTrigger.SCHEDULED, decodeConfig(start)?.trigger)
        // Still Scheduled — only the service's confirmation promotes it.
        assertEquals(
            RecordingState.Scheduled(startAt, config.copy(trigger = RecordingTrigger.SCHEDULED)),
            controller.state.value,
        )
        controller.onServiceStarted(System.currentTimeMillis(), decodeConfig(start))
        assertTrue(controller.state.value is RecordingState.Recording)
    }

    @Test
    fun `cancelSchedule returns a scheduled start to Idle and the delayed job never fires`() {
        controller.start(RecordingConfig(), System.currentTimeMillis() + SCHEDULE_DELAY_MS)
        assertEquals(RecordingState.Scheduled::class, controller.state.value::class)

        controller.cancelSchedule()

        // Straight back to Idle, and — the load-bearing half — the delayed
        // job was cancelled, not merely masked: advancing past the fire time
        // (and the settle's generous tail) queues no start intent.
        assertEquals(RecordingState.Idle, controller.state.value)
        settle("no start intent may fire after cancelSchedule — the job was cancelled, not masked")
        assertEquals(0, serviceIntents(RecordingService.ACTION_START).size)
    }

    @Test
    fun `a new start supersedes a pending schedule without firing the old one`() {
        val first = RecordingConfig()
        val second = RecordingConfig(durationSeconds = 5)
        controller.start(first, System.currentTimeMillis() + SCHEDULE_DELAY_MS)

        // A pending schedule is always superseded: the state is the new
        // schedule (new stamp, new folded config) from this call on.
        val secondStartAt = System.currentTimeMillis() + 60_000 // far past this test's lifetime
        controller.start(second, secondStartAt)
        assertEquals(
            RecordingState.Scheduled(secondStartAt, second.copy(trigger = RecordingTrigger.SCHEDULED)),
            controller.state.value,
        )

        // The first schedule's fire time passes with the state already
        // superseded: its job must never launch the old start. The settle
        // also stays far short of the SECOND schedule's 60 s offset — a
        // fired start here could only be the old one.
        settle("the superseded schedule must never fire")
        assertEquals(0, serviceIntents(RecordingService.ACTION_START).size)

        controller.cancelSchedule()
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `stop cancels a pending schedule back to Idle and still queues the stop intent`() {
        controller.start(RecordingConfig(), System.currentTimeMillis() + SCHEDULE_DELAY_MS)
        assertEquals(RecordingState.Scheduled::class, controller.state.value::class)

        controller.stop()

        // The schedule is cancelled synchronously... and the stop intent is
        // sent anyway (the always-send rule: the delayed job may already be
        // past its cancellation point, so the queued STOP lands after the
        // START in the service's serialized onStartCommand and cannot wedge).
        assertEquals(RecordingState.Idle, controller.state.value)
        val stop = awaitServiceIntents(RecordingService.ACTION_STOP, 1, "the stop intent was never queued").single()
        assertEquals(RecordingService::class.java.name, stop.component?.className)
        assertNull("a stop intent carries no config", stop.getStringExtra(RecordingService.EXTRA_CONFIG))

        // And the cancelled scheduled job never launches the start it held.
        settle("the cancelled schedule must never fire")
        assertEquals(0, serviceIntents(RecordingService.ACTION_START).size)
    }

    // ── stop: finalizing ladder + idempotency ──

    @Test
    fun `stop leaves the state to the service - Recording holds until Finalizing and Finalizing until Stopped`() {
        controller.start(RecordingConfig())
        awaitServiceIntents(RecordingService.ACTION_START, 1, "the start intent was never queued")
        val startedAt = System.currentTimeMillis()
        controller.onServiceStarted(startedAt, RecordingConfig())

        // stop() itself writes nothing: the state stays Recording while the
        // stop intent travels and the service starts draining.
        controller.stop()
        assertEquals(
            RecordingState.Recording(startedAt, RecordingConfig()),
            controller.state.value,
        )

        // The service reports the drain: Finalizing is a flag on the live
        // Recording, not a separate state.
        controller.onServiceFinalizing()
        assertTrue(
            "the recording must be finalizing after the service's report",
            (controller.state.value as RecordingState.Recording).finalizing,
        )
        assertEquals(startedAt, (controller.state.value as RecordingState.Recording).startedAtMs)

        // Only the stopped report lands Idle.
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
        tick()
        assertFalse("isRecording never fell back to false", controller.isRecording.value)
    }

    @Test
    fun `stop is idempotent - every stop queues its own stop intent and a stop report over Idle stays Idle`() {
        // The always-send rule is visible at rest: a stop with nothing live
        // and nothing scheduled still queues the stop intent, so a stop
        // aimed at a dead service can never wedge the state.
        controller.stop()
        assertEquals(1, serviceIntents(RecordingService.ACTION_STOP).size)
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        controller.stop()
        assertEquals(2, serviceIntents(RecordingService.ACTION_STOP).size)
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    // ── the bounded-recording cycle (duration policy wiring) ──

    @Test
    fun `a bounded recording auto-stops through a stop intent and only the service report lands it Idle`() {
        val config = RecordingConfig(durationSeconds = 1, repeatIntervalSeconds = 0)
        controller.start(config)
        controller.onServiceStarted(System.currentTimeMillis(), config) // arms the duration job

        // The auto-stop fires through a STOP service intent one (virtual)
        // second later — the controller's own duration policy, no client
        // involvement.
        advance(DURATION_CYCLE_MS)

        // No optimistic write: the state is still live Recording while the
        // service drains — only its report lands Idle.
        assertEquals(
            RecordingState.Recording::class,
            controller.state.value::class,
        )
        assertFalse((controller.state.value as RecordingState.Recording).finalizing)

        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun `the auto-stop re-arms the repeat after its own stop report and re-starts the same config once Idle`() {
        val config = RecordingConfig(durationSeconds = 1, repeatIntervalSeconds = 1)
        controller.start(config)
        controller.onServiceStarted(System.currentTimeMillis(), config)

        // Cycle 1: the duration elapses, the policy's own stop intent goes
        // out, the drain is reported, and the armed repeat survives the
        // AUTO stop report (only its own stop survives it). One advance per
        // phase: the auto-stop fires, THE REPORT LANDS, then the gap
        // elapses — the same order the real service's serialized queue
        // produces, and the order the repeat's stateIsIdle re-check needs.
        advance(DURATION_CYCLE_MS)
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "auto-stop #1 never fired")
        controller.onServiceFinalizing()
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // The repeat waits out its gap and re-starts — through the same
        // start path, with the same config it armed on.
        advance(DURATION_CYCLE_MS)
        val restarts = awaitServiceIntents(RecordingService.ACTION_START, 2, "the repeat re-start never fired")
        assertEquals(config, decodeConfig(restarts.last()))
        controller.onServiceStarted(System.currentTimeMillis(), decodeConfig(restarts.last()))

        // Armed again on every confirmed start: cycle 2 auto-stops the same
        // way, and this time the user stop lands inside the freshly armed
        // repeat gap — the one USER cause no armed step survives — so the
        // chain ends here for good.
        advance(DURATION_CYCLE_MS)
        awaitServiceIntents(RecordingService.ACTION_STOP, 2, "auto-stop #2 never fired")
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 3, "the user stop intent was never queued")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
        settle("no third segment may fire behind the user stop")
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size) // the two cycle starts, nothing new
    }

    @Test
    fun `an unexplained stop report during the repeat gap kills the armed restart (service death semantics)`() {
        val config = RecordingConfig(durationSeconds = 1, repeatIntervalSeconds = 1)
        controller.start(config)
        controller.onServiceStarted(System.currentTimeMillis(), config)

        // The cycle's own auto-stop and its report: the repeat survives this
        // one and waits out the gap. One advance per phase — the auto-stop
        // fires, the report lands (Idle), and the repeat stays armed behind
        // the AUTO cause.
        advance(DURATION_CYCLE_MS)
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "auto-stop never fired")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // A second stop report with nothing policy-pending — a service death
        // or an error landing during the gap: SERVICE_REPORTED must not
        // leave a restart armed behind it.
        controller.onServiceStopped()

        // The settle advances past the armed restart's whole would-be fire:
        // nothing appears.
        settle("a service-reported stop must cancel the armed restart")
        assertEquals(RecordingState.Idle, controller.state.value)
        assertEquals(1, serviceIntents(RecordingService.ACTION_START).size) // only the original start
        assertEquals(1, serviceIntents(RecordingService.ACTION_STOP).size) // only the auto-stop
    }

    // ── the epoch/lock dance through the public surface ──

    @Test
    fun `a late stop report from the previous round lands as Idle and cannot re-arm the old cycle behind the new round`() {
        val stale = RecordingConfig(durationSeconds = 10, repeatIntervalSeconds = 1)
        controller.start(stale)
        controller.onServiceStarted(System.currentTimeMillis(), stale) // stale round armed (10 s countdown)

        controller.stop() // epoch 1 → 2: the stale round's cycle is torn down here
        val fresh = RecordingConfig(durationSeconds = 0)
        controller.start(fresh) // epoch 2 → 3: start(B) makes no optimistic write...
        assertEquals(
            "start() must not optimistically write the state — still the stale round's Recording",
            RecordingState.Recording::class,
            controller.state.value::class,
        )

        // The late service report for the stale round's queued STOP: reports
        // are the only state writers and none is epoch-checked, so this is
        // an honest transient Idle even though a start is already in flight
        // — pinned, not fixed (the service's serialized onStartCommand
        // always delivers this STOP before the new START is processed).
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // The heart of the pin: the stale round's armed cycle is dead — its
        // 10 s countdown was cancelled by the user stop, and neither an
        // auto-stop nor a repeat of the OLD config may appear behind the new
        // round.
        settle("the stale round's cycle must never fire behind the new round")
        assertEquals(1, serviceIntents(RecordingService.ACTION_STOP).size) // only the user stop
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size) // stale + fresh starts only

        // The new round's confirmation wins and holds.
        val freshStartedAt = System.currentTimeMillis()
        controller.onServiceStarted(freshStartedAt, fresh)
        assertEquals(
            RecordingState.Recording(freshStartedAt, fresh),
            controller.state.value,
        )
    }

    @Test
    fun `a stale stop report while a new schedule is pending kills the pending schedule too (pinned)`() {
        controller.start(RecordingConfig(durationSeconds = 10), System.currentTimeMillis() + SCHEDULE_DELAY_MS)
        assertEquals(RecordingState.Scheduled::class, controller.state.value::class)

        // A stop report from a previous round arriving while a new schedule
        // is pending: onServiceStopped cancels the scheduled job
        // unconditionally (it must never fire a start behind a reported
        // stop) and lands Idle. Pinned current semantics — reachable when a
        // queued STOP is processed between a schedule firing and the next
        // schedule being set.
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        settle("the pending schedule must not survive a reported stop")
        assertEquals(0, serviceIntents(RecordingService.ACTION_START).size)
    }

    @Test
    fun `a stale stop report landing after the new round's start report resets the state (pinned, unreachable via the serialized service queue)`() {
        val stale = RecordingConfig(durationSeconds = 10, repeatIntervalSeconds = 1)
        val fresh = RecordingConfig(durationSeconds = 10, repeatIntervalSeconds = 1)
        controller.start(stale)
        controller.onServiceStarted(System.currentTimeMillis(), stale)
        controller.stop()
        controller.start(fresh)
        controller.onServiceStarted(System.currentTimeMillis(), fresh) // the new round reports first

        // The adversarial order the controller itself does not guard: a
        // stale STOP report processed after the new round's START report.
        // The controller trusts the service — the epochs guard the policy
        // cycle, not the reports — so this clobbers the state to Idle and
        // tears the fresh round's armed cycle down with it. Pinned NOT
        // fixed: the real service's serialized onStartCommand cannot
        // deliver this order (the STOP was queued before the START), and a
        // report guard here would be a second truth fighting the service.
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // The fresh round's armed cycle went down with the report — which is
        // exactly why the queue ordering is load-bearing.
        settle("nothing may fire after the stale report")
        assertEquals(1, serviceIntents(RecordingService.ACTION_STOP).size) // only the user stop
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size) // stale + fresh starts only
    }

    // ── service death / error semantics ──

    @Test
    fun `a stop report with nothing pending resets Idle and tears the armed cycle down, and Finalizing over Idle is a no-op`() {
        val config = RecordingConfig(durationSeconds = 10, repeatIntervalSeconds = 1)
        controller.start(config)
        controller.onServiceStarted(System.currentTimeMillis(), config) // armed, 10 s countdown

        // The service dies (or errors) without a stop request: the report is
        // SERVICE_REPORTED — Idle, and the armed cycle goes down with the
        // report so no auto-stop or repeat fires behind a dead service.
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // A Finalizing report with no live recording changes nothing.
        controller.onServiceFinalizing()
        assertEquals(RecordingState.Idle, controller.state.value)

        settle("the armed cycle must not outlive the death report")
        assertEquals(1, serviceIntents(RecordingService.ACTION_START).size) // only the original start
        assertEquals(0, serviceIntents(RecordingService.ACTION_STOP).size) // no auto-stop, ever
    }

    @Test
    fun `a null-config service report records an unlimited recording`() {
        // The service reports a null config when the intent JSON fails to
        // decode: the state carries it verbatim and nothing arms (an
        // unlimited recording has no duration policy).
        val reportedAt = 1_700_000_000_000L
        controller.onServiceStarted(reportedAt, null)
        assertEquals(
            RecordingState.Recording(reportedAt, null, finalizing = false),
            controller.state.value,
        )

        settle("a null config must arm no duration policy")
        assertEquals(0, serviceIntents(RecordingService.ACTION_STOP).size)
    }

    // ── the ContinuousRecordingController interplay (a second client on the same surface) ──

    @Test
    fun `the continuous loop arms through the same public surface, is never double-started, and its disable-time teardown stops like any client's`() {
        val loop = armContinuousLoop()

        // The loop's first segment is the segment config on the wire: one
        // smallest-persisted-segment bounded (the store's saved floor is
        // SEGMENT_MINUTES minutes — see armContinuousLoop), repeat-chained
        // (REPEAT_GAP_SECONDS arms the policy machinery), CONTINUOUS_LOOP
        // provenance.
        assertEquals(SEGMENT_DURATION_SECONDS, loop.segment.durationSeconds)
        assertEquals(ContinuousRecordingPolicy.REPEAT_GAP_SECONDS, loop.segment.repeatIntervalSeconds)
        assertEquals(RecordingTrigger.CONTINUOUS_LOOP, loop.segment.trigger)
        assertEquals(loop.inputs.audioEnabled.value, loop.segment.includeAudio)
        controller.onServiceStarted(System.currentTimeMillis(), loop.segment)
        assertTrue(controller.state.value is RecordingState.Recording)

        // Any live recording IS the loop: the re-evaluation over the
        // Recording state must maintain it, never double-start.
        tick()
        settle("the loop must never double-start over a live recording")
        assertEquals(1, serviceIntents(RecordingService.ACTION_START).size)

        // Disable the feature: the loop's own teardown goes through the same
        // stop() surface a screen would — only a loop this client armed.
        loop.inputs.enabled.value = false
        tick()
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "the disable-time teardown never stopped the loop")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // Feature off and loop down: no re-arm behind the teardown.
        settle("a disabled loop must stay down")
        assertEquals(1, serviceIntents(RecordingService.ACTION_START).size)
    }

    @Test
    fun `a foreign stop of the continuous loop is honored - the chain break declares past the grace window and nothing re-arms`() {
        val loop = armContinuousLoop()
        controller.onServiceStarted(System.currentTimeMillis(), loop.segment)

        // A stop by another hand — a screen, the Web API — the same public
        // surface: the loop loses its segment and must not win it back.
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "the foreign stop was never queued")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // The controller counts the Idle through the injected grace window,
        // declares the break, and suppresses re-arming for the injected
        // cooldown: the rearm job sits TEST_SUPPRESSION_MS out. The settle
        // advances past the grace declaration and sits strictly inside that
        // window — a re-armed START here could only be a suppression miss.
        tick()
        advance(GRACE_DECLARE_ADVANCE_MS)
        settle("the loop must not re-start over a foreign stop")
        assertEquals(RecordingState.Idle, controller.state.value)
        assertEquals(1, serviceIntents(RecordingService.ACTION_START).size) // only the original arm
    }

    @Test
    fun `a second chain break inside the suppression window retargets the rearm - the loop re-arms past the extended deadline`() {
        // The stranding wedge: break #1 arms a rearm job at the injected
        // suppression length. A foreign recording starts inside the window
        // and its stop declares break #2, which EXTENDS the suppression past
        // break #1's deadline. The retargeted job then fires exactly at the
        // extended deadline: without the retarget, the stale job's fire
        // would fail canArm and return without rescheduling — the loop sits
        // Idle with no pending job and no flow emission ever coming,
        // silently dead until a setting changes. The pin: past the EXTENDED
        // deadline, exactly one re-arm START fires.
        val loop = armContinuousLoop()
        // The segment's start report carries a REAL stamp: the controller's
        // duration math is real-clock, and the report stamp flows into the
        // armed 300 s duration job (a positive virtual delay — it never
        // fires in this test, and the user stop below cancels it). The
        // loop's verdicts read the virtual clock through its `nowMs` seam.
        controller.onServiceStarted(System.currentTimeMillis(), loop.segment)

        // Break #1: a foreign stop — the grace check declares it past the
        // injected grace mark.
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "the first chain break was never queued")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
        tick() // the collector stamps the Idle; the grace check is scheduled
        advance(GRACE_DECLARE_ADVANCE_MS)
        // Break #1 declared (suppression until virtual now + TEST_SUPPRESSION_MS);
        // the rearm job is armed with that window as its (virtual) delay.

        // A foreign recording starts inside the suppression window: the loop
        // adopts it (armed, not armed-by-us) and leaves the pending rearm
        // targeting break #1's OLD deadline.
        val foreign = RecordingConfig(durationSeconds = 0, repeatIntervalSeconds = 0)
        controller.start(foreign)
        awaitServiceIntents(RecordingService.ACTION_START, 2, "the foreign start was never queued")
        controller.onServiceStarted(System.currentTimeMillis(), foreign)
        tick() // the loop adopts the foreign recording

        // ...and stops inside the window: the second chain break extends the
        // suppression past break #1's deadline (the same grace dance, and
        // scheduleRearm retargets the pending job onto the extended deadline
        // as it declares).
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 2, "the foreign stop was never queued")
        controller.onServiceStopped()
        tick()
        advance(GRACE_DECLARE_ADVANCE_MS)

        // Through the retargeted job's fire moment (armed at break #2's
        // declaration with the extended window as its delay): on the
        // stranding shape its fire would fail canArm and schedule nothing,
        // stranding the loop Idle.
        advance(TEST_SUPPRESSION_MS)

        // Exactly one re-arm fired — the deterministic clock makes the count
        // a direct read, no pacing or budget needed.
        val restarts = awaitServiceIntents(
            RecordingService.ACTION_START,
            3,
            "the loop never re-armed past the extended suppression deadline",
        )
        assertEquals(loop.segment, decodeConfig(restarts.last()))

        // The re-arm is the only new activity: no further starts, no stops.
        settle("the re-armed loop must not churn behind the restart")
        assertEquals(3, serviceIntents(RecordingService.ACTION_START).size)
        assertEquals(2, serviceIntents(RecordingService.ACTION_STOP).size)
    }

    @Test
    fun `a foreign takeover keeps the loop's stop-ours verdict - the disable-time teardown stops the foreign recording too`() {
        val loop = armContinuousLoop()
        controller.onServiceStarted(System.currentTimeMillis(), loop.segment)

        // A foreign recording takes over the live segment: the loop adopts it
        // while KEEPING its own ours verdict (the Recording branch's `||`
        // deliberately preserves ours over a config mismatch) — the takeover
        // must not launder the loop's stop into a foreign one.
        val foreign = RecordingConfig(durationSeconds = 0, repeatIntervalSeconds = 0)
        controller.start(foreign)
        awaitServiceIntents(RecordingService.ACTION_START, 2, "the foreign start was never queued")
        controller.onServiceStarted(System.currentTimeMillis(), foreign)
        tick() // the loop adopts the foreign recording
        settle("the loop must adopt the foreign recording before the flip")
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size) // arm + foreign, nothing new

        // Flip the toggle off: the teardown must still stop the segment —
        // now the foreign recording — because ours survived the takeover.
        loop.inputs.enabled.value = false
        tick()
        awaitServiceIntents(
            RecordingService.ACTION_STOP,
            1,
            "the disable-time teardown never stopped the foreign recording",
        )
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)

        // Feature off and loop down: no re-arm behind the teardown.
        settle("a disabled loop must stay down")
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size)
        assertEquals(1, serviceIntents(RecordingService.ACTION_STOP).size)
    }

    @Test
    fun `a user start inside the suppression window leaves the pending rearm a quiet no-op - the chain still re-arms past the extended deadline`() {
        // The rearmJob-outlives-Suppression shape: the break #1 rearm job
        // fires while a USER recording is live inside the window. The loop
        // adopted that recording (Maintaining, not ours) and the job stays
        // pending beside the state — its fire must be the deliberate quiet
        // no-op: no start over the live segment, no reschedule behind it,
        // the user recording untouched. The user recording then stops, break
        // #2 extends the window, and exactly one re-arm fires past the
        // extended deadline (the same accounting as the wedge pin above).
        val loop = armContinuousLoop()
        // Real report stamp (see the wedge pin): the loop's verdicts read
        // the virtual clock through the `nowMs` seam.
        controller.onServiceStarted(System.currentTimeMillis(), loop.segment)

        // Break #1: a foreign stop — the grace check declares it past the
        // injected grace mark. The stop also cancels the just-armed 300 s
        // duration job.
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "the first chain break was never queued")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
        tick()
        advance(GRACE_DECLARE_ADVANCE_MS)
        // Break #1 declared (suppression until virtual now + TEST_SUPPRESSION_MS);
        // the rearm job is armed with that window as its (virtual) delay.

        // A user recording starts inside the window: the loop adopts it
        // (Maintaining, not ours) and the pending rearm stays targeting
        // break #1's deadline.
        val user = RecordingConfig(durationSeconds = 0, repeatIntervalSeconds = 0)
        controller.start(user)
        awaitServiceIntents(RecordingService.ACTION_START, 2, "the user start was never queued")
        controller.onServiceStarted(System.currentTimeMillis(), user)
        tick()
        assertTrue(controller.state.value is RecordingState.Recording)

        // Advance exactly through the pending rearm's fire moment while the
        // user recording is live: quiet no-op — nothing starts over the live
        // segment, nothing is disturbed. The deterministic clock fires the
        // job exactly here; there is no race to pace away.
        advance(TEST_SUPPRESSION_MS)
        assertTrue(
            "the rearm's fire must not disturb the live user recording",
            controller.state.value is RecordingState.Recording,
        )
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size) // arm + user start only

        // The user recording stops: the paced grace dance declares break #2,
        // which extends the suppression past break #1's (already-consumed)
        // deadline.
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 2, "the user stop was never queued")
        controller.onServiceStopped()
        tick()
        advance(GRACE_DECLARE_ADVANCE_MS)

        // Advance through the EXTENDED deadline: exactly one re-arm fires.
        advance(TEST_SUPPRESSION_MS)
        val restarts = awaitServiceIntents(
            RecordingService.ACTION_START,
            3,
            "the loop never re-armed past the extended suppression deadline",
        )
        assertEquals(loop.segment, decodeConfig(restarts.last()))

        // The re-arm is the only new activity: no further starts, no stops.
        settle("the re-armed loop must not churn behind the restart")
        assertEquals(3, serviceIntents(RecordingService.ACTION_START).size)
        assertEquals(2, serviceIntents(RecordingService.ACTION_STOP).size)
    }

    @Test
    fun `a disable-and-reenable inside the suppression window erases the cooldown - the loop re-arms immediately`() {
        // The disable-time Dormant write erases a still-live suppression
        // stamp: round-tripping the toggle inside the cooldown must re-arm
        // immediately, not wait the window out. On the virtual clock the
        // re-enable lands with the stamp's deadline still far in the future
        // (only the grace declaration's advance has elapsed against the
        // TEST_SUPPRESSION_MS window), so an immediate start can only be
        // the erase — waiting the window out would have taken a further
        // advance the test never makes.
        val loop = armContinuousLoop()
        // Real report stamp (see the wedge pin).
        controller.onServiceStarted(System.currentTimeMillis(), loop.segment)

        // Declare break #1 (the grace dance): Suppressing until virtual now
        // + TEST_SUPPRESSION_MS, with a rearm job pending. The stop also
        // cancels the just-armed 300 s duration job.
        controller.stop()
        awaitServiceIntents(RecordingService.ACTION_STOP, 1, "the chain break was never queued")
        controller.onServiceStopped()
        assertEquals(RecordingState.Idle, controller.state.value)
        tick()
        advance(GRACE_DECLARE_ADVANCE_MS)

        // Toggle off: the disable-time Noop path ends the idle loop — Dormant
        // erases the stamp, and the pending rearm job is cancelled at decide
        // time. The tick lets that decision land before the re-enable (the
        // two flips must not conflate into a single enabled emission).
        loop.inputs.enabled.value = false
        tick()
        loop.inputs.enabled.value = true
        tick()

        // Re-enabled with the clock still deep inside the would-be cooldown:
        // the immediate re-arm can only be the erase.
        val restarts = awaitServiceIntents(
            RecordingService.ACTION_START,
            2,
            "the loop never re-armed after the disable/enable round-trip",
        )
        assertEquals(loop.segment, decodeConfig(restarts.last()))

        // The re-arm is the only new activity: no stop (nothing was live), no
        // further starts.
        settle("the re-armed loop must not churn behind the restart")
        assertEquals(2, serviceIntents(RecordingService.ACTION_START).size)
        assertEquals(1, serviceIntents(RecordingService.ACTION_STOP).size)
    }

    // ── harness ──

    /**
     * Arms the real [ContinuousRecordingController] against this test's
     * [controller]: fakes the loop's store inputs, starts the loop, flips the
     * enable input on, and returns the decoded segment config it queued.
     * Shared by the interplay tests; the loop shares this test's [TestScope]
     * and reads the virtual clock through its `nowMs` seam
     * (`scheduler.currentTime`) — its grace checks and rearm delays advance
     * exactly when the test advances, never before.
     *
     * The store input is faked at the loop's own constructor seam, and that
     * is deliberate isolation, not a shortcut. In production exactly one loop
     * exists — hosted at the streaming manager — and it observes the real
     * SettingsDataStore, whose underlying DataStore is a classloader-wide
     * singleton under Robolectric; every test method's MainApplication
     * constructs the manager again (onCreate forces it through the settings
     * applier), so each method spins up one more real loop against the same
     * store. Flipping the persisted continuous-recording flag would arm ALL
     * of them at once — and each arms through its own stale
     * RecordingController, whose start intent Robolectric records against
     * whatever test is current (the started-service shadow resolves through
     * the global activity thread, not through the stale Application), i.e.
     * straight into this test's intent counts. No persisted flag is ever
     * touched here, so every foreign loop stays dormant and the loop, the
     * controller, and the intents under the pins all stay real.
     *
     * The segment length is [SEGMENT_MINUTES] — the store's persisted floor
     * (see the companion note); the faked input feeds exactly the value a
     * real save of that setting would decode back.
     */
    private fun armContinuousLoop(): ArmedLoop {
        val inputs = LoopInputs(
            enabled = MutableStateFlow(false),
            segmentMinutes = MutableStateFlow(SEGMENT_MINUTES),
            audioEnabled = MutableStateFlow(AUDIO_ENABLED),
        )
        val loop = ContinuousRecordingController(
            mockk {
                every { continuousRecording } returns inputs.enabled
                every { continuousSegmentMinutes } returns inputs.segmentMinutes
                every { recordingAudioEnabled } returns inputs.audioEnabled
            },
            controller,
            scope = testScope,
            nowMs = { scheduler.currentTime },
            suppressionMs = TEST_SUPPRESSION_MS,
            graceMs = TEST_GRACE_MS,
        )
        loop.start()

        inputs.enabled.value = true
        val start = awaitServiceIntents(
            RecordingService.ACTION_START,
            1,
            "the continuous loop never armed its first segment",
        ).single()
        return ArmedLoop(
            segment = checkNotNull(decodeConfig(start)) {
                "the loop's start intent carried no decodable config"
            },
            inputs = inputs,
        )
    }

    /** The config JSON the controller put on an intent, decoded the way the service decodes it. */
    private fun decodeConfig(intent: Intent): RecordingConfig? =
        RecordingConfigJson.decode(
            checkNotNull(intent.getStringExtra(RecordingService.EXTRA_CONFIG)) {
                "intent ${intent.action} carried no config extra"
            },
        )

    /** Pops everything queued so far into [drained]. Single-remover, race-free against concurrent appends. */
    private fun pumpStartedServices() {
        while (true) {
            val intent = appShadow.nextStartedService ?: break
            drained += intent
        }
    }

    /** The intents queued so far with [action], oldest first. */
    private fun serviceIntents(action: String): List<Intent> {
        pumpStartedServices()
        return drained.filter { it.action == action }
    }

    /**
     * The intents queued with [action] must have reached [count] already —
     * after the caller's synchronous calls and/or [advance]s. The virtual
     * clock is deterministic: a count that is not there now will never
     * arrive, so this fails immediately instead of polling (an await budget
     * would only hide a mis-sequenced advance).
     */
    private fun awaitServiceIntents(action: String, count: Int, message: String): List<Intent> {
        tick()
        return serviceIntents(action).also { queued ->
            check(queued.size >= count) { "$message (queued so far: ${drained.map { it.action }})" }
        }
    }

    /**
     * Runs everything the virtual clock has due right now: the loop's
     * collector reacting to a state/setting change, a just-scheduled job's
     * first suspension point, the Eagerly-shared isRecording share.
     */
    private fun tick() {
        scheduler.runCurrent()
    }

    /** Moves the shared virtual clock forward by [ms] and runs whatever comes due. */
    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /**
     * Advance past every armed would-be timing of the cycle the test just
     * cancelled or never armed. A "never fires" pin: the virtual clock
     * covers every future delay the remaining armed jobs could have, and no
     * new intent may appear.
     */
    private fun settle(reason: String) {
        advance(CYCLE_SETTLE_MS)
        // [reason] documents the guarantee at the call site; the asserts follow it.
    }

    /** The loop's three store inputs, faked per interplay test (see [armContinuousLoop]). */
    private class LoopInputs(
        val enabled: MutableStateFlow<Boolean>,
        val segmentMinutes: MutableStateFlow<Int>,
        val audioEnabled: MutableStateFlow<Boolean>,
    )

    /** An armed loop's segment config plus the inputs driving it, for the disable-time flip. */
    private data class ArmedLoop(val segment: RecordingConfig, val inputs: LoopInputs)

    private companion object {
        /** The future-start offset every scheduling test uses. */
        const val SCHEDULE_DELAY_MS = 250L

        /**
         * The scheduled-start advance covers the delay plus the few
         * microseconds of real time between the test computing `startAt`
         * and `schedule()` subtracting `System.currentTimeMillis()`.
         */
        const val SCHEDULE_ADVANCE_MARGIN_MS = 50L

        /**
         * The continuous loop's segment length, in minutes: the smallest
         * value the segment-minutes setting can persist (its saved bounds
         * are [StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES]..
         * [StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES] and the saver
         * coerces through them — an earlier draft of this suite asked the
         * store for 1 minute and the write silently landed as 5, which no
         * await could ever observe past). The faked loop input feeds this
         * value, exactly what a real save of it decodes back.
         */
        val SEGMENT_MINUTES: Int = StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES

        /** The on-wire segment duration that setting produces ([ContinuousRecordingPolicy.segmentConfig]). */
        val SEGMENT_DURATION_SECONDS: Long = SEGMENT_MINUTES.toLong() * 60L

        /** The recording-audio preference value the faked loop input carries. */
        const val AUDIO_ENABLED = true

        /**
         * The suppression window injected into the loop under test (the
         * production constant is a real 60 s): the rearm job delays on this
         * value — virtually. The manual pins advance exactly through it.
         */
        const val TEST_SUPPRESSION_MS = 4_000L

        /** The chain-break grace window injected into the loop under test. */
        const val TEST_GRACE_MS = 200L

        /**
         * The advance that declares a chain break: the collector's Idle
         * stamp plus the grace check's delay ([TEST_GRACE_MS] plus the
         * controller's check slack) plus a settling margin. Advancing this
         * far fires exactly one grace check — confirmed, since the idle
         * stamp was taken at the preceding [tick].
         */
        const val GRACE_DECLARE_ADVANCE_MS = TEST_GRACE_MS + 100L

        /**
         * Exactly ONE duration-policy step: the 1 s auto-stop of a bounded
         * recording, or the 1 s repeat gap behind it. The delay is anchored
         * at a real-clock stamp (a few microseconds under one virtual
         * second — [RecordingDurationPolicy.autoStopDelayMs]), so the
         * boundary-inclusive advance fires exactly the phase's job and
         * nothing armed behind it. Never advance two steps in a row over a
         * live cycle: the repeat anchored at the auto-stop's fire moment
         * would fire inside the same advance — before the test has played
         * the stop report — and its stateIsIdle re-check would kill it over
         * a stale Recording state. One advance, one report, one advance.
         */
        const val DURATION_CYCLE_MS = 1_000L

        /**
         * The "never fires" advance: past a 1 s duration cycle, past the
         * [SCHEDULE_DELAY_MS] schedule fire, past every cancelled would-be
         * timing the pins leave behind — and strictly short of the
         * TEST_SUPPRESSION_MS rearm mark (a settle after a break declaration
         * must stay inside the live cooldown).
         */
        const val CYCLE_SETTLE_MS = 2_000L
    }
}

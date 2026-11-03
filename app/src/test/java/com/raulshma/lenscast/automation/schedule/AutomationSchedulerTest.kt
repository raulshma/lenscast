package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.data.SettingsDataStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The scheduler shell around the pure [AutomationSchedulePolicy]: the
 * injected clock drives the local-calendar derivation, a fake runner records
 * the fired rules, and a real [AutomationFireJournal] on a temp folder pins
 * the dedup contract — fires on the minute, catch-up on the first
 * evaluation, journal-blocked refires, the master/rule toggles, the grace
 * window's STATEFUL/ONESHOT split, and per-rule failure isolation. The
 * verdict matrix runs against [AutomationScheduler.evaluate] directly; the
 * loop wiring (immediate first evaluation, settings-change restart) runs
 * through [AutomationScheduler.start] with real flows.
 */
class AutomationSchedulerTest {

    // The injected clock: tests move it and the scheduler reads it live.
    private var now: Long = 0L

    private val rulesFlow = MutableStateFlow(emptyList<AutomationRule>())
    private val enabledFlow = MutableStateFlow(false)
    private val settingsDataStore: SettingsDataStore = mockk {
        every { automationRules } returns rulesFlow
        every { automationEnabled } returns enabledFlow
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val started = mutableListOf<AutomationScheduler>()

    @Before
    fun utcClock() {
        // The policy's grace comparison anchors a rule's occurrence to the
        // virtual "local-midnight-epoch" (AutomationSchedulePolicy's
        // occurrenceTimeMs) and measures it against the real nowMs — a
        // comparison that is offset-free only on UTC. The suite pins the
        // JVM clock there so "N minutes past the minute" means N real
        // minutes on every machine.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        started.forEach { it.stop() }
        TimeZone.setDefault(null)
    }

    /** Records fired rule ids; [failIds] makes those rules' runs throw. */
    private class FakeRunner : AutomationActionRunner {
        val firedIds = mutableListOf<String>()
        val failIds = mutableSetOf<String>()
        val received = Channel<List<AutomationRule>>(Channel.UNLIMITED)

        override suspend fun run(fired: List<AutomationSchedulePolicy.FiredRule>) {
            firedIds += fired.map { it.rule.id.orEmpty() }
            received.send(fired.map { it.rule })
            if (fired.any { it.rule.id in failIds }) throw IllegalStateException("boom")
        }
    }

    // ── Time helpers: the scheduler's derivations, mirrored so the tests
    // pin the formula (a silent change on either side fails loudly). The
    // clock base is today's UTC noon — far from midnight and DST edges, so
    // +/-N-minute shifts never straddle a day boundary — built on an
    // explicit UTC calendar so field-init order (before @Before) cannot
    // skew it against the host's default zone.

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private val noon: Long = Calendar.getInstance(utc).apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun minuteOfDayAt(ms: Long): Int =
        Calendar.getInstance(utc).apply { timeInMillis = ms }.let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

    private fun journal(): AutomationFireJournal =
        AutomationFireJournal(File(tmp.root, "automation/fire_journal.json"))

    private fun rule(
        id: String? = "rule-1",
        enabled: Boolean = true,
        actions: List<AutomationActionSpec> = listOf(
            AutomationActionSpec("stream", mapOf("enabled" to "true")),
        ),
    ) = AutomationRule(
        id = id,
        name = "Rule $id",
        enabled = enabled,
        minuteOfDay = minuteOfDayAt(noon),
        actions = actions,
    )

    private fun scheduler(
        target: AutomationFireJournal,
        runner: AutomationActionRunner,
    ): AutomationScheduler = AutomationScheduler(
        settingsDataStore = settingsDataStore,
        journal = target,
        runner = runner,
        nowMs = { now },
    ).also(started::add)

    // ── Due verdicts (direct evaluate) ──

    @Test
    fun `fires at the exact scheduled minute`() = runBlocking {
        now = noon
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(listOf(rule()), enabled = true)
        assertEquals(listOf("rule-1"), runner.firedIds)
    }

    @Test
    fun `does not fire before the scheduled minute`() = runBlocking {
        now = noon - 60_000
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(listOf(rule()), enabled = true)
        assertTrue(runner.firedIds.isEmpty())
    }

    @Test
    fun `master disabled fires nothing`() = runBlocking {
        now = noon
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(listOf(rule()), enabled = false)
        assertTrue(runner.firedIds.isEmpty())
    }

    @Test
    fun `rule disabled fires nothing`() = runBlocking {
        now = noon
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(listOf(rule(enabled = false)), enabled = true)
        assertTrue(runner.firedIds.isEmpty())
    }

    @Test
    fun `catch-up fires late on the first evaluation within the one-shot grace`() = runBlocking {
        now = noon + 5 * 60_000 // inside AUTOMATION_ONESHOT_GRACE_MS (10 min)
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(
            listOf(rule(actions = listOf(AutomationActionSpec("capture_photo", emptyMap())))),
            enabled = true,
        )
        assertEquals(listOf("rule-1"), runner.firedIds)
    }

    @Test
    fun `one-shot-only rule past grace is dropped and not journaled`() = runBlocking {
        now = noon + 20 * 60_000 // far outside the grace window
        val target = journal()
        val runner = FakeRunner()
        scheduler(target, runner).evaluate(
            listOf(rule(actions = listOf(AutomationActionSpec("capture_photo", emptyMap())))),
            enabled = true,
        )
        assertTrue(runner.firedIds.isEmpty())
        assertTrue(target.load().isEmpty())
    }

    @Test
    fun `stateful-only rule past grace still fires`() = runBlocking {
        now = noon + 20 * 60_000
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(listOf(rule()), enabled = true)
        assertEquals(listOf("rule-1"), runner.firedIds)
    }

    // ── Dedup (direct evaluate + real journal) ──

    @Test
    fun `no refire while the journal key holds`() = runBlocking {
        now = noon
        val target = journal()
        val runner = FakeRunner()
        val target2 = scheduler(target, runner)
        target2.evaluate(listOf(rule()), enabled = true)
        assertEquals(listOf("rule-1"), runner.firedIds)
        // The same minute's next poll tick, and a later minute: journaled.
        now = noon + 30_000
        target2.evaluate(listOf(rule()), enabled = true)
        now = noon + 5 * 60_000
        target2.evaluate(listOf(rule()), enabled = true)
        assertEquals(1, runner.firedIds.size)
    }

    @Test
    fun `journal persists so a restart cannot double-fire`() = runBlocking {
        now = noon
        val target = journal()
        scheduler(target, FakeRunner()).evaluate(listOf(rule()), enabled = true)
        // A fresh scheduler on a fresh journal instance (the restart shape):
        // the persisted key still blocks the occurrence.
        val runner = FakeRunner()
        scheduler(journal(), runner).evaluate(listOf(rule()), enabled = true)
        assertTrue(runner.firedIds.isEmpty())
    }

    @Test
    fun `the journaled key is the rule's occurrence key`() = runBlocking {
        now = noon
        val target = journal()
        scheduler(target, FakeRunner()).evaluate(listOf(rule()), enabled = true)
        val calendar = Calendar.getInstance(utc).apply { timeInMillis = noon }
        val epochDay = Math.floorDiv(
            calendar.timeInMillis + calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET),
            86_400_000L,
        )
        assertEquals(
            mapOf("rule-1" to AutomationSchedulePolicy.occurrenceKey(rule(), epochDay)),
            target.load(),
        )
    }

    @Test
    fun `a rule without an id is skipped defensively`() = runBlocking {
        now = noon
        val target = journal()
        val runner = FakeRunner()
        scheduler(target, runner).evaluate(listOf(rule(id = null)), enabled = true)
        assertTrue(runner.firedIds.isEmpty())
        assertTrue(target.load().isEmpty())
    }

    // ── Failure isolation ──

    @Test
    fun `one failing rule does not lose the others and both are journaled`() = runBlocking {
        now = noon
        val target = journal()
        val runner = FakeRunner().apply { failIds += "rule-1" }
        scheduler(target, runner).evaluate(
            listOf(rule(id = "rule-1"), rule(id = "rule-2")),
            enabled = true,
        )
        // Rule 1 threw; rule 2 still ran, and both journal entries landed.
        assertEquals(listOf("rule-1", "rule-2"), runner.firedIds)
        assertEquals(setOf("rule-1", "rule-2"), target.load().keys)
        // Neither re-fires on the next tick — a persistently failing rule
        // must not tight-loop the poll cadence.
        val next = FakeRunner()
        scheduler(target, next).evaluate(
            listOf(rule(id = "rule-1"), rule(id = "rule-2")),
            enabled = true,
        )
        assertTrue(next.firedIds.isEmpty())
    }

    // ── Loop wiring (real start(), real flows) ──

    @Test
    fun `boot catch-up fires on the immediate first evaluation`() = runBlocking {
        now = noon + 60_000 // the minute already passed before the process started
        rulesFlow.value = listOf(rule())
        enabledFlow.value = true
        val runner = FakeRunner()
        scheduler(journal(), runner).start()
        val fired = withTimeout(5_000) { runner.received.receive() }
        assertEquals(listOf("rule-1"), fired.map { it.id })
        // The journal then blocks every later tick.
        now = noon + 2 * 60_000
        enabledFlow.value = false
        enabledFlow.value = true // forces a fresh evaluation without waiting a poll interval
        Thread.sleep(300)
        assertNull(runner.received.tryReceive().getOrNull())
    }

    @Test
    fun `master disabled blocks firing until re-enabled`() = runBlocking {
        now = noon
        rulesFlow.value = listOf(rule())
        enabledFlow.value = false
        val runner = FakeRunner()
        scheduler(journal(), runner).start()
        Thread.sleep(300) // the immediate first evaluation ran, disabled
        assertNull(runner.received.tryReceive().getOrNull())
        enabledFlow.value = true // the re-emission restarts the loop into a catch-up
        val fired = withTimeout(5_000) { runner.received.receive() }
        assertEquals(listOf("rule-1"), fired.map { it.id })
    }
}

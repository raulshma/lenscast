package com.raulshma.lenscast.automation

import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.camera.LightSensorMonitor
import com.raulshma.lenscast.data.SettingsDataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The local rules mirror's hydration latch (`LocalAutomationSource`'s
 * `rulesReady`), JVM-tested against a fake store whose shared flow replays
 * the seeded placeholder exactly like the real `shareIn`. These tests pin
 * the latch's value contract mechanically:
 *
 * 1. the collector's replayed placeholder only WAKES the disk consult: the
 *    disk twin's answer is what rules AND readiness publish from — with no
 *    dependency on the shared flow's value (the replay can be the seeded
 *    placeholder while the read is in flight, or an already-hydrated
 *    snapshot the disk has outgrown);
 * 2. a PENDING disk read keeps the gate shut — pending hydration is
 *    distinguishable from an immediately-completed read — and completing it
 *    with a genuinely-empty list arms (an empty store is a verdict, not a
 *    pending read);
 * 3. a deletion landing after hydration reconciles through the
 *    emission-triggered consult — the latch cannot wedge: the arm never
 *    depends on flow contents, so the StateFlow's dedupe cannot strand it;
 * 4. a non-empty replay cannot open the gate against the pending disk read:
 *    completing the read arms from the DISK answer, and the gate flip and
 *    the mirror publish are one ordered pair in the consult's coroutine —
 *    the test reads both at the flip on the same thread, so a swapped write
 *    order fails the mirror assert;
 * 5. shared-flow churn (empty → non-empty → empty) racing a PENDING read
 *    cannot satisfy the gate: hydration completes only when the read does,
 *    and the DISK answer — distinct from every replayed/churned value — is
 *    what gets published.
 *
 * The latch runs on an UNCONFINED test dispatcher so the collect processes
 * every mutation inline, and nothing here needs virtual time. The
 * pending-read variants hold the MockK-stubbed suspend call open with a
 * deferred gate (`coAnswers { gate.await() }`) and complete it EXPLICITLY
 * from the test thread — the unconfined resumption lands inline right
 * there, never on the scheduler. Waiting on scheduler time for a parked
 * resumption remains the losing idiom: `advanceUntilIdle()` drains before
 * the continuation lands back there.
 *
 * The editor's lux readout (`illuminance`) rides the same scope, and its
 * tests pin that collection IS the sensor's demand: the first subscriber
 * opens the monitor's editor gate, the last one's subscribe grace expiring
 * closes it — the one place here a virtual delay is walked by hand.
 */
class LocalAutomationSourceTest {

    private val store: SettingsDataStore = mockk(relaxed = true)
    private val testController: AutomationTestController = mockk(relaxed = true)
    private val fireJournal: AutomationFireJournal = mockk(relaxed = true)

    /** The light sensor runtime — only its gate calls and its reading are read. */
    private val lightSensorMonitor: LightSensorMonitor = mockk(relaxed = true)
    private val lux = MutableStateFlow<Int?>(null)


    /** The store's shared flow — starts at the seeded placeholder, like the real share-in. */
    private val rulesFlow = MutableStateFlow<List<AutomationRule>>(emptyList())
    private val enabledFlow = MutableStateFlow(false)
    private val armedFlow = MutableStateFlow<String?>(null)
    private val torch = MutableStateFlow<Int?>(null)

    /**
     * Unconfined: the latch's collect processes every mutation inline, no
     * drain to race. The scheduler is kept by name so the readout's subscribe
     * grace — a virtual delay on this scope — can be walked by hand.
     */
    private val scheduler = TestCoroutineScheduler()
    private val scopeJob = Job()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + scopeJob)

    @Before
    fun setUp() {
        every { store.automationRules } returns rulesFlow
        every { store.automationEnabled } returns enabledFlow
        every { testController.armedRuleId } returns armedFlow
        every { fireJournal.load() } returns emptyMap()
        every { lightSensorMonitor.illuminanceLux } returns lux
        every { lightSensorMonitor.sensorAvailable } returns true
        // The store is hydrated by default; tests that need a different
        // snapshot re-stub this.
        coEvery { store.automationRulesFromDisk() } returns emptyList()
    }

    @After
    fun tearDown() {
        scopeJob.cancel()
        scope.cancel()
    }

    private fun source(): LocalAutomationSource =
        LocalAutomationSource(store, testController, fireJournal, torch, lightSensorMonitor, scope)

    @Test
    fun `placeholder replay arms from the disk snapshot, not from the shared flow`() = runTest {
        // The disk truth is [ruleA] while the shared flow still holds only
        // the seeded placeholder: subscribing replays that placeholder, which
        // only wakes the disk consult — the arm publishes THAT answer (rules
        // AND readiness, ordered in one coroutine) instead of anything read
        // off the replayed value.
        coEvery { store.automationRulesFromDisk() } returns listOf(RULE)
        val latch = source()
        assertTrue(latch.rulesReady.value)
        assertEquals(listOf(RULE), latch.automationRules.value)

        // The share-in's catch-up lands the same list — the emission's
        // consult answers the same truth, a no-op on an already-armed latch.
        rulesFlow.value = listOf(RULE)
        assertTrue(latch.rulesReady.value)
        assertEquals(listOf(RULE), latch.automationRules.value)
    }

    @Test
    fun `genuinely empty persisted list is a hydrated verdict`() = runTest {
        // The disk read is held PENDING on a deferred gate: the gate stays
        // shut while hydration is in flight, so pending is distinguishable
        // from an immediately-completed read. Completing the read with the
        // empty list arms — placeholder and truth are the same empty list
        // (an empty store is a verdict, not a pending read).
        val diskRead = CompletableDeferred<List<AutomationRule>>()
        coEvery { store.automationRulesFromDisk() } coAnswers { diskRead.await() }
        val latch = source()
        assertFalse(latch.rulesReady.value)
        diskRead.complete(emptyList())
        assertTrue(latch.rulesReady.value)
        assertEquals(emptyList<AutomationRule>(), latch.automationRules.value)
    }

    @Test
    fun `deletion after hydration reconciles through the emission consult`() = runTest {
        // The disk consult answers [ruleA] at hydration; the share-in's
        // catch-up echoes it (a no-op consult); then a wholesale deletion
        // lands and the flow carries ONLY the empty list. The emission
        // triggers a fresh consult whose answer — the post-deletion truth —
        // is what the mirror publishes. (The old equality-based latch could
        // wedge shut forever here: the StateFlow's dedupe never re-fires a
        // missed classification. The consult-per-emission latch has no
        // classification to miss.)
        coEvery { store.automationRulesFromDisk() } returnsMany listOf(
            listOf(RULE),
            listOf(RULE),
            emptyList(),
        )
        val latch = source()
        assertTrue(latch.rulesReady.value)
        assertEquals(listOf(RULE), latch.automationRules.value)

        rulesFlow.value = listOf(RULE)
        assertEquals(listOf(RULE), latch.automationRules.value)

        rulesFlow.value = emptyList()
        assertEquals(emptyList<AutomationRule>(), latch.automationRules.value)
        assertTrue(latch.rulesReady.value)
    }

    @Test
    fun `replayed value cannot open the gate against the pending disk read`() = runTest {
        // The share-in replays the non-empty [RULE_COPY] while the disk read
        // is held pending: the replay cannot arm the gate — completing the
        // read arms from the DISK answer [RULE], which is what both the gate
        // and the mirror publish. The flip and the mirror publish are one
        // ordered pair in the consult's coroutine, so reading both here on
        // the same thread pins the write order deterministically (a swap
        // leaves the mirror empty and fails the assert); a second collector
        // resumed as a consequence of the arm would not.
        val diskRead = CompletableDeferred<List<AutomationRule>>()
        coEvery { store.automationRulesFromDisk() } coAnswers {
            if (diskRead.isCompleted) listOf(RULE_COPY) else diskRead.await()
        }
        rulesFlow.value = listOf(RULE_COPY) // the replay's non-empty snapshot
        val latch = source()
        assertFalse(latch.rulesReady.value) // pending read: the replay cannot arm the gate
        diskRead.complete(listOf(RULE)) // the disk answer — distinct from the replay
        assertTrue(latch.rulesReady.value) // armed by the DISK answer
        assertEquals(listOf(RULE), latch.automationRules.value) // ...which the mirror holds
        rulesFlow.value = emptyList() // a reconciling emission lands later
        // Its consult answers the landed write's truth, not the emission's value.
        assertEquals(listOf(RULE_COPY), latch.automationRules.value)
    }

    @Test
    fun `churn while the read is pending cannot satisfy the gate`() = runTest {
        // While the read is pending, the shared flow churns empty → [ruleB]
        // → empty: none of it may satisfy the gate. Completing the read arms
        // from the DISK answer — distinct from the churn's non-empty peak
        // and from the last replayed value — which is what gets published;
        // the conflated emission's consult lands the same answer again.
        val diskRead = CompletableDeferred<List<AutomationRule>>()
        var consults = 0
        coEvery { store.automationRulesFromDisk() } coAnswers {
            if (consults++ == 0) diskRead.await() else listOf(RULE)
        }
        val latch = source()
        assertFalse(latch.rulesReady.value)
        rulesFlow.value = listOf(RULE_COPY)
        rulesFlow.value = emptyList()
        assertFalse(latch.rulesReady.value)
        diskRead.complete(listOf(RULE))
        assertTrue(latch.rulesReady.value)
        assertEquals(listOf(RULE), latch.automationRules.value)
    }

    @Test
    fun `the lux readout opens the editor gate on collect and closes it once the grace lapses`() {
        val source = source()
        // Nobody collects yet: the value is the pre-reading one, and nothing
        // has asked the sensor for a sample.
        assertEquals(AutomationIlluminance(lux = null, sensorAvailable = true), source.illuminance.value)
        verify(exactly = 0) { lightSensorMonitor.setEditorOpen(any()) }

        val collector = scope.launch { source.illuminance.collect() }
        verify(exactly = 1) { lightSensorMonitor.setEditorOpen(true) }

        // The reading maps straight through, verdict riding along.
        lux.value = 120
        assertEquals(AutomationIlluminance(lux = 120, sensorAvailable = true), source.illuminance.value)

        collector.cancel()
        // Inside the subscribe grace the gate stays open — a configuration
        // change's immediate resubscribe must not unregister the sensor and
        // restart its debounce.
        verify(exactly = 0) { lightSensorMonitor.setEditorOpen(false) }

        // Past the source's 5 s grace the last collector's departure closes it.
        scheduler.advanceTimeBy(5_001)
        scheduler.runCurrent()
        verify(exactly = 1) { lightSensorMonitor.setEditorOpen(false) }
    }

    @Test
    fun `a phone without the sensor publishes that verdict before any reading`() {
        every { lightSensorMonitor.sensorAvailable } returns false

        // The readout reads "no sensor" from its first frame — never a flash
        // of "waiting" while nothing can ever arrive.
        assertEquals(AutomationIlluminance(lux = null, sensorAvailable = false), source().illuminance.value)
    }

    private companion object {
        val RULE = AutomationRule(id = "a", name = "rule-a")
        val RULE_COPY = AutomationRule(id = "b", name = "rule-b")
    }
}

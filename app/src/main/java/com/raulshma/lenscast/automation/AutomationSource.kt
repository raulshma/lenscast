package com.raulshma.lenscast.automation

import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The automation screen's one seam — the exact truth surface
 * [AutomationViewModel] and AutomationScreen consume (the master toggle, the
 * rule list, the fire journal's read side, the armed Automation Test, and the
 * whole-document write), as a plain interface. One interface, two backends
 * (the [com.raulshma.lenscast.camera.CameraConsole] pattern; the
 * [com.raulshma.lenscast.capture.EventsSource] precedent): the rule editor
 * runs against either
 *
 * - the **local** backend — [LocalAutomationSource], today's graph
 *   singletons (the settings store's automation surface + the fire journal +
 *   the test controller + the camera's flash verdict) unchanged; or
 * - the **remote** backend —
 *   [com.raulshma.lenscast.companion.console.RemoteAutomationSource], the
 *   paired device's `GET/PUT /api/automation` document plus its
 *   `/api/automation/test` and `/restore` routes.
 *
 * The rule shape needs no mapping on either backend: the wire rule IS the
 * domain [AutomationRule] (the ONE declaration — the web handler embeds it
 * directly in the state document), so the editor's draft machinery,
 * catalogs, and card renderers consume the identical values everywhere.
 *
 * Mutation gate: [canEdit] — every write here (the master toggle, the rule
 * list write, the test arm/restore) is a config write the device's
 * [com.raulshma.lenscast.streaming.web.RoleGatePolicy] DENIES a viewer-role
 * session, so a viewer pairing pins this false and the screen hides the
 * write affordances (master switch, add FAB, card switches, the
 * duplicate/delete menu, the test icon) instead of handing the device a 403.
 * Reads stay open — a viewer sees the rules exactly as the device runs them.
 */
interface AutomationSource {

    /** The engine's master switch, as the backend holds it. */
    val automationEnabled: StateFlow<Boolean>

    /** The configured rules, in the backend's persisted order. */
    val automationRules: StateFlow<List<AutomationRule>>

    /**
     * Whether [automationRules] has loaded its first real truth. False only
     * in the window before the backend's initial read lands — remotely, the
     * first `GET /api/automation` answer; locally, the store's first
     * DataStore read (the shared flows are eagerly STARTED, but their values
     * hold the descriptor default — the empty placeholder — until that read
     * lands, the same default-first race the store's `...FromDisk()` twins
     * document). Rule-list edits must not build on [automationRules]'s
     * value while this is false: the pre-load value is the placeholder
     * (empty) list, and the whole-document save would submit that placeholder
     * as the device's next truth — a wipe of its configured rules. Both
     * backends publish this verdict ordered AFTER their rules snapshot, so
     * an edit that observes true always builds on the hydrated list.
     */
    val rulesReady: StateFlow<Boolean>

    /**
     * Rule id → last-fired journal value — the raw read the screen decodes
     * with [com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy.decodeOccurrenceKey]
     * into "Last ran …" lines. Both backends' values decode identically
     * (locally: the journal's own occurrence keys / event timestamps;
     * remotely: the state document's `lastFiredAt` instants, which ride
     * above the event-journal threshold and therefore pass through the
     * decoder verbatim).
     */
    val lastFiredKeys: StateFlow<Map<String, Long>>

    /** The armed Automation Test's rule id; null = nothing armed (no restore pending). */
    val armedTestRuleId: StateFlow<String?>

    /**
     * The bound camera's multi-level flash ceiling (null = no verdict yet /
     * unsupported). The torch strength editor rides it against the catalog
     * span; a backend without a carrier for that device fact pins null
     * (remote: the wire publishes no flash-level reading), which is exactly
     * the "camera has not reported" rendering — the catalog span.
     */
    val torchStrengthMaxLevel: StateFlow<Int?>

    /** Whether writes render at all (the role's config-write verdict). False hides every write affordance. */
    val canEdit: Boolean

    /** The master toggle's write. Caller-gated by [canEdit]. */
    fun setEnabled(enabled: Boolean)

    /**
     * The one rules-write seam: persists the whole list (the document is
     * wholesale — a rule absent from the list is gone). The caller has
     * already applied its list transform and
     * [com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy.sanitizeRules];
     * the backend sanitizes again behind the write, so no out-of-bounds rule
     * can land. Caller-gated by [canEdit].
     *
     * Returns a handle that completes when the write settles and completes
     * EXCEPTIONALLY when the backend refused or dropped it — the caller's
     * pending-edit overlay retires a failed revision instead of silently
     * building later edits on it (a failed edit must not ride inside a later
     * save). The write itself stays fire-and-forget: nobody must await this
     * for the write to run.
     */
    fun saveRules(rules: List<AutomationRule>): Deferred<Unit>

    /**
     * The Automation Test's arm: fire [ruleId]'s actions once, state
     * snapshot held for the restore. Caller-gated by [canEdit].
     */
    fun runRuleTest(ruleId: String)

    /** The Automation Test's restore; a no-op when nothing is armed. Caller-gated by [canEdit]. */
    fun restoreRuleTest()
}

/**
 * The automation screen's LOCAL backend: [AutomationSource] over this
 * phone's graph singletons — the AutomationViewModel's old collaborators,
 * lifted verbatim into a source. The settings store stays the single source
 * of truth (the flows read straight through; the rule list is the one
 * mirror, paired with its hydration latch — see [rulesReady]), the fire
 * journal's read side keeps its screen-beat poll (the old
 * `lastFiredKeys` loop, only collected while a screen is subscribed), and
 * every write lands through the store's savers, which sanitize again behind
 * the caller.
 */
class LocalAutomationSource(
    private val settingsDataStore: SettingsDataStore,
    /** The Automation Test's arm/restore owner (issue #18 item 3). */
    private val testController: AutomationTestController,
    /** The fire journal — the last-fired read side (the screen's poll reads it directly). */
    private val fireJournal: AutomationFireJournal,
    /** The bound camera's multi-level flash ceiling (the camera service's verdict flow). */
    private val torchMaxLevel: StateFlow<Int?>,
    /** The scope the journal poll and the writes run on. */
    private val scope: CoroutineScope,
) : AutomationSource {

    override val automationEnabled: StateFlow<Boolean> = settingsDataStore.automationEnabled
    override val armedTestRuleId: StateFlow<String?> = testController.armedRuleId
    override val torchStrengthMaxLevel: StateFlow<Int?> = torchMaxLevel

    // The rule list is mirrored off the store's DISK truth (which stays the
    // single source of truth) beside a hydration latch: the store's shared
    // flows are eager, but their VALUES start at the descriptor default —
    // the empty placeholder — until DataStore's first disk read lands, and a
    // whole-document save built on that placeholder would wipe the persisted
    // rules. One collector publishes the pair below, the rules write first,
    // the readiness write second — program-ordered in one coroutine, so
    // observing rulesReady true always sees the hydrated snapshot.
    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())
    private val _rulesReady = MutableStateFlow(false)

    override val automationRules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()

    override val rulesReady: StateFlow<Boolean> = _rulesReady.asStateFlow()

    init {
        scope.launch {
            // The shared flow only WAKES the read below; its emission value is
            // never trusted. Subscribing replays the StateFlow's current
            // VALUE, and that value can be stale in two independent ways: the
            // descriptor-default placeholder (empty) while DataStore's first
            // read is still in flight, or an already-hydrated snapshot the
            // disk has outgrown (a write can commit to the file between the
            // share-in's last processed emission and this read — the store's
            // `...FromDisk()` twin is a FRESH file pass, never lags the
            // share-in). Arming or mirroring on the replayed value would open
            // the edit gate on a list that is not the truth, and the
            // whole-document save behind that gate would overwrite the newer
            // rules. So every emission — the replay included — triggers the
            // store's disk-read twin instead: its answer suspends until
            // DataStore's read lands and is whatever the file holds NOW; it
            // is the one hydration signal, and its shape is not consulted —
            // a genuinely empty persisted list is a hydrated verdict too.
            settingsDataStore.automationRules.collect { _ -> consultAndPublish() }
        }
    }

    /**
     * One hydration step: consult the disk twin and publish its answer — the
     * mirror first, the readiness latch second, ordered in this one
     * coroutine. A failed read must not escape the app-scope launch (an
     * unhandled exception here would crash the app) nor arm the gate on
     * nothing: it retries until the store answers, so editing stays
     * unavailable until hydration actually succeeds and every caller's
     * deferred edit waits honestly. Cancellation propagates — the scope's
     * death is never treated as a read failure.
     */
    private suspend fun consultAndPublish() {
        while (true) {
            val snapshot = try {
                settingsDataStore.automationRulesFromDisk()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(HYDRATION_RETRY_MILLIS)
                continue
            }
            _rules.value = snapshot
            if (!_rulesReady.value) _rulesReady.value = true
            return
        }
    }

    override val canEdit: Boolean = true

    /**
     * Rule id → last fired occurrence key, re-read from the fire journal on
     * a beat matching the engine's own poll cadence (the journal is a tiny
     * per-rule long map on disk — its load is the designed read path). Only
     * collected while a screen is subscribed, so nothing polls in the
     * background — the ViewModel's old loop, verbatim.
     */
    override val lastFiredKeys: StateFlow<Map<String, Long>> = flow {
        while (true) {
            emit(fireJournal.load())
            delay(JOURNAL_POLL_MILLIS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MILLIS), emptyMap())

    override fun setEnabled(enabled: Boolean) {
        scope.launch { settingsDataStore.saveAutomationEnabled(enabled) }
    }

    /**
     * The whole-document saves' serialization: one save at a time, so two
     * writers' commits land in the order their callers launched them. This
     * source is an app-scoped singleton — a route change builds a fresh
     * ViewModel over it while an older instance's write may still be settling
     * (the write runs HERE, surviving that instance's disposal), and
     * unordered commits would let the later-built list commit first only to
     * be overwritten by the stale one.
     */
    private val writeMutex = Mutex()

    override fun saveRules(rules: List<AutomationRule>): Deferred<Unit> =
        // async, not launch: the failure is held in the Deferred for the
        // caller's await instead of surfacing as an unhandled launch
        // exception — the write's verdict stays observable without an
        // app-scope crash path.
        scope.async {
            writeMutex.withLock {
                settingsDataStore.saveAutomationRules(rules)
                // Publish the settled disk truth BEFORE the handle completes:
                // the acknowledgment watcher (and any later edit's base)
                // sees the write's own truth without waiting out the share-in's
                // hop. Best-effort — a failed publish read leaves the write's
                // verdict (it committed) intact; the emission-triggered
                // consult lands the same truth a beat later.
                try {
                    _rules.value = settingsDataStore.automationRulesFromDisk()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the share-in's emission consult catches up
                }
            }
        }

    override fun runRuleTest(ruleId: String) {
        scope.launch { testController.arm(ruleId) }
    }

    override fun restoreRuleTest() {
        scope.launch { testController.restore() }
    }

    companion object {
        /** The journal poll beat, matching the scheduler's own poll cadence. */
        private val JOURNAL_POLL_MILLIS = StreamDefaults.AUTOMATION_POLL_SECONDS * 1_000L

        /** The flows' subscribe grace — the ViewModel's old cadence, kept. */
        private const val SUBSCRIBE_GRACE_MILLIS = 5_000L

        /**
         * The failed hydration read's retry beat: a transient DataStore read
         * failure re-attempts at this cadence until it answers (the gate stays
         * shut the whole time), instead of crashing the app-scope launch.
         */
        private const val HYDRATION_RETRY_MILLIS = 1_000L
    }
}

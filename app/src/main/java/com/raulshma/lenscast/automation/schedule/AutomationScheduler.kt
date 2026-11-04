package com.raulshma.lenscast.automation.schedule

import android.util.Log
import com.raulshma.lenscast.core.LocalWallClock
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The execution seam between the scheduler's evaluation and the runtime: the
 * scheduler hands one fired rule at a time to [run] and knows nothing about
 * what the actions do. [com.raulshma.lenscast.automation.AutomationActionExecutor]
 * is the production implementation (the same executor the automation
 * receiver drives); tests substitute a fake.
 */
fun interface AutomationActionRunner {
    suspend fun run(fired: List<AutomationSchedulePolicy.FiredRule>)
}

/**
 * The automation scheduler — the shell around the pure
 * [AutomationSchedulePolicy]. It owns the clock, the poll loop, the event
 * entry point, and the [AutomationFireJournal]; every decision (which rules
 * are due, which actions may catch up, whether a trigger matches an event)
 * stays in the policy so it remains JVM-testable — the same split as
 * StreamingManager's eco-idle monitor over EcoIdlePolicy.
 *
 * Two evaluation paths, serialized by one mutex so a poll tick and an event
 * fire can never interleave their action runs:
 *
 * - **Time** (the loop): re-evaluates [AutomationSchedulePolicy.dueRules] on
 *   every [StreamDefaults.AUTOMATION_POLL_SECONDS] tick, and re-evaluates
 *   immediately whenever the persisted rules or the master toggle change
 *   (the combine/collectLatest pair restarts the tick loop on a new
 *   emission). The first evaluation is immediate — it IS the boot catch-up:
 *   process start → DataStore rules survive → this first pass fires
 *   everything the process missed, with the fire journal blocking
 *   occurrences that already ran, which is why no boot receiver is needed.
 * - **Event** ([onEvent]): the wiring layer delivers an
 *   [AutomationEvent]; the scheduler evaluates
 *   [AutomationSchedulePolicy.eventDue] against the freshest rules/toggle
 *   caches and a freshly pulled [AutomationSensorSnapshot], then journals
 *   the firing's wall-clock time — the value the rule's cooldown is checked
 *   against on the next matching event.
 *
 * The journal carries both kinds of records (occurrence keys for time fires,
 * epoch milliseconds for event fires) and
 * [AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD] keeps them from being
 * read as each other, so switching a rule's trigger kind never wedges it.
 *
 * The [AutomationEvent.AppStarted] cue is delivered once per process, after
 * the first rules emission (so the DataStore rules are loaded) — guarded by
 * a one-shot flag, because every rules edit restarts the collectLatest body
 * and would otherwise re-fire it.
 *
 * Runs on its own SupervisorJob scope for the process lifetime, exactly like
 * StreamWatchdog: started once from MainApplication, never cancelled (the
 * death of the process is the only stop).
 */
class AutomationScheduler(
    private val settingsDataStore: SettingsDataStore,
    private val journal: AutomationFireJournal,
    private val runner: AutomationActionRunner,
    private val snapshotProvider: AutomationSensorSnapshotProvider,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /** Serializes the time tick against event fires: no interleaved runs. */
    private val evaluationMutex = Mutex()

    /**
     * The freshest rules/master toggle, kept current by the loop's combine.
     * Event evaluations read these caches so a rule saved a millisecond ago
     * is honored at the next event even before the tick loop notices.
     */
    private var rulesCache: List<AutomationRule> = emptyList()
    private var enabledCache: Boolean = false
    private var appStartFired = false

    /**
     * Starts the evaluation loop. Idempotent: a second call while the loop
     * is live is a no-op, mirroring StreamWatchdog.startMonitoring.
     */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            combine(
                settingsDataStore.automationRules,
                settingsDataStore.automationEnabled,
            ) { rules, enabled -> rules to enabled }
                .collectLatest { (rules, enabled) ->
                    rulesCache = rules
                    enabledCache = enabled
                    // The process-start cue: exactly once, after the first
                    // emission guarantees the persisted rules are cached.
                    if (!appStartFired) {
                        appStartFired = true
                        onEvent(AutomationEvent.AppStarted)
                    }
                    while (currentCoroutineContext().isActive) {
                        evaluate(rules, enabled)
                        delay(StreamDefaults.AUTOMATION_POLL_SECONDS * 1000L)
                    }
                }
        }
    }

    /** Stops the evaluation loop; start() may follow. Process code never calls this. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * The event entry point: fire-and-forget — the wiring layer's collectors
     * call this and keep flowing. Events delivered before the loop's first
     * emission evaluate against an empty rule cache and no-op (a rule lost
     * in the first process milliseconds is acceptable; a rule firing before
     * its persisted state is loaded is not).
     */
    fun onEvent(event: AutomationEvent) {
        scope.launch { evaluateEvent(event) }
    }

    /**
     * One time-path evaluation tick: derive the local wall-clock calendar
     * from [nowMs], build the sensor snapshot, ask the policy which rules
     * are due, run each fired rule through the [runner] one at a time (so
     * one failing rule never loses the others), and journal each attempt.
     * The minute/day derivations are the shared [LocalWallClock] math — the
     * same numbers the detection coordinator reads, so the two consumers of
     * the wall clock cannot drift.
     *
     * Internal for tests: production reaches this only through [start]'s loop.
     */
    internal suspend fun evaluate(rules: List<AutomationRule>, enabled: Boolean) {
        evaluationMutex.withLock {
            val now = nowMs()
            val snapshot = snapshot(now) ?: return
            val fired = AutomationSchedulePolicy.dueRules(
                rules = rules,
                enabled = enabled,
                snapshot = snapshot,
                journal = journal.load(),
                graceMs = StreamDefaults.AUTOMATION_ONESHOT_GRACE_MS,
            )
            runAndJournal(fired) { rule ->
                AutomationSchedulePolicy.occurrenceKey(rule, snapshot.epochDay)
            }
        }
    }

    /**
     * One event-path evaluation: the same shape as [evaluate] but with the
     * journal recording the firing's wall-clock time (the value the next
     * event's cooldown check reads). Production evaluates against the
     * freshest rule/toggle caches ([rulesCache]/[enabledCache], kept current
     * by the loop's combine) so a rule saved a millisecond ago is honored at
     * the next event even before the tick loop notices; tests may pass
     * explicit rules instead.
     *
     * Internal for tests: production reaches this through [onEvent].
     */
    internal suspend fun evaluateEvent(
        event: AutomationEvent,
        rules: List<AutomationRule> = rulesCache,
        enabled: Boolean = enabledCache,
    ) {
        evaluationMutex.withLock {
            val now = nowMs()
            val snapshot = snapshot(now) ?: return
            val fired = AutomationSchedulePolicy.eventDue(
                rules = rules,
                enabled = enabled,
                event = event,
                snapshot = snapshot,
                journal = journal.load(),
                nowMs = now,
            )
            runAndJournal(fired) { _ -> now }
        }
    }

    /**
     * Runs each fired rule and journals its occurrence — after the run
     * attempt regardless of failure: a persistently failing action must not
     * tight-loop the poll cadence re-running it every tick, nor an event
     * cluster re-run it on every retry. [keyFor] supplies the path-specific
     * journal value (occurrence key for time fires, wall-clock ms for
     * events).
     */
    private suspend fun runAndJournal(
        fired: List<AutomationSchedulePolicy.FiredRule>,
        keyFor: (AutomationRule) -> Long,
    ) {
        for (rule in fired) {
            val id = rule.rule.id
            if (id == null) {
                // The store decode mints an id for every persisted rule, so
                // this is unreachable through the settings flow — skipped
                // defensively because a null-id rule could never be journaled
                // and would re-fire on every tick or event.
                Log.w(TAG, "Automation rule '${rule.rule.name}' has no id; not firing")
                continue
            }
            runCatching { runner.run(listOf(rule)) }
                .onFailure {
                    Log.w(TAG, "Automation rule '${rule.rule.name}' ($id) failed: ${it.message}")
                }
            journal.record(id, keyFor(rule.rule))
        }
    }

    /**
     * The sensor snapshot at wall-clock [nowMs] moment, or null when the
     * provider fails — a snapshot read must never crash the loop; skipping
     * one evaluation is free (the next tick or event re-evaluates).
     */
    private suspend fun snapshot(nowMs: Long): AutomationSensorSnapshot? =
        runCatching {
            val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
            snapshotProvider.snapshot().let { provided ->
                // Re-stamp the wall-clock triple from THIS tick's calendar so
                // the snapshot's time fields can never disagree with the tick
                // the evaluation runs in, however the provider built it.
                provided.copy(
                    minuteOfDay = LocalWallClock.minuteOfDay(calendar),
                    epochDay = LocalWallClock.epochDay(calendar),
                    isoDayIndex = LocalWallClock.isoDayIndex(calendar),
                )
            }
        }.getOrNull()
        ?: run {
            Log.w(TAG, "Automation sensor snapshot unavailable; skipping evaluation")
            null
        }

    companion object {
        private const val TAG = "AutomationScheduler"
    }
}

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
 * The time-of-day automation scheduler — the shell around the pure
 * [AutomationSchedulePolicy]. It owns the clock, the poll loop, and the
 * [AutomationFireJournal]; every decision (which rules are due, which
 * actions may catch up) stays in the policy so it remains JVM-testable —
 * the same split as StreamingManager's eco-idle monitor over EcoIdlePolicy.
 *
 * The loop re-evaluates on every [StreamDefaults.AUTOMATION_POLL_SECONDS]
 * tick, and re-evaluates immediately whenever the persisted rules or the
 * master toggle change (the combine/collectLatest pair restarts the tick
 * loop on a new emission). The first evaluation is immediate — it IS the
 * boot catch-up: process start → DataStore rules survive → this first pass
 * fires everything the process missed, with the fire journal (see
 * [AutomationFireJournal]) blocking occurrences that already ran, which is
 * why no boot receiver is needed.
 *
 * Runs on its own SupervisorJob scope for the process lifetime, exactly like
 * StreamWatchdog: started once from MainApplication, never cancelled (the
 * death of the process is the only stop).
 */
class AutomationScheduler(
    private val settingsDataStore: SettingsDataStore,
    private val journal: AutomationFireJournal,
    private val runner: AutomationActionRunner,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

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
     * One evaluation tick: derive the local wall-clock calendar from [nowMs],
     * ask the policy which rules are due, run each fired rule through the
     * [runner] one at a time (so one failing rule never loses the others),
     * and journal each attempt. The minute/day derivations are the shared
     * [LocalWallClock] math — the same numbers the detection coordinator
     * reads, so the two consumers of the wall clock cannot drift.
     *
     * Internal for tests: production reaches this only through [start]'s loop.
     */
    internal suspend fun evaluate(rules: List<AutomationRule>, enabled: Boolean) {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMs() }
        val minuteOfDay = LocalWallClock.minuteOfDay(calendar)
        val epochDay = LocalWallClock.epochDay(calendar)
        val isoDayIndex = LocalWallClock.isoDayIndex(calendar)

        val fired = AutomationSchedulePolicy.dueRules(
            rules = rules,
            enabled = enabled,
            minuteOfDay = minuteOfDay,
            epochDay = epochDay,
            isoDayIndex = isoDayIndex,
            journal = journal.load(),
            graceMs = StreamDefaults.AUTOMATION_ONESHOT_GRACE_MS,
        )
        for (rule in fired) {
            val id = rule.rule.id
            if (id == null) {
                // The store decode mints an id for every persisted rule, so
                // this is unreachable through the settings flow — skipped
                // defensively because a null-id rule could never be journaled
                // and would re-fire on every tick.
                Log.w(TAG, "Automation rule '${rule.rule.name}' has no id; not firing")
                continue
            }
            runCatching { runner.run(listOf(rule)) }
                .onFailure {
                    Log.w(TAG, "Automation rule '${rule.rule.name}' ($id) failed: ${it.message}")
                }
            // Recorded AFTER the run attempt regardless of failure: a
            // persistently failing action must not tight-loop the poll
            // cadence re-running it every tick.
            journal.record(id, AutomationSchedulePolicy.occurrenceKey(rule.rule, epochDay))
        }
    }

    companion object {
        private const val TAG = "AutomationScheduler"
    }
}

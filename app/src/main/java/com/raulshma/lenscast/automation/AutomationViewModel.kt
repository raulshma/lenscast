package com.raulshma.lenscast.automation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationParamSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.automation.schedule.AutomationTriggerKind
import com.raulshma.lenscast.automation.schedule.MissedRunPolicy
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.gallery.GalleryDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Collections
import java.util.TimeZone
import java.util.UUID
import java.util.WeakHashMap

/**
 * One rendered action-summary line: the string resource plus its positional
 * string args. The pure half of [AutomationViewModel.actionSummaryLines] —
 * the screen resolves it with `stringResource(res, *args.toTypedArray())`,
 * JVM tests assert the resource ids and args without touching Android.
 */
data class AutomationSummaryLine(
    @StringRes val res: Int,
    val args: List<String> = emptyList(),
)

/**
 * The display bucket of a rule's last-fired instant — the pure half of the
 * rule card's "Last ran …" line, resolved to strings by the screen
 * (Today/Yesterday carry the local minute-of-day for their HH:mm templates;
 * [AutomationLastFiredLabel.Earlier] carries the epoch instant for a
 * locale-formatted date).
 */
sealed interface AutomationLastFiredLabel {
    data class Today(val minuteOfDay: Int) : AutomationLastFiredLabel
    data class Yesterday(val minuteOfDay: Int) : AutomationLastFiredLabel
    data class Earlier(val epochMillis: Long, val minuteOfDay: Int) : AutomationLastFiredLabel
}

/**
 * A humanized duration — the pure half of the cooldown readout: seconds
 * under a minute, minutes under an hour, hours above. The screen renders the
 * count through its per-unit template ("45 s" / "5 min" / "2 h").
 */
sealed interface AutomationDurationLabel {
    data class Seconds(val count: Int) : AutomationDurationLabel
    data class Minutes(val count: Int) : AutomationDurationLabel
    data class Hours(val count: Int) : AutomationDurationLabel
}

/**
 * The automation screen's state owner — the pure rule-list transforms and
 * display-string builders over the [AutomationSource] seam, one interface
 * over this phone's settings-store backend and the paired device's
 * automation document. The source owns everything that differs between
 * backends (the persisted truth, the journal read, the test controller, the
 * write gate); this ViewModel owns the derived reads and the rule-list
 * transforms, which fold through [AutomationSchedulePolicy.sanitizeRules]
 * into the source's one write seam ([AutomationSource.saveRules]) — an edit
 * is clamped into the persisted/usable shape with no second gate to bypass.
 * Pure transforms and display strings live in the [Companion] and are
 * unit-tested directly.
 */
class AutomationViewModel(
    private val source: AutomationSource,
) : ViewModel() {

    // Read-side state straight from the source — no per-ViewModel
    // stateIn, no retyped defaults.
    val automationEnabled: StateFlow<Boolean> = source.automationEnabled
    val automationRules: StateFlow<List<AutomationRule>> = source.automationRules

    /** The armed Automation Test's rule id; null = nothing armed. */
    val armedTestRuleId: StateFlow<String?> = source.armedTestRuleId

    /** The bound camera's multi-level flash ceiling (null = no levels / unsupported). */
    val torchStrengthMaxLevel: StateFlow<Int?> = source.torchStrengthMaxLevel

    /**
     * The rule editor's live ambient-light reading ([AutomationSource.illuminance]).
     * Collecting it is the sensor's demand, so only an editor that
     * configures light ([RuleDraft.readsIlluminance]) does.
     */
    val illuminance: StateFlow<AutomationIlluminance> = source.illuminance

    /** False once the rule list is at the cap — the screen greys the add button out. */
    val canAddRule: StateFlow<Boolean> = source.automationRules
        .map { it.size < StreamDefaults.AUTOMATION_MAX_RULES }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether write affordances render at all (the source's role verdict). */
    val canEdit: Boolean = source.canEdit

    /**
     * Rule id → last fired occurrence key — the journal read side, straight
     * from the source (locally the on-disk journal on its poll beat,
     * remotely the document's `lastFiredAt` decoration).
     */
    val lastFiredKeys: StateFlow<Map<String, Long>> = source.lastFiredKeys

    fun setEnabled(enabled: Boolean) = source.setEnabled(enabled)

    // The pending edit overlay: the last rules list sent through [saveRules]
    // (by THIS instance or an older one — see [SharedWrites]), still
    // unacknowledged by the source. The source's automationRules only
    // reflects a write after the backend's round trip (the store's save
    // locally, the PUT answer remotely) — it is NOT updated before another
    // edit can run — so a rapid second edit that read the source directly
    // would build on the pre-edit list and its write would restore what the
    // first edit changed (disabling A then B would leave A enabled). Each
    // edit instead builds on the pending list until the source acknowledges
    // it ([isAcknowledged], watched by the init collector below), so the
    // overlay lives only while that revision is outstanding — or until the
    // write FAILS: the save's handle completes exceptionally and the failed
    // revision retires, so a later edit never silently builds on (and
    // re-saves) an edit the backend refused. Edits run on the main thread
    // (the screen's callbacks), so the shared holder's plain fields are
    // enough.
    private val writes = sharedWritesFor(source)

    init {
        // The overlay's acknowledgment watcher: the source's rule flow
        // retires the pending revision the moment it provably carries it.
        // A newer pending revision is never cleared by an OLDER one's
        // acknowledgment — the comparison runs against the current overlay,
        // which a newer edit has already replaced (and an older revision
        // matching the latest list cannot also match the newer pending
        // unless the edits produced equal lists, where retiring is right).
        viewModelScope.launch {
            source.automationRules.collect { latest ->
                val pending = writes.pendingRules ?: return@collect
                if (isAcknowledged(latest, pending, writes.removedIds)) {
                    writes.pendingRules = null
                    writes.removedIds = emptySet()
                }
            }
        }
    }

    fun addRule(rule: AutomationRule) = persistRules { base -> withRuleAdded(base, rule) }

    fun updateRule(rule: AutomationRule) = persistRules { base -> withRuleUpdated(base, rule) }

    fun removeRule(ruleId: String) = persistRules { base -> withRuleRemoved(base, ruleId) }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) =
        persistRules { base -> withRuleEnabled(base, ruleId, enabled) }

    fun duplicateRule(rule: AutomationRule, copyName: String) =
        persistRules { base -> withRuleDuplicated(base, rule, copyName) }

    /**
     * The list the next edit transforms: the source's current rules, unless
     * an earlier edit's write is still pending — then the pending list (the
     * source is not current until that write's truth lands). A pending write
     * the source has already acknowledged retires here (belt-and-braces:
     * the init collector clears it on the flow beat).
     */
    private fun baseRules(): List<AutomationRule> {
        val pending = writes.pendingRules ?: return source.automationRules.value
        val latest = source.automationRules.value
        return if (isAcknowledged(latest, pending, writes.removedIds)) latest else pending
    }

    /**
     * The Automation Test: fire [ruleId]'s actions once (snapshot held), or
     * wind the armed test back. The backend verifies the id against the
     * persisted rules, so an unknown id is a logged no-op — the card action
     * only appears for rules that exist.
     */
    fun runRuleTest(ruleId: String) = source.runRuleTest(ruleId)

    fun restoreRuleTest() = source.restoreRuleTest()

    /**
     * The single persist seam: every rules edit enqueues its transform onto
     * the serialized write chain ([SharedWrites.writeChain]). The write
     * coroutine builds its list on [baseRules] only after the preceding
     * write has settled — so the base is the latest persisted rules (or the
     * pending overlay in the acknowledged-write round-trip gap), sanitizes,
     * saves — and parks the sanitized list as the pending edit state
     * [baseRules] builds on until the source's flows acknowledge it (or the
     * write fails).
     */
    private fun persistRules(build: (List<AutomationRule>) -> List<AutomationRule>) {
        if (source.rulesReady.value) {
            enqueuePersist(build)
            return
        }
        // The backend's rule truth has not loaded: the source's flow still
        // carries its placeholder (empty) list, and building the edit on it
        // would save that placeholder wholesale — a wipe of the device's
        // rules. The wait is deliberately UNBOUNDED (cancellation aside): a
        // timed-out wait silently DROPPED the edit — the user's save would
        // vanish with no error anywhere. The edit stays parked here until
        // the backend answers or the user leaves the screen (the ViewModel's
        // scope tears down); once ready, the existing save and error
        // behavior takes over unchanged.
        viewModelScope.launch {
            source.rulesReady.first { it }
            enqueuePersist(build)
        }
    }

    /** Links one edit's write onto the chain: it starts when the previous write settles. */
    private fun enqueuePersist(build: (List<AutomationRule>) -> List<AutomationRule>) {
        val previous = writes.writeChain
        writes.writeChain = viewModelScope.launch {
            previous?.join()
            resyncIfNeeded()
            val base = baseRules()
            persistNow(build(base), base)
        }
    }

    /**
     * The ambiguous-write reconcile ([SharedWrites.needsResync]): a refused
     * or dropped write may have committed anyway (a lost PUT reply), so the
     * next edit must not build on the pre-failure mirror — its wholesale
     * save would carry a stale rule set and restore a remotely committed
     * removal. Wait (bounded — an unreachable backend must not park the
     * queued edit forever) for the source's next authoritative emission and
     * let the chain's [baseRules] read it; on timeout the current mirror is
     * the freshest visible truth and serves as the base.
     */
    private suspend fun resyncIfNeeded() {
        if (!writes.needsResync) return
        writes.needsResync = false
        withTimeoutOrNull(RESYNC_TIMEOUT_MS) {
            source.automationRules.drop(1).first()
        }
    }

    private suspend fun persistNow(rules: List<AutomationRule>, base: List<AutomationRule>) {
        val sanitized = AutomationSchedulePolicy.sanitizeRules(rules)
        writes.pendingRules = sanitized
        writes.removedIds = base.mapNotNull { it.id }.toSet() - sanitized.mapNotNull { it.id }.toSet()
        val handle = source.saveRules(sanitized)
        // The write outlives this ViewModel (it runs on the source's own
        // scope), so its verdict must be observed even when the awaiting
        // coroutine is gone: a refusal landing after a route change abandoned
        // the await must still retire the shared revision, or a later
        // instance's edit would build on (and re-save) it. Success retires
        // nothing here — the overlay holds until the flows acknowledge it,
        // because the read side can lag the settle.
        handle.invokeOnCompletion { cause ->
            if (cause != null && writes.pendingRules === sanitized) retireRevision()
        }
        try {
            handle.await()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // The WRITE HANDLE was cancelled out of band — the source's
                // write scope died or the round trip was abandoned — while
                // this ViewModel's coroutine is still active. The write will
                // never settle observably, so retire the overlay exactly
                // like a refused write (the completion listener above has
                // usually done it; same verdict, same guard) and flag the
                // next edit's authoritative reconcile. The ViewModel's OWN
                // cancellation (an inactive context — the scope tearing
                // down) still propagates.
                if (writes.pendingRules === sanitized) retireRevision()
            } else {
                throw e
            }
        } catch (_: Exception) {
            // The backend refused or dropped the write: the completion
            // listener above already retired the failed revision and flagged
            // the resync — a later edit must neither build on (and re-save)
            // it nor skip reconciling with the authoritative rules.
        }
    }

    /** Retires the outstanding revision and flags the next edit's authoritative reconcile. */
    private fun retireRevision() {
        writes.pendingRules = null
        writes.removedIds = emptySet()
        writes.needsResync = true
    }

    class Factory(
        /** The backend for this route's device argument (local when absent). */
        private val source: AutomationSource,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AutomationViewModel(source) as T
        }
    }

    /**
     * The write coordination shared by every ViewModel instance over the
     * same [AutomationSource]: the screen's `viewModel()` is
     * backstack-entry scoped, so a route away-and-back builds a FRESH
     * instance over the ONE app-scoped source while the old instance's
     * write may still be unsettled (it runs on the source's own scope and
     * survives the instance). Per-instance chain and overlay state would let
     * the new instance's first edit build on the pre-write mirror and
     * wholesale-save it — resurrecting what the older write had just removed
     * (remove A, navigate away and back, remove B: A comes back). Keying the
     * overlay, the chain, and the resync flag by the source keeps both
     * instances in ONE serialization: the next edit's transform applies to
     * the unsettled revision, and both edits land. Edits and the
     * acknowledgment watcher run on the main thread (the screen's
     * callbacks), so the holder's plain fields are enough; the map is only
     * a lookup aid.
     */
    private class SharedWrites {
        /** The outstanding revision's list — [AutomationViewModel.baseRules]' base. */
        var pendingRules: List<AutomationRule>? = null

        /** The ids [pendingRules] dropped — the acknowledgment's removal evidence. */
        var removedIds: Set<String> = emptySet()

        /** The last enqueued edit's write coroutine (the serialization chain). */
        var writeChain: Job? = null

        /**
         * Set when a write settles ambiguously (refused, dropped, or a lost
         * reply that may have committed anyway): the next edit must
         * reconcile with the authoritative rules before transforming.
         */
        var needsResync: Boolean = false
    }

    companion object {

        /**
         * The bounded wait for an authoritative emission during
         * [AutomationViewModel.resyncIfNeeded]'s reconcile: the remote beat
         * re-fetches within a poll interval, a healthy device answers in
         * this span, and a backend that never emits again leaves the current
         * mirror (already the freshest visible truth) as the base rather
         * than parking the queued edit forever.
         */
        private const val RESYNC_TIMEOUT_MS = 5_000L

        /** One [SharedWrites] per source — app-scoped singletons, so keyed by identity. */
        private val sharedWrites: MutableMap<AutomationSource, SharedWrites> =
            Collections.synchronizedMap(WeakHashMap<AutomationSource, SharedWrites>())

        private fun sharedWritesFor(source: AutomationSource): SharedWrites =
            synchronized(sharedWrites) {
                sharedWrites[source] ?: SharedWrites().also { sharedWrites[source] = it }
            }

        // ── pure rule-list transforms (JVM-tested) ──

        /**
         * Whether a draft may save: the engine fires nothing without at least
         * one action, so an action-less rule is the one invalid shape. The
         * editor's Save gate and its inline THEN-section error share this
         * verdict.
         */
        fun isValidDraft(draft: AutomationRule): Boolean = draft.actions.isNotEmpty()

        /**
         * Append [rule] unless the list is already at [StreamDefaults.AUTOMATION_MAX_RULES].
         * An id-less rule (a fresh draft) gets a minted UUID — a rule without
         * an id could never be journaled by the schedule engine.
         */
        fun withRuleAdded(rules: List<AutomationRule>, rule: AutomationRule): List<AutomationRule> =
            if (rules.size >= StreamDefaults.AUTOMATION_MAX_RULES) rules
            else rules + rule.copy(id = rule.id ?: UUID.randomUUID().toString())

        /**
         * A duplicate of [source], inserted right after it: a fresh UUID (the
         * journal key must never be shared — a duplicate would inherit the
         * original's dedupe history), the [copyName] the caller resolved
         * ("Copy of …" needs localized resources, so the screen renders it),
         * and a fresh enabled state. At the cap this is a no-op, like
         * [withRuleAdded].
         */
        fun withRuleDuplicated(
            rules: List<AutomationRule>,
            source: AutomationRule,
            copyName: String,
        ): List<AutomationRule> {
            val id = source.id ?: return rules
            if (rules.size >= StreamDefaults.AUTOMATION_MAX_RULES) return rules
            val index = rules.indexOfFirst { it.id == id }
            if (index < 0) return rules
            val copy = source.copy(
                id = UUID.randomUUID().toString(),
                name = copyName,
                enabled = true,
            )
            return rules.toMutableList().apply { add(index + 1, copy) }
        }

        /** Replace the entry with [rule]'s id; unknown or id-less rules are no-ops. */
        fun withRuleUpdated(rules: List<AutomationRule>, rule: AutomationRule): List<AutomationRule> {
            val id = rule.id ?: return rules
            if (rules.none { it.id == id }) return rules
            return rules.map { if (it.id == id) rule else it }
        }

        fun withRuleRemoved(rules: List<AutomationRule>, ruleId: String): List<AutomationRule> =
            rules.filterNot { it.id == ruleId }

        fun withRuleEnabled(
            rules: List<AutomationRule>,
            ruleId: String,
            enabled: Boolean,
        ): List<AutomationRule> = rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }

        /**
         * Whether the source's emitted [latest] rules already carry the
         * pending write — [pending], which dropped [removedIds] — so the
         * overlay may retire onto [latest] as the next edit's base.
         *
         * Equality with the read-only `lastFiredAt` fire stamp stripped is
         * the exact echo (the remote document decorates rules with it and
         * [AutomationSchedulePolicy.sanitizeRules] resets it, so the raw
         * lists can differ on that read-only field alone while every
         * configured value matches). Supersession is the conflict-aware
         * half: every kept rule verbatim in [latest] AND every removed id
         * still absent means the emission's snapshot provably post-dates the
         * write — a pre-write snapshot cannot carry a kept rule's NEW
         * content, nor miss a rule the base had. Equality alone would wedge
         * the overlay shut forever when a concurrent writer changed the
         * document after our commit (a web console adding rule C: the echo
         * of our edit never lands as an exact match), and every later edit
         * would re-save the stale overlay wholesale — wiping C; retirement
         * must wait for the authoritative list either way (the read side
         * lags the settle), but when it does land it may already be past
         * the write. The removal evidence keeps the predicate sound: for a
         * removal, a PRE-write snapshot trivially contains every kept rule,
         * so "removed ids still absent" is what separates it from the
         * post-write truth.
         */
        fun isAcknowledged(
            latest: List<AutomationRule>,
            pending: List<AutomationRule>,
            removedIds: Set<String> = emptySet(),
        ): Boolean {
            val stripped = latest.map { it.copy(lastFiredAt = null) }
            if (stripped == pending) return true
            if (!stripped.containsAll(pending)) return false
            return removedIds.none { id -> latest.any { it.id == id } }
        }

        /**
         * The params a freshly picked action starts with: every required param
         * at its catalog default, optional ones absent (their absence IS the
         * "leave unchanged" semantics the executor honors).
         */
        fun defaultActionParams(typeId: String): Map<String, String> =
            defaultSpecParams(AutomationActionCatalog.typeFor(typeId)?.params)

        /** [defaultActionParams] over the trigger catalog. */
        fun defaultTriggerParams(typeId: String): Map<String, String> =
            defaultSpecParams(AutomationTriggerCatalog.typeFor(typeId)?.params)

        /** [defaultActionParams] over the condition catalog. */
        fun defaultConditionParams(typeId: String): Map<String, String> =
            defaultSpecParams(AutomationConditionCatalog.typeFor(typeId)?.params)

        /** The shared required-params-at-defaults fold behind all three catalogs. */
        private fun defaultSpecParams(params: List<AutomationParamSpec>?): Map<String, String> =
            params
                ?.filterNot { it.optional }
                ?.associate { it.name to it.defaultValue }
                ?: emptyMap()

        /**
         * One canonical param write on a string-typed params map: a null/empty
         * value removes the key (absent optional param = "leave unchanged"),
         * any other value is stored verbatim — callers pass already-canonical
         * strings from [canonicalIntValue]/[canonicalFloatValue]. The shared
         * write behind the action/trigger/condition spec copies below.
         */
        fun withSpecParam(
            params: Map<String, String>,
            name: String,
            value: String?,
        ): Map<String, String> {
            val mutable = params.toMutableMap()
            if (value.isNullOrEmpty()) mutable.remove(name) else mutable[name] = value
            return mutable
        }

        /** [withSpecParam] folded onto an action spec copy. */
        fun withActionParam(
            action: AutomationActionSpec,
            name: String,
            value: String?,
        ): AutomationActionSpec = action.copy(params = withSpecParam(action.params, name, value))

        /** [withSpecParam] folded onto a condition spec copy. */
        fun withConditionParam(
            condition: AutomationConditionSpec,
            name: String,
            value: String?,
        ): AutomationConditionSpec = condition.copy(params = withSpecParam(condition.params, name, value))

        /**
         * INT slider value → canonical wire string (whole number, no
         * decimals). Delegates to the catalog's canonical INT rendering —
         * one renderer, so what the editor shows is what the sanitizer
         * accepts. A slider only produces finite floats, so the catalog's
         * null (unparseable/non-finite raw) cannot occur.
         */
        fun canonicalIntValue(value: Float): String =
            requireNotNull(AutomationActionCatalog.canonicalIntValue(value.toString())) {
                "slider INT values are finite: $value"
            }.toString()

        /**
         * FLOAT slider value → canonical wire string: rounded to one decimal,
         * integral values losing the trailing ".0" (so a saved "1" matches the
         * documented default "1"). Delegates to the catalog's canonical FLOAT
         * rendering for the same single-renderer reason as [canonicalIntValue].
         */
        fun canonicalFloatValue(value: Float): String =
            requireNotNull(AutomationActionCatalog.canonicalFloatValue(value.toString())) {
                "slider FLOAT values are finite: $value"
            }

        /**
         * The catalog's display ceiling for torch strength: the torch action's
         * strength param max. The catalog owns the bound — a missing or
         * unbounded torch.strength is a contract break the catalog test
         * catches, not something to paper over with a magic literal here.
         */
        private val torchStrengthCatalogMax: Int =
            requireNotNull(
                AutomationActionCatalog.typeFor("torch")
                    ?.params?.firstOrNull { it.name == "strength" }?.max?.toInt(),
            ) { "catalog must declare a bounded torch.strength max" }

        /**
         * The torch strength slider's 1..max span: the device's multi-level
         * flash ceiling capped by the catalog's display bound. With no device
         * verdict yet the full catalog span shows.
         */
        fun torchStrengthBounds(deviceMaxLevel: Int?): IntRange =
            1..minOf(torchStrengthCatalogMax, deviceMaxLevel ?: torchStrengthCatalogMax).coerceAtLeast(1)

        /**
         * Whether the strength editor renders at all: only a KNOWN verdict of
         * unsupported/single-level (max <= 1) hides it — a null verdict means
         * the camera has not reported yet, so the editor shows and rides the
         * catalog span from [torchStrengthBounds] until the device answers.
         */
        fun showsTorchStrengthEditor(deviceMaxLevel: Int?): Boolean =
            deviceMaxLevel == null || deviceMaxLevel > 1

        fun clampTorchStrength(value: Int, deviceMaxLevel: Int?): Int =
            value.coerceIn(torchStrengthBounds(deviceMaxLevel))

        /**
         * The widest INT span that still maps one slider tick — and one snap
         * position — per value. Past it the thumb cannot resolve single
         * values anyway, and Material's slider draws one tick dot PER STEP
         * on every frame (a lux or day-long duration span would be tens of
         * thousands of draw calls per frame), so wider spans ride a
         * continuous slider: the value still lands on whole numbers through
         * [canonicalIntValue], and [sliderIsWide] opens the typed entry.
         */
        private const val SLIDER_TICK_SPAN_CAP = 100

        /** The Material slider's `steps` for one numeric span — see [SLIDER_TICK_SPAN_CAP]. */
        fun sliderSteps(min: Float, max: Float, integer: Boolean): Int {
            if (!integer) return 0
            val span = (max - min).toInt()
            return if (span <= SLIDER_TICK_SPAN_CAP) (span - 1).coerceAtLeast(0) else 0
        }

        /** Whether the span is wider than a slider can resolve, so its value is typeable too. */
        fun sliderIsWide(min: Float, max: Float): Boolean = (max - min).toInt() > SLIDER_TICK_SPAN_CAP

        /**
         * The rule editor's live lux readout for one [AutomationSource.illuminance]
         * value: the measured reading once the sensor has published one; the
         * no-sensor notice only on a KNOWN "no sensor" verdict; the waiting
         * line while the reading is unknown — a sensor still establishing
         * its first (debounced) reading, or a device that has not answered.
         */
        fun illuminanceReadout(reading: AutomationIlluminance): AutomationSummaryLine = when {
            reading.lux != null ->
                AutomationSummaryLine(R.string.automation_illuminance_now, listOf(reading.lux.toString()))
            reading.sensorAvailable == false ->
                AutomationSummaryLine(R.string.automation_illuminance_unavailable)
            else ->
                AutomationSummaryLine(R.string.automation_illuminance_waiting)
        }

        // ── display helpers (JVM-tested) ──

        /** "HH:mm" over a 24-hour clock from a minute-of-day, clamped into 0..1439. */
        fun formatMinuteOfDay(minuteOfDay: Int): String {
            val minute = minuteOfDay.coerceIn(0, StreamDefaults.MINUTES_PER_DAY - 1)
            return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60)
        }

        fun isEveryDay(daysMask: Int): Boolean = daysMask == MotionArmingPolicy.ALL_DAYS_MASK

        /** The armed ISO day indices (0 = Monday … 6 = Sunday) in a day mask. */
        fun armedDays(daysMask: Int): List<Int> =
            (0..6).filter { MotionArmingPolicy.isArmedDay(daysMask, it) }

        /**
         * The "Last ran …" bucket for a rule's decoded fire instant vs [nowMs]
         * in [zone]: today and yesterday carry the local minute-of-day for
         * their HH:mm templates; anything older carries the epoch instant for
         * a locale-formatted date. A non-positive instant (never fired) is
         * null — the card shows nothing.
         *
         * Local days and minute-of-day come from [GalleryDates]/[Calendar] —
         * java.time is API 26+ and minSdk is 23 (the GalleryDates doc spells
         * out the convention); the epoch-day gap matches
         * `ChronoUnit.DAYS.between`'s local-date difference exactly.
         */
        fun lastFiredLabel(lastFiredAtMs: Long, nowMs: Long, zone: TimeZone): AutomationLastFiredLabel? {
            if (lastFiredAtMs <= 0) return null
            val dayGap = GalleryDates.epochDayOf(nowMs, zone) - GalleryDates.epochDayOf(lastFiredAtMs, zone)
            val fired = Calendar.getInstance(zone).apply { timeInMillis = lastFiredAtMs }
            val minuteOfDay = fired.get(Calendar.HOUR_OF_DAY) * 60 + fired.get(Calendar.MINUTE)
            return when {
                dayGap <= 0L -> AutomationLastFiredLabel.Today(minuteOfDay)
                dayGap == 1L -> AutomationLastFiredLabel.Yesterday(minuteOfDay)
                else -> AutomationLastFiredLabel.Earlier(lastFiredAtMs, minuteOfDay)
            }
        }

        /**
         * Humanize a duration: seconds under a minute, minutes under an hour
         * (rounded, minimum 1), hours above (rounded). The cooldown presets
         * land exactly; slider values land on the nearest unit.
         */
        fun humanizeDuration(seconds: Int): AutomationDurationLabel {
            val clamped = seconds.coerceAtLeast(0)
            return when {
                clamped < 60 -> AutomationDurationLabel.Seconds(clamped)
                clamped < 3_600 -> AutomationDurationLabel.Minutes(((clamped + 30) / 60).coerceAtLeast(1))
                else -> AutomationDurationLabel.Hours(((clamped + 1_800) / 3_600).coerceAtLeast(1))
            }
        }

        /**
         * The rule-card/preview "IF MISSED" summary line for a rule's
         * missed-run policy, or null when the row stays off the card: the
         * default catch-up policy is the historical behavior and gets no line,
         * and event triggers cannot miss (an event fires in the present). The
         * window lands in the same rounded unit [humanizeDuration] picks —
         * hours above the hour, minutes below (a sanitized rule never lands
         * under a minute, so the seconds template has no case here).
         */
        fun missedRunSummaryLine(rule: AutomationRule): AutomationSummaryLine? {
            if (rule.triggerType != AutomationTriggerKind.TIME_OF_DAY) return null
            return when (rule.missedRunPolicy) {
                MissedRunPolicy.SKIP ->
                    AutomationSummaryLine(R.string.automation_missed_skip_summary)
                MissedRunPolicy.WINDOW ->
                    when (val label = humanizeDuration(rule.missedWindowMinutes * 60)) {
                        is AutomationDurationLabel.Hours -> AutomationSummaryLine(
                            R.string.automation_missed_window_summary_hours,
                            listOf(label.count.toString()),
                        )
                        is AutomationDurationLabel.Minutes -> AutomationSummaryLine(
                            R.string.automation_missed_window_summary_minutes,
                            listOf(label.count.toString()),
                        )
                        // A sanitized rule never lands under a minute; an
                        // unsanitized draft floors at 1, the readout's own rule.
                        is AutomationDurationLabel.Seconds -> AutomationSummaryLine(
                            R.string.automation_missed_window_summary_minutes,
                            listOf("1"),
                        )
                    }
                else -> null
            }
        }

        // ── trigger/condition display strings (id → resource, JVM-tested) ──

        /** Trigger id → pretty picker label; unknown ids fall to the unknown line. */
        private val TRIGGER_TYPE_LABELS: Map<String, Int> = mapOf(
            AutomationTriggerCatalog.TIME_OF_DAY to R.string.automation_trigger_time_of_day,
            AutomationTriggerCatalog.MOTION_DETECTED to R.string.automation_trigger_motion,
            AutomationTriggerCatalog.SOUND_DETECTED to R.string.automation_trigger_sound,
            AutomationTriggerCatalog.TAMPER_DETECTED to R.string.automation_trigger_tamper,
            AutomationTriggerCatalog.ILLUMINANCE to R.string.automation_trigger_illuminance,
            AutomationTriggerCatalog.CHARGING_CHANGED to R.string.automation_trigger_charging,
            AutomationTriggerCatalog.BATTERY_LEVEL to R.string.automation_trigger_battery,
            AutomationTriggerCatalog.STREAMING_CHANGED to R.string.automation_trigger_streaming,
            AutomationTriggerCatalog.VIEWERS_CHANGED to R.string.automation_trigger_viewers,
            AutomationTriggerCatalog.APP_STARTED to R.string.automation_trigger_app_started,
        )

        /** Condition id → pretty picker label; unknown ids fall to the unknown line. */
        private val CONDITION_TYPE_LABELS: Map<String, Int> = mapOf(
            AutomationConditionCatalog.TIME_WINDOW to R.string.automation_condition_time_window,
            AutomationConditionCatalog.CHARGING to R.string.automation_condition_charging,
            AutomationConditionCatalog.BATTERY_LEVEL to R.string.automation_condition_battery,
            AutomationConditionCatalog.ILLUMINANCE to R.string.automation_condition_illuminance,
            AutomationConditionCatalog.POWER_SAVE to R.string.automation_condition_power_save,
            AutomationConditionCatalog.WIFI_CONNECTED to R.string.automation_condition_wifi,
            AutomationConditionCatalog.STREAMING to R.string.automation_condition_streaming,
            AutomationConditionCatalog.VIEWERS to R.string.automation_condition_viewers,
            AutomationConditionCatalog.TORCH to R.string.automation_condition_torch,
            AutomationConditionCatalog.RECORDING to R.string.automation_condition_recording,
            AutomationConditionCatalog.THERMAL to R.string.automation_condition_thermal,
            AutomationConditionCatalog.MOTION_DETECTION to R.string.automation_condition_motion_detection,
        )

        /** The pretty picker label for one catalog trigger id. */
        fun triggerTypeLabelRes(triggerTypeId: String): Int =
            TRIGGER_TYPE_LABELS[triggerTypeId] ?: R.string.automation_action_unknown

        /** The pretty picker label for one catalog condition id. */
        fun conditionTypeLabelRes(conditionTypeId: String): Int =
            CONDITION_TYPE_LABELS[conditionTypeId] ?: R.string.automation_action_unknown

        /** The param-label source for trigger param editor rows. */
        fun triggerParamLabelRes(triggerTypeId: String, paramName: String): Int =
            TRIGGER_PARAM_LABEL_OVERRIDES["$triggerTypeId/$paramName"]
                ?: PARAM_LABELS[paramName]
                ?: R.string.automation_param_enabled

        /** The param-label source for condition param editor rows. */
        fun conditionParamLabelRes(conditionTypeId: String, paramName: String): Int =
            CONDITION_PARAM_LABEL_OVERRIDES["$conditionTypeId/$paramName"]
                ?: PARAM_LABELS[paramName]
                ?: R.string.automation_param_enabled

        private val TRIGGER_PARAM_LABEL_OVERRIDES: Map<String, Int> = mapOf(
            "battery_level/level" to R.string.automation_param_battery_threshold,
            "battery_level/when_below" to R.string.automation_param_when_below,
            "illuminance/lux" to R.string.automation_param_lux_threshold,
            "illuminance/when_below" to R.string.automation_param_when_below,
            "charging_changed/charging" to R.string.automation_param_charging_plugged,
            "streaming_changed/streaming" to R.string.automation_param_streaming_active,
            "viewers_changed/connected" to R.string.automation_param_viewers_connected,
        )

        private val CONDITION_PARAM_LABEL_OVERRIDES: Map<String, Int> = mapOf(
            "time_window/start_minute" to R.string.automation_param_window_start,
            "time_window/end_minute" to R.string.automation_param_window_end,
            "time_window/days_mask" to R.string.automation_param_window_days,
            "charging/charging" to R.string.automation_param_charging_plugged,
            "battery_level/min_level" to R.string.automation_param_battery_min,
            "battery_level/max_level" to R.string.automation_param_battery_max,
            "illuminance/min_lux" to R.string.automation_param_lux_min,
            "illuminance/max_lux" to R.string.automation_param_lux_max,
            "power_save/enabled" to R.string.automation_param_power_save_on,
            "wifi_connected/connected" to R.string.automation_param_wifi_connected,
            "streaming/active" to R.string.automation_param_streaming_active,
            "viewers/min_count" to R.string.automation_param_viewers_min,
            "viewers/max_count" to R.string.automation_param_viewers_max,
            "torch/on" to R.string.automation_param_torch_on,
            "recording/active" to R.string.automation_param_recording_active,
            "thermal/normal" to R.string.automation_param_thermal_normal,
            "motion_detection/enabled" to R.string.automation_param_motion,
        )

        // ── action display strings (id → resource, JVM-tested) ──

        /** Action id → pretty picker/editor label; unknown ids fall to the unknown line. */
        private val ACTION_TYPE_LABELS: Map<String, Int> = mapOf(
            "camera_exposure" to R.string.automation_action_camera_exposure,
            "camera_ev" to R.string.automation_action_camera_ev,
            "camera_zoom" to R.string.automation_action_camera_zoom,
            "camera_fps" to R.string.automation_action_camera_fps,
            "camera_lens" to R.string.automation_action_camera_lens,
            "night_vision" to R.string.automation_action_night_vision,
            "torch" to R.string.automation_action_torch,
            "stream" to R.string.automation_action_stream,
            "capture_photo" to R.string.automation_action_capture_photo,
            "recording" to R.string.automation_action_recording,
            "siren" to R.string.automation_action_siren,
            "detection" to R.string.automation_action_detection,
        )

        /**
         * Param name → editor row label. Two names read differently per action
         * type ("index" is a lens index or an EV step; "enabled" names a
         * different device per action), so those carry "typeId/paramName"
         * entries in [PARAM_LABEL_OVERRIDES] that win over the bare-name fall
         * back here.
         */
        private val PARAM_LABELS: Map<String, Int> = mapOf(
            "iso" to R.string.automation_param_iso,
            "exposure_time_ms" to R.string.automation_param_exposure_time_ms,
            "index" to R.string.automation_param_ev_index,
            "ratio" to R.string.automation_param_zoom_ratio,
            "fps" to R.string.automation_param_fps,
            "strength" to R.string.automation_param_strength,
            "duration_seconds" to R.string.automation_param_duration_seconds,
            "motion_enabled" to R.string.automation_param_motion,
            "sound_enabled" to R.string.automation_param_sound,
            "enabled" to R.string.automation_param_enabled,
        )

        /** Type-specific param-label overrides, keyed "typeId/paramName". */
        private val PARAM_LABEL_OVERRIDES: Map<String, Int> = mapOf(
            "camera_lens/index" to R.string.automation_param_lens_index,
            "night_vision/mode" to R.string.automation_param_night_vision_mode,
            "torch/enabled" to R.string.automation_param_torch,
            "stream/enabled" to R.string.automation_param_stream,
            "recording/enabled" to R.string.automation_param_recording,
            "siren/enabled" to R.string.automation_param_siren,
        )

        /** The pretty picker/editor label for one catalog action id. */
        fun actionTypeLabelRes(typeId: String): Int =
            ACTION_TYPE_LABELS[typeId] ?: R.string.automation_action_unknown

        /** The editor row label for one catalog param. */
        fun paramLabelRes(typeId: String, paramName: String): Int =
            PARAM_LABEL_OVERRIDES["$typeId/$paramName"]
                ?: PARAM_LABELS[paramName]
                ?: R.string.automation_param_enabled

        /**
         * The rule-card summary lines for one action, built purely from the
         * catalog id and its params — the screen resolves them through
         * resources; tests pin the ids. One line per meaningful setting (an
         * exposure action with both ISO and exposure time renders two lines;
         * a disabled torch folds its strength away).
         */
        fun actionSummaryLines(type: String, params: Map<String, String>): List<AutomationSummaryLine> {
            val lines = ArrayList<AutomationSummaryLine>()
            when (type) {
                "camera_exposure" -> {
                    // "0" is the auto sentinel for both params (catalog contract).
                    params["iso"]?.let { raw ->
                        if (raw == "0") lines.add(AutomationSummaryLine(R.string.automation_summary_iso_auto))
                        else lines.add(AutomationSummaryLine(R.string.automation_summary_iso, listOf(raw)))
                    }
                    params["exposure_time_ms"]?.let { raw ->
                        if (raw == "0") lines.add(AutomationSummaryLine(R.string.automation_summary_exposure_auto))
                        else lines.add(AutomationSummaryLine(R.string.automation_summary_exposure, listOf(raw)))
                    }
                }
                "camera_ev" -> params["index"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_ev, listOf(it)))
                }
                "camera_zoom" -> params["ratio"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_zoom, listOf(it)))
                }
                "camera_fps" -> params["fps"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_fps, listOf(it)))
                }
                "camera_lens" -> params["index"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_lens, listOf(it)))
                }
                "night_vision" -> params["mode"]?.let { mode ->
                    lines.add(
                        when (mode) {
                            "auto" -> AutomationSummaryLine(R.string.automation_summary_night_vision_auto)
                            "on" -> AutomationSummaryLine(R.string.automation_summary_night_vision_on)
                            else -> AutomationSummaryLine(R.string.automation_summary_night_vision_off)
                        },
                    )
                }
                "torch" -> {
                    if (params["enabled"] != "false") {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_torch_on))
                        params["strength"]?.let {
                            lines.add(AutomationSummaryLine(R.string.automation_summary_torch_strength, listOf(it)))
                        }
                    } else {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_torch_off))
                    }
                }
                "stream" -> lines.add(
                    if (params["enabled"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_stream_start)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_stream_stop)
                    },
                )
                "capture_photo" -> lines.add(AutomationSummaryLine(R.string.automation_summary_photo))
                "recording" -> {
                    if (params["enabled"] == "false") {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_recording_stop))
                    } else {
                        // "0" (or absent) = unbounded — renders as a plain start.
                        val duration = params["duration_seconds"]?.toIntOrNull() ?: 0
                        if (duration > 0) {
                            lines.add(
                                AutomationSummaryLine(R.string.automation_summary_recording_duration, listOf(duration.toString())),
                            )
                        } else {
                            lines.add(AutomationSummaryLine(R.string.automation_summary_recording_start))
                        }
                    }
                }
                "siren" -> {
                    if (params["enabled"] == "false") {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_siren_stop))
                    } else {
                        val duration = params["duration_seconds"]?.toIntOrNull() ?: 0
                        if (duration > 0) {
                            lines.add(
                                AutomationSummaryLine(R.string.automation_summary_siren_duration, listOf(duration.toString())),
                            )
                        } else {
                            lines.add(AutomationSummaryLine(R.string.automation_summary_siren))
                        }
                    }
                }
                "detection" -> {
                    params["motion_enabled"]?.let { raw ->
                        lines.add(
                            if (raw == "true") {
                                AutomationSummaryLine(R.string.automation_summary_motion_on)
                            } else {
                                AutomationSummaryLine(R.string.automation_summary_motion_off)
                            },
                        )
                    }
                    params["sound_enabled"]?.let { raw ->
                        lines.add(
                            if (raw == "true") {
                                AutomationSummaryLine(R.string.automation_summary_sound_on)
                            } else {
                                AutomationSummaryLine(R.string.automation_summary_sound_off)
                            },
                        )
                    }
                }
                else -> lines.add(AutomationSummaryLine(R.string.automation_action_unknown))
            }
            return lines
        }

        /**
         * The rule-card summary lines for a rule's TRIGGER. Time-of-day rules
         * read [] (their card subtitle is the existing HH:mm · days label);
         * every event trigger renders one "when …" line built purely from the
         * catalog id and params, reading through the same defaults the
         * engine's evaluation reads ([AutomationParamValues]-canonical maps).
         */
        fun triggerSummaryLines(rule: AutomationRule): List<AutomationSummaryLine> {
            val params = rule.triggerParams
            val type = rule.triggerType
            val bool = { name: String, default: Boolean ->
                params[name]?.let { it == "true" } ?: default
            }
            return when (type) {
                AutomationTriggerKind.TIME_OF_DAY -> emptyList()
                AutomationTriggerKind.MOTION_DETECTED -> listOf(
                    AutomationSummaryLine(R.string.automation_summary_trigger_motion),
                )
                AutomationTriggerKind.SOUND_DETECTED -> listOf(
                    AutomationSummaryLine(R.string.automation_summary_trigger_sound),
                )
                AutomationTriggerKind.TAMPER_DETECTED -> listOf(
                    AutomationSummaryLine(R.string.automation_summary_trigger_tamper),
                )
                AutomationTriggerKind.ILLUMINANCE -> {
                    val lux = params["lux"]?.toIntOrNull()
                        ?: StreamDefaults.LIGHT_SENSOR_TRIGGER_DEFAULT_LUX
                    listOf(
                        if (bool("when_below", true)) {
                            AutomationSummaryLine(R.string.automation_summary_trigger_illuminance_below, listOf(lux.toString()))
                        } else {
                            AutomationSummaryLine(R.string.automation_summary_trigger_illuminance_above, listOf(lux.toString()))
                        },
                    )
                }
                AutomationTriggerKind.CHARGING_CHANGED ->
                    listOf(
                        if (bool("charging", true)) {
                            AutomationSummaryLine(R.string.automation_summary_trigger_charging_on)
                        } else {
                            AutomationSummaryLine(R.string.automation_summary_trigger_charging_off)
                        },
                    )
                AutomationTriggerKind.BATTERY_LEVEL -> {
                    val level = params["level"]?.toIntOrNull() ?: 20
                    listOf(
                        if (bool("when_below", true)) {
                            AutomationSummaryLine(R.string.automation_summary_trigger_battery_below, listOf(level.toString()))
                        } else {
                            AutomationSummaryLine(R.string.automation_summary_trigger_battery_above, listOf(level.toString()))
                        },
                    )
                }
                AutomationTriggerKind.STREAMING_CHANGED ->
                    listOf(
                        if (bool("streaming", true)) {
                            AutomationSummaryLine(R.string.automation_summary_trigger_stream_start)
                        } else {
                            AutomationSummaryLine(R.string.automation_summary_trigger_stream_stop)
                        },
                    )
                AutomationTriggerKind.VIEWERS_CHANGED ->
                    listOf(
                        if (bool("connected", true)) {
                            AutomationSummaryLine(R.string.automation_summary_trigger_viewer_join)
                        } else {
                            AutomationSummaryLine(R.string.automation_summary_trigger_viewer_leave)
                        },
                    )
                AutomationTriggerKind.APP_STARTED -> listOf(
                    AutomationSummaryLine(R.string.automation_summary_trigger_app_started),
                )
                else -> emptyList()
            }
        }

        /**
         * The rule-card summary lines for one precondition. The time window's
         * day-mask suffix is NOT rendered here — days need localized day
         * labels, so the screen composes that one line itself (reusing
         * [isEveryDay]/[armedDays]) while the window span renders through the
         * shared [formatMinuteOfDay].
         */
        fun conditionSummaryLines(condition: AutomationConditionSpec): List<AutomationSummaryLine> {
            val params = condition.params
            return when (condition.type) {
                AutomationConditionCatalog.TIME_WINDOW -> {
                    val start = params["start_minute"]?.toIntOrNull() ?: 0
                    val end = params["end_minute"]?.toIntOrNull()
                        ?: (StreamDefaults.MINUTES_PER_DAY - 1)
                    listOf(
                        AutomationSummaryLine(
                            R.string.automation_summary_condition_time_window,
                            listOf(formatMinuteOfDay(start), formatMinuteOfDay(end)),
                        ),
                    )
                }
                AutomationConditionCatalog.CHARGING -> listOf(
                    if (params["charging"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_charging_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_charging_off)
                    },
                )
                AutomationConditionCatalog.BATTERY_LEVEL -> {
                    val min = params["min_level"]?.toIntOrNull()
                    val max = params["max_level"]?.toIntOrNull()
                    listOf(
                        when {
                            min != null && max != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_battery_between, listOf(min.toString(), max.toString()))
                            min != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_battery_min, listOf(min.toString()))
                            max != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_battery_max, listOf(max.toString()))
                            else ->
                                AutomationSummaryLine(R.string.automation_summary_condition_battery_any)
                        },
                    )
                }
                AutomationConditionCatalog.POWER_SAVE -> listOf(
                    if (params["enabled"] == "true") {
                        AutomationSummaryLine(R.string.automation_summary_condition_power_save_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_power_save_off)
                    },
                )
                AutomationConditionCatalog.ILLUMINANCE -> {
                    val min = params["min_lux"]?.toIntOrNull()
                    val max = params["max_lux"]?.toIntOrNull()
                    listOf(
                        when {
                            min != null && max != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_illuminance_between, listOf(min.toString(), max.toString()))
                            min != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_illuminance_min, listOf(min.toString()))
                            max != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_illuminance_max, listOf(max.toString()))
                            else ->
                                AutomationSummaryLine(R.string.automation_summary_condition_illuminance_any)
                        },
                    )
                }
                AutomationConditionCatalog.WIFI_CONNECTED -> listOf(
                    if (params["connected"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_wifi_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_wifi_off)
                    },
                )
                AutomationConditionCatalog.STREAMING -> listOf(
                    if (params["active"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_streaming_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_streaming_off)
                    },
                )
                AutomationConditionCatalog.VIEWERS -> {
                    val min = params["min_count"]?.toIntOrNull()
                    val max = params["max_count"]?.toIntOrNull()
                    listOf(
                        when {
                            min != null && max != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_viewers_between, listOf(min.toString(), max.toString()))
                            min != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_viewers_min, listOf(min.toString()))
                            max != null ->
                                AutomationSummaryLine(R.string.automation_summary_condition_viewers_max, listOf(max.toString()))
                            else ->
                                AutomationSummaryLine(R.string.automation_summary_condition_viewers_any)
                        },
                    )
                }
                AutomationConditionCatalog.TORCH -> listOf(
                    if (params["on"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_torch_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_torch_off)
                    },
                )
                AutomationConditionCatalog.RECORDING -> listOf(
                    if (params["active"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_recording_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_recording_off)
                    },
                )
                AutomationConditionCatalog.THERMAL -> listOf(
                    if (params["normal"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_thermal_normal)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_thermal_throttling)
                    },
                )
                AutomationConditionCatalog.MOTION_DETECTION -> listOf(
                    if (params["enabled"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_condition_motion_on)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_condition_motion_off)
                    },
                )
                else -> emptyList()
            }
        }
    }
}

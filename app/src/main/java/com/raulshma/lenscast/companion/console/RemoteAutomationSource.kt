package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.automation.AutomationIlluminance
import com.raulshma.lenscast.automation.AutomationSource
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.RemoteAutomationState
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The automation screen's REMOTE backend: [AutomationSource] over the paired
 * device's automation document — `GET /api/automation` on a beat for the
 * truth (master toggle, rules, per-rule `lastFiredAt`, armed test id),
 * `PUT /api/automation` for every rules/toggle write (the document persists
 * wholesale, and the PUT's answer — the persisted sanitized truth — becomes
 * the new document, exactly the web console's contract), and
 * `POST /api/automation/test` + `/restore` for the Automation Test. Built by
 * [com.raulshma.lenscast.companion.di.CompanionGraph.automationSource],
 * cached per device beside the other sources.
 *
 * No rule mapping is needed at this seam: the wire rule IS the domain
 * [AutomationRule], decoded/encoded through `AutomationRuleJson`'s instance
 * (the vocabulary enums' wire-id adapters — the client's methods own that),
 * so the editor's draft machinery and card renderers consume the identical
 * values the local backend holds.
 *
 * Lifecycle/visibility contract: plain settings-shaped REST — the source
 * never starts or stops the device session, so a deep-linked automation
 * screen works with no live link. The document poll rides a WhileSubscribed
 * flow (the lastFiredKeys idiom): the beat fetches only while a screen is
 * subscribed, and every write's answer lands in the document directly, so a
 * save renders instantly without waiting out a beat.
 *
 * Write shape: the PUT is a whole-document persist, so [saveRules] merges
 * the caller's list against the last known document's toggle, and
 * [setEnabled] merges against its rules — a stale read can only lose a
 * concurrent device-side change, the same window the web console's
 * GET-merge-PUT carries. Writes serialize through one mutex (a rapid
 * toggle-then-save cannot interleave two PUTs), and a failed write leaves
 * the last known document for the beat to reconcile — its verdict surfaces
 * through the handle [saveRules] returns, so the caller's pending-edit
 * overlay retires the failed revision instead of building later edits on it.
 *
 * Poll/write coordination: the beat's fetch records the write stamp before
 * dialing and applies its response only if no state-changing round trip
 * completed while it was in flight — a poll response that predates a
 * completed write is rejected instead of overwriting the write's
 * acknowledged answer (a 403'd write bumps nothing, so the beat still
 * reconciles). A write that lands before the first successful read defers
 * (bounded) instead of merging its other half from fallback values —
 * a blind first write would clear the device's rules or disable automation.
 *
 * `lastFiredKeys` rides the document's read-only `lastFiredAt` decoration:
 * the device already decoded its journal keys to wall-clock instants, which
 * sit above [com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD]
 * and therefore pass through the screen's decodeOccurrenceKey verbatim —
 * the same "Last ran …" line either backend.
 *
 * The editor's lux readout ([illuminance]) rides `GET /api/status` — the
 * same document the web editor polls for its own readout, at the same beat.
 *
 * * Degraded-to-hidden: [torchStrengthMaxLevel] pins null — the wire
 *   publishes no flash-level reading, which is exactly the "not yet reported"
 *   rendering, so the torch strength editor rides the catalog span.
 *
 * Role gate: [canEdit] is the roster role's verdict (a viewer pairing would
 * 403 every config write; the flag hides the write affordances instead —
 * the [com.raulshma.lenscast.streaming.web.RoleGatePolicy] shape). A gated
 * write is a no-op at the source.
 */
class RemoteAutomationSource(
    /**
     * The device's JSON client; null when the roster lost the device — a
     * settled no-dial state (the local defaults, every write a no-op).
     */
    private val api: LenscastApiClient?,
    /** The roster role's config-write verdict (admin pairings only). */
    override val canEdit: Boolean,
    /** The poll beat (the injectable record; tests shrink it). */
    private val timings: ConsoleTimings = ConsoleTimings(),
    /** The scope the beat and the write round trips run on (the graph's app scope). */
    private val scope: CoroutineScope,
) : AutomationSource {

    // The device's last known document — the write answers patch it directly
    // (a save renders on its own round trip), the beat refreshes it while a
    // screen is subscribed.
    private val _doc = MutableStateFlow<RemoteAutomationState?>(null)

    /** Monotonic beat tick: each fetch bumps it, so the StateFlow never conflates two beats. */
    private val beat: StateFlow<Long> = flow {
        var tick = 0L
        while (true) {
            fetchDoc()
            emit(++tick)
            delay(timings.automationPollMs)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), 0L)

    /** One merged view: re-emitted by every beat tick and every write answer. */
    private val doc: Flow<RemoteAutomationState?> =
        merge(beat, _doc).map { _doc.value }

    override val automationRules: StateFlow<List<AutomationRule>> = doc
        .map { it?.rules.orEmpty() }
        .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), emptyList())

    /**
     * Whether the initial document read has landed — the rule-list edits'
     * build-base verdict ([AutomationSource.rulesReady]): before it, the
     * rules flow's emptyList is the placeholder, and a whole-document save
     * built on it would wipe the device's configured rules. The verdict
     * rides the RULES FLOW itself (not `_doc` alone): two derived stateIns
     * publish through separate collector coroutines, so a `_doc`-only
     * verdict could be observed true while the rules flow still held the
     * placeholder — the combine only answers true once the rules flow
     * carries a value derived from a loaded document, or the loaded
     * document itself has no rules (where placeholder and truth are the
     * same empty list). A skew evaluation answers false — conservative, it
     * just holds the edit until the catch-up emission re-fires the combine.
     */
    override val rulesReady: StateFlow<Boolean> =
        combine(_doc, automationRules) { state, rules ->
            state != null && (rules.isNotEmpty() || state.rules.isEmpty())
        }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), false)

    override val automationEnabled: StateFlow<Boolean> = doc
        .map { it?.enabled ?: false }
        .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), false)

    override val lastFiredKeys: StateFlow<Map<String, Long>> = doc
        .map { state -> state?.rules.orEmpty().lastFiredKeys() }
        .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), emptyMap())

    override val armedTestRuleId: StateFlow<String?> = doc
        .map { it?.testArmedRuleId }
        .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), null)

    // No flash-level carrier on the wire: the "not yet reported" rendering.
    override val torchStrengthMaxLevel: StateFlow<Int?> = MutableStateFlow(null)

    /**
     * Polled off `GET /api/status` on its own WhileSubscribed beat, and the
     * beat IS the demand on the far side: every status GET arms the paired
     * device's dashboard demand pulse (LightSensorPolicy's status-pulse
     * gate, longer than this beat), so an editor showing the lux readout
     * keeps that device's sensor sampled with no route of its own. A failed
     * poll keeps the last reading — a read must never blank a value the
     * editor is showing.
     */
    override val illuminance: StateFlow<AutomationIlluminance> = flow {
        while (true) {
            readIlluminance()?.let { emit(it) }
            delay(timings.luxPollMs)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), AutomationIlluminance.UNKNOWN)

    private suspend fun readIlluminance(): AutomationIlluminance? {
        val client = api ?: return null
        val status = try {
            client.getStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null // a read: transient failure keeps the last reading
        }
        return AutomationIlluminance(lux = status.illuminanceLux, sensorAvailable = status.lightSensorAvailable)
    }

    private suspend fun fetchDoc() {
        val client = api ?: return
        // Stamp before dialing: a response is only fresh if no write (or
        // state-changing test round trip) completed while this poll was in
        // flight — one that did would otherwise overwrite the write's
        // acknowledged answer with pre-write device state.
        val startGeneration = writeGeneration.get()
        val fetched = try {
            client.getAutomation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // a read: transient failure keeps the last document
        }
        writeMutex.withLock {
            if (writeGeneration.get() == startGeneration) _doc.value = fetched
        }
    }

    /**
     * The in-mutex fetch behind [runRuleTest]/[restoreRuleTest]'s immediate
     * reconcile and [needsReconcile]'s re-read: the caller holds
     * [writeMutex], so no write can interleave and the answer is fresh by
     * construction. True when the fetch landed (the document was refreshed).
     */
    private suspend fun fetchDocLocked(): Boolean {
        val client = api ?: return false
        val fetched = try {
            client.getAutomation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false // a read: transient failure keeps the last document
        }
        _doc.value = fetched
        return true
    }

    /** The writes' serialization: one PUT (or test round trip) at a time. */
    private val writeMutex = Mutex()

    /**
     * Set (under [writeMutex]) by a write that failed without the device's
     * verdict — the answer lost after the request may have persisted — and
     * cleared by the next write once it has re-read the authoritative
     * document (kept set if that re-read also fails, so the merge never
     * rides a document the device may have moved past).
     */
    private var needsReconcile = false

    /**
     * The device document's change stamp: bumped (under [writeMutex]) after
     * every round trip that changed the device's automation truth — an
     * applied PUT, a successful test arm, a restore — so a poll response
     * issued before the change is rejected at apply time. A failed write
     * bumps nothing (the device's truth is unchanged; the beat still
     * reconciles over it).
     */
    private val writeGeneration = AtomicInteger(0)

    /**
     * The whole-document write behind [setEnabled]/[saveRules]: the PATCH's
     * submitted half rides verbatim, the omitted half merges against the
     * loaded document — re-read from the device first when a predecessor
     * failed ambiguously (see [needsReconcile]). Returns a handle the caller
     * can observe: it completes when the write settles and completes
     * EXCEPTIONALLY when the write failed or was dropped — the caller's
     * pending-edit overlay retires the failed revision instead of building
     * later edits on it. The failure is completed on the handle only (no
     * parent job): the app scope's own lanes are untouched, and the beat
     * still reconciles the device's truth.
     */
    private fun putDoc(enabled: Boolean?, rules: List<AutomationRule>?): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        val client = api
        if (client == null || !canEdit) {
            // The documented settled no-op (roster lost the device / a
            // viewer-gated write) — observed as a completed write, not a
            // failure.
            done.complete(Unit)
            return done
        }
        val job = scope.launch {
            try {
                // Defer until the first read lands: with no document there is
                // nothing to merge the patch's other half against, and fallback
                // values would send "disabled / no rules" — a blind wipe. The
                // wait runs OUTSIDE the mutex so the beat's own fetch (which
                // needs the mutex) can fill the document; on timeout (the device
                // never answers) the write is dropped rather than guessed —
                // surfaced as a failure so the caller's overlay retires it.
                withTimeoutOrNull(FIRST_DOC_TIMEOUT_MS) { _doc.first { it != null } }
                    ?: throw IOException("automation write dropped: the device never answered its first read")
                writeMutex.withLock {
                    // A predecessor that failed ambiguously (the answer lost
                    // after the device may have persisted) leaves the in-memory
                    // document unreliable: re-read the authoritative one before
                    // merging, or this write's omitted half could send a stale
                    // `enabled` (or stale rules) over the device's committed
                    // state — undoing its toggle.
                    if (needsReconcile) needsReconcile = !fetchDocLocked()
                    val current = _doc.value ?: return@withLock
                    try {
                        // Merge the patch against the last known document — the
                        // whole-document PUT carries both halves. A failed write
                        // leaves the last known document for the beat to
                        // reconcile.
                        _doc.value = client.putAutomation(
                            enabled = enabled ?: current.enabled,
                            rules = rules ?: current.rules,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The outcome is ambiguous: the document may have
                        // persisted before the answer was lost. The next write
                        // reconciles (above) instead of merging the stale
                        // in-memory half — a follow-up saveRules must not send
                        // the old toggle back over the device's committed one.
                        needsReconcile = true
                        throw e
                    }
                    writeGeneration.incrementAndGet()
                }
                done.complete(Unit)
            } catch (e: CancellationException) {
                done.cancel(e)
                throw e
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        // A scope cancelled before the body starts would leave `done`
        // incomplete forever — a caller awaiting the verdict hangs. The
        // launch's completion closes that gap: every settled outcome
        // (success, failure, the in-body cancellation above) has already
        // completed `done` by the time this handler fires, so only the
        // never-ran / killed-before-completing cases land here.
        job.invokeOnCompletion { cause ->
            if (!done.isCompleted) {
                done.cancel(
                    cause as? CancellationException
                        ?: CancellationException("automation write abandoned"),
                )
            }
        }
        return done
    }

    // The toggle's verdict has no caller-side observer (the screen reads the
    // flows it publishes) — the write handle is discarded here; saveRules is
    // the seam that surfaces failures.
    override fun setEnabled(enabled: Boolean) {
        putDoc(enabled = enabled, rules = null)
    }

    override fun saveRules(rules: List<AutomationRule>): Deferred<Unit> =
        putDoc(enabled = null, rules = rules)

    override fun runRuleTest(ruleId: String) {
        val client = api ?: return
        if (!canEdit) return
        scope.launch {
            writeMutex.withLock {
                try {
                    val answer = client.automationTest(ruleId)
                    if (answer.success) {
                        // The arm moved the armed id (and fired the rule's
                        // actions onto the device): stamp the change, then
                        // reconcile immediately. In-flight polls issued
                        // before the arm are stale and reject themselves.
                        writeGeneration.incrementAndGet()
                        fetchDocLocked()
                    }
                    // A failed arm (unknown id) changed nothing server-side.
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed round trip leaves the document as-is.
                }
            }
        }
    }

    override fun restoreRuleTest() {
        val client = api ?: return
        if (!canEdit) return
        scope.launch {
            writeMutex.withLock {
                try {
                    client.automationTestRestore()
                    // The armed id cleared (and the snapshot's values went
                    // back): stamp the change, then reconcile immediately.
                    writeGeneration.incrementAndGet()
                    fetchDocLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Same reconciliation rule as the arm.
                }
            }
        }
    }

    companion object {
        /** The subscribe grace that parks the beat when no screen shows. */
        private const val SUBSCRIBE_GRACE_MS = 5_000L

        /**
         * The bounded wait for the first document before a write gives up:
         * a few seconds — a healthy device answers its first GET in that
         * span, and an unreachable one must not park writes forever.
         */
        private const val FIRST_DOC_TIMEOUT_MS = 5_000L
    }
}

/**
 * The pure wire→display projection: the state document's rules onto the
 * rule-id → last-fired map the cards decode. The wire's `lastFiredAt` is a
 * wall-clock epoch-ms instant (~1.7e12), above the journal's event
 * threshold — so the screen's `decodeOccurrenceKey` passes it through
 * verbatim and the "Last ran …" buckets read identically to the local
 * journal's values. JVM-pure.
 */
internal fun List<AutomationRule>.lastFiredKeys(): Map<String, Long> =
    mapNotNull { rule ->
        val firedAt = rule.lastFiredAt ?: return@mapNotNull null
        rule.id?.let { id -> id to firedAt }
    }.toMap()

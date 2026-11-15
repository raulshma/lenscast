package com.raulshma.lenscast.companion.session

import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.DeviceStatus
import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import com.raulshma.lenscast.companion.client.sse.EventStreamBuffer
import com.raulshma.lenscast.companion.client.sse.PollLadderPolicy
import com.raulshma.lenscast.companion.client.sse.SseEvent
import com.raulshma.lenscast.companion.client.sse.SseEventSource
import com.raulshma.lenscast.companion.client.sse.SseHealth
import com.raulshma.lenscast.companion.client.sse.SseStream
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.core.AppJson
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A device session's lifecycle, as the UI renders it: [IDLE] before the
 * first start, [CONNECTING] while the probe/first poll is in flight,
 * [CONNECTED] once status flows, [RECONNECTING] when the SSE/poll ladder is
 * retrying a dropped link, [OFFLINE] after the retries give up (device
 * unreachable), [AUTH_FAILED] when the remote answered 401 — the re-pair
 * cue, distinct from a network problem.
 */
enum class ConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING, OFFLINE, AUTH_FAILED }

/**
 * Which SSE lane a URL belongs to — the [DeviceSession.laneOpenListener]
 * test seam's discriminator (the transport-switch test asserts the STATUS
 * lane's URL *intent* switched http→https; https cannot actually handshake
 * against a plaintext MockWebServer, so the intent is the observable).
 */
internal enum class LaneKind { STATUS, DETECTION }

/**
 * The session's ladder/poll timing constants, one injectable record (the
 * web numbers they port live in useAppState.ts / useEventStream.ts /
 * sseHealth.ts; the ladder tiers follow RetryBudget's "capped exponential"
 * spirit at the wave brief's 1s→2s→4s→8s→15s→30s values):
 *
 * | Field                 | Default  | Web origin                                            |
 * |-----------------------|----------|-------------------------------------------------------|
 * | [tickMs]              | 1 000 ms | useAppState `tickMs: 1000` (one poll-ladder tick/s)   |
 * | [statusSseRetryMs]    | 3 000 ms | EventSource's default retry interval (the browser auto-reconnects its status stream on roughly this cadence) |
 * | [detectionPollMs]     | 10 000 ms | useEventStream `POLL_INTERVAL_MS`                     |
 * | [detectionSseRetryMs] | 60 000 ms | useEventStream `SSE_RETRY_MS`                         |
 * | [ladderBackoffMs]     | 1k→2k→4k→8k→15k→30k cap | wave brief; RetryBudget's capped-step shape at connect-ladder scale |
 * | [ladderGiveUpAtCapTier] | 3      | wave brief: the 30 s tier repeats ~3× (~2 min total) → OFFLINE |
 *
 * Tests shrink the whole record instead of virtualizing time — this
 * package's tests run on real MockWebServer threads (`runBlocking`, never
 * `runTest`), so a shrunken [SessionTimings] is the determinism seam.
 */
internal data class SessionTimings(
    val tickMs: Long = 1_000L,
    val statusSseRetryMs: Long = 3_000L,
    val detectionPollMs: Long = 10_000L,
    val detectionSseRetryMs: Long = 60_000L,
    val ladderBackoffMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L),
    val ladderGiveUpAtCapTier: Int = 3,
)

/**
 * One paired device's live link: the connection state machine, the
 * conflated status SSE (the latest GET /api/status snapshot, superseded
 * in place — UI reads the newest, never replays), the detection-event feed
 * (deduped, capped, NEWEST-FIRST — see [detectionEvents]), and the JSON API
 * the tab surfaces call through. One [DeviceSession] per device — the
 * manager caches them, so a tab leave/re-entry rides the same live session
 * instead of reconnecting.
 *
 * Implemented in wave 2 (agent F). The shape, ported from the web hooks:
 *
 *  - **Status lane** (`useAppState.ts`): an SSE connection on
 *    `/api/events` whose named `status` frames decode into [status]
 *    (conflated — every arrival supersedes the last), while the
 *    [PollLadderPolicy] STATUS rung polls `GET /api/status` every 3 s ONLY
 *    while the SSE reads unhealthy ([SseHealth]: silent ≥ 10 s is Dead; a
 *    KNOWN failure — socket error or the device's clean lifetime close —
 *    reads unhealthy immediately, the web's `onerror` parity). SSE recovery
 *    returns the session to CONNECTED and the polls stop.
 *  - **Detection lane** (`useEventStream.ts`): an SSE connection on
 *    `/api/detection/events/stream` into an [EventStreamBuffer] (dedupe by
 *    id, cap 200, newest-first). On failure the lane polls
 *    `GET /api/detection/events?limit=50` every 10 s and retries the SSE
 *    after 60 s; poll refreshes merge through the same dedupe, so the
 *    server's connect-time backlog replay never duplicates.
 *  - **Transport switch** (`transport` event): the web RELOADS the page onto
 *    the announced scheme; a live session cannot reload, so instead it
 *    rebuilds in place — a session-local `useTls` override flips in a LOCAL
 *    copy of the roster device (the roster itself is never mutated), a fresh
 *    [LenscastApiClient] + [SseEventSource] are built on the new scheme, and
 *    both lanes re-dial. State (status snapshot, event feed) survives. The
 *    override persists for the session's lifetime — the device's own
 *    announcement is the newest truth about its scheme, and it clears only
 *    on [com.raulshma.lenscast.companion.session.DeviceSessionManager.release]
 *    / process death (a device toggled BACK to the old scheme while the
 *    session was away recovers through re-pair, which refreshes the roster).
 *  - **Connect ladder** (`RetryBudget`'s capped-step spirit at the brief's
 *    tiers): a failed probe retries after 1s→2s→4s→8s→15s→30s(cap) and the
 *    30 s tier repeats 3× (~2 min) before the session settles OFFLINE —
 *    `start()` is the manual retry. While RECONNECTING, status keeps polling
 *    (visibility permitting) so the UI stays warm.
 *  - **Visibility** ([setVisible]): false freezes the ladder ticks, the
 *    polls, and the lane reopen attempts (our documented choice — the web
 *    pauses everything on `document.hidden` while its EventSource stays
 *    open; we keep the same shape but also freeze our reconnect timers,
 *    which the browser's built-in one would have driven). The SSE sockets
 *    themselves stay open; on visible, the freshness re-evaluation catches
 *    a lane that died in the dark and reconnects within one tick. The
 *    connect ladder itself keeps its backoff schedule in the background —
 *    a session healing a link is not a UI activity.
 *
 * A session whose device has no roster entry (removed from another screen
 * mid-session, or a directly-constructed session with no collaborators) has
 * no address to dial: `start()` still walks CONNECTING, then fails fast to
 * OFFLINE — no network call, no retries; the UI may offer manual retry, and
 * [start] re-reads the roster (so a re-added device recovers without
 * releasing the session).
 *
 * Signatures frozen in wave 0; implemented in wave 2; new members may be
 * added (the test seams and [setVisible] below are this wave's additions).
 */
class DeviceSession(val deviceId: String) {

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)

    /** The lifecycle state machine; [ConnectionState.IDLE] until [start]. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus?>(null)

    /** The newest status snapshot; null before the first poll lands. */
    val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    private val _detectionEvents = MutableStateFlow(emptyList<RemoteDetectionEvent>())

    /**
     * The live event feed, NEWEST-FIRST (the [EventStreamBuffer]'s
     * TS-`mergeEvents` ordering — the wave-0 stub's "newest last" phrasing
     * was a slip against the source it ports). UIs that want chronological
     * display reverse the list. Deduped by id and capped at 200; a
     * `stop()`/`start()` cycle keeps the feed (session memory, like the
     * web's signal).
     */
    val detectionEvents: StateFlow<List<RemoteDetectionEvent>> = _detectionEvents.asStateFlow()

    /** The JSON API for this session's device, on the shared engine. */
    fun api(): LenscastApiClient =
        requireClient()
            ?: throw IllegalStateException(
                "No roster entry (or credentials) for device \"$deviceId\" — the session cannot dial " +
                    "its API; connectionState is OFFLINE (see the class KDoc's fail-fast note).",
            )

    /**
     * Opens the link (poll + SSE lanes) and runs the reconnect ladder.
     * Idempotent: a session already CONNECTING/CONNECTED/RECONNECTING
     * ignores the call; IDLE/OFFLINE/AUTH_FAILED start a fresh run (fresh
     * child scope, fresh lanes, [detectionEvents] feed retained).
     */
    fun start() {
        val parent = parentScope
        if (parent == null) {
            // A directly-constructed session has no collaborators (roster,
            // credentials, parent scope) — there is nothing to dial and no
            // scope to run lanes in. Fail fast to OFFLINE so the UI sees a
            // settled "unreachable" state, not a spinner. Direct value write:
            // there are no live lanes whose transitions would need guarding.
            _connectionState.value = ConnectionState.OFFLINE
            return
        }
        val fresh = CoroutineScope(
            // SupervisorJob child of the PARENT's job: the manager's app
            // scope dying (process wind-down) kills this session, but the
            // session's own teardown never touches the parent — one broken
            // session cannot take the app scope or its sibling sessions down.
            parent.coroutineContext.minusKey(Job) + SupervisorJob(parent.coroutineContext[Job]),
        )
        // The CAS is the idempotency gate: non-null means a run is already
        // live (CONNECTING/CONNECTED/RECONNECTING all keep their scope).
        if (!activeScope.compareAndSet(null, fresh)) return
        fresh.launch { runLink() }
        detectionLaneJob = fresh.launch { detectionLaneBody() }
    }

    /**
     * Closes the link and its lanes; the session stays cached for restart.
     * Idempotent; the state flows retain their last values (StateFlow
     * semantics) and the state resets to IDLE — a stopped session is
     * indistinguishable from a never-started one.
     */
    fun stop() {
        teardown()
        transitionTo(ConnectionState.IDLE)
    }

    /**
     * Pauses/resumes the session's activity (see the class KDoc's visibility
     * note): false freezes ladder ticks, polls and lane reopens; true
     * resumes — the freshness check catches up on anything that died in the
     * dark. Default is visible. (Additive, wave 2.)
     */
    fun setVisible(visible: Boolean) {
        this.visible.set(visible)
    }

    // ── Wave-2 internals ──

    /** The collaborators the manager (or a test) wires in post-construction. */
    @Volatile private var roster: DeviceRosterStore? = null
    @Volatile private var credentials: CompanionCredentialStore? = null
    @Volatile private var parentScope: CoroutineScope? = null

    internal fun attach(roster: DeviceRosterStore, credentials: CompanionCredentialStore, parentScope: CoroutineScope) {
        this.roster = roster
        this.credentials = credentials
        this.parentScope = parentScope
    }

    // ── Test seams (additive, wave 2; see SessionTimings' KDoc) ──

    @Volatile internal var timings: SessionTimings = SessionTimings()

    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    @Volatile internal var delayFn: suspend (Long) -> Unit = { delay(it) }

    /**
     * Lane-open observation seam: fires with the exact URL intent right
     * before each SSE dial. The transport-switch test asserts through it —
     * an https URL cannot actually handshake against a plaintext
     * MockWebServer, so the intent (not the exchange) is the observable.
     */
    @Volatile internal var laneOpenListener: ((LaneKind, String) -> Unit)? = null

    /**
     * Probe-intent observation seam: fires with the dial base (scheme/host/
     * port) before each connect-ladder probe. The override-restart test needs
     * it — a restarted session probing an https-override against a plaintext
     * test server never reaches the dispatcher at all, so the intent is once
     * again the only observable.
     */
    @Volatile internal var probeListener: ((String) -> Unit)? = null

    private companion object {
        const val STATUS_EVENT_NAME = "status"
        const val TRANSPORT_EVENT_NAME = "transport"
        const val SCHEME_HTTP = "http"
        const val SCHEME_HTTPS = "https"

        /** web/src/hooks/useAppState.ts CONNECTION_LOST_FAILURES. */
        const val CONNECTION_LOST_FAILURES = 3

        /** The wave brief's poll page size (the server's replay-page shape). */
        const val DETECTION_POLL_LIMIT = 50
    }

    // Session lifecycle: one AtomicReference<CoroutineScope> is the run's
    // whole truth — non-null ⟺ CONNECTING/CONNECTED/RECONNECTING, and the
    // terminal states (OFFLINE/AUTH_FAILED) + stop() tear it back down, so
    // the state machine and the coroutine lifetime can never disagree.
    private val activeScope = AtomicReference<CoroutineScope?>(null)

    private val visible = AtomicBoolean(true)

    // The current client + its SSE front door; both rebuilt together when the
    // transport switch (or a roster edit) changes the dial target.
    @Volatile private var client: LenscastApiClient? = null
    @Volatile private var sseSource: SseEventSource? = null

    /**
     * The session-local TLS override from a `transport` notice. NEVER written
     * to the roster — the roster entry is the pairing-time snapshot; this is
     * the session's knowledge of the device's live scheme (see the class
     * KDoc's transport-switch note). Cleared only by release/process death.
     */
    @Volatile private var schemeOverride: Boolean? = null

    // Status-lane freshness (SseHealth inputs): stamped at lane birth and on
    // every `status` frame; [statusLaneDown] is the web's onerror parity —
    // a KNOWN failure reads unhealthy immediately instead of waiting out the
    // dead bar on the clock alone.
    private val statusLaneDown = AtomicBoolean(false)
    private val statusLaneLastEventMs = AtomicLong(0L)

    @Volatile private var statusLaneJob: Job? = null
    @Volatile private var detectionLaneJob: Job? = null

    /** True while the steady-state tick loop owns the lanes (see rebuildLanes). */
    @Volatile private var steadyActive = false

    private val statusStream = AtomicReference<SseStream?>(null)
    private val detStream = AtomicReference<SseStream?>(null)

    /**
     * Serializes every lane (re)launch — steady-state reopen, transport
     * rebuild, steady-state exit — so two rebuild paths can never leave an
     * orphaned or double-cancelled lane behind.
     */
    private val laneMutex = Mutex()

    private val health = SseHealth()
    private val detectionBuffer = EventStreamBuffer()

    private val statusAdapter = AppJson.moshi.adapter(DeviceStatus::class.java)
    private val detectionAdapter = AppJson.moshi.adapter(RemoteDetectionEvent::class.java)

    // `{"scheme":"https"}` decoded as a raw map — a one-shot notice shape not
    // worth a codegen DTO (useAppState.ts parses it just as leniently).
    private val transportAdapter = AppJson.moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )

    /**
     * The connection master: probe → steady state → (on link death) the
     * backoff ladder → terminal states. AUTH_FAILED and OFFLINE end here;
     * everything else loops.
     */
    private suspend fun runLink() {
        var ladderFailures = 0
        while (currentCoroutineContext().isActive) {
            transitionTo(
                // First attempt of a fresh run reads CONNECTING (the probe is
                // in flight); every retry reads RECONNECTING (a dropped link
                // being retried).
                if (ladderFailures == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING,
            )
            val client = requireClient()
            if (client == null) {
                // The roster entry vanished (device removed from another
                // screen) — no address to dial. Fail fast, no network call;
                // a later start() re-reads the roster.
                finishTerminal(ConnectionState.OFFLINE)
                return
            }
            probeListener?.invoke(dialBase(client))
            val probe = probeCatching { client.getStatus() }
            val probed = probe.getOrNull()
            if (probed != null) {
                applyStatus(probed)
                transitionTo(ConnectionState.CONNECTED)
                ladderFailures = 0
                when (runSteadyState()) {
                    SteadyVerdict.AUTH -> {
                        // 401 mid-link: the held credential was revoked —
                        // retrying cannot help (the server fails closed);
                        // settle AUTH_FAILED, the re-pair cue.
                        finishTerminal(ConnectionState.AUTH_FAILED)
                        return
                    }
                    SteadyVerdict.LINK_DEAD -> ladderFailures = 1 // resume mid-ladder as RECONNECTING
                }
            } else {
                when (probe.exceptionOrNull()) {
                    is CompanionApiException.Auth -> {
                        finishTerminal(ConnectionState.AUTH_FAILED)
                        return
                    }
                    else -> {
                        ladderFailures += 1
                        if (ladderExhausted(ladderFailures)) {
                            finishTerminal(ConnectionState.OFFLINE)
                            return
                        }
                        transitionTo(ConnectionState.RECONNECTING)
                        delayFn(ladderDelay(ladderFailures))
                        // Keep the UI warm through the backoff (visibility
                        // permitting) — the ladder sleeps seconds at a time.
                        if (visible.get()) warmPoll()
                    }
                }
            }
        }
    }

    /** Result of the steady-state phase: why it gave the link back. */
    private enum class SteadyVerdict { LINK_DEAD, AUTH }

    /**
     * The CONNECTED phase: the status SSE lane + the 1 s tick loop driving
     * SseHealth, the PollLadderPolicy STATUS rung (poll while SSE dead), and
     * the lane reopen rate-limit. Returns when the push AND pull lanes are
     * both judged gone (LINK_DEAD — hand back to the connect ladder) or the
     * link went AUTH (revoked credential).
     */
    private suspend fun runSteadyState(): SteadyVerdict {
        // Only the STATUS rung is scheduled: this session's ladder is exactly
        // the web's status-poll fallback (recording/interval/settings pollers
        // are tab-surface concerns, wave 3, not the session's).
        val ladder = PollLadderPolicy(periods = mapOf(PollLadderPolicy.Lane.STATUS to PollLadderPolicy.PERIOD_STATUS))
        var pollFailures = 0
        var lastReopenAttemptMs = clock()
        // steadyActive flips INSIDE the lock with the lane launch: a transport
        // rebuild must never see "steady active" before this phase actually
        // owns the lane slot, or it relaunches lanes that the launch below
        // would orphan (two live status SSE sockets, one unreferenced).
        laneMutex.withLock {
            steadyActive = true
            statusLaneJob = launchStatusLane()
        }
        try {
            while (currentCoroutineContext().isActive) {
                delayFn(timings.tickMs)
                // Visibility freeze: ticks, polls and reopens pause; the SSE
                // socket stays open (documented choice — class KDoc).
                if (!visible.get()) continue
                val now = clock()
                val down = statusLaneDown.get()
                val dead = health.evaluate(now, statusLaneLastEventMs.get()) == SseHealth.SseHealthState.Dead
                val healthy = !down && health.laneHealthy(now, statusLaneLastEventMs.get())
                if (down || dead) {
                    // Push lane gone (known failure) or silent past the dead
                    // bar (half-open socket): reconnecting + poll fallback armed.
                    transitionTo(ConnectionState.RECONNECTING)
                    if (now - lastReopenAttemptMs >= timings.statusSseRetryMs) {
                        // Rate-limited reopen — the EventSource retry cadence
                        // the browser drives for us on the web.
                        lastReopenAttemptMs = now
                        laneMutex.withLock {
                            statusLaneJob?.cancel()
                            statusLaneJob = launchStatusLane()
                        }
                    }
                } else if (_connectionState.value == ConnectionState.RECONNECTING) {
                    // A fresh status frame stamped the lane healthy again —
                    // back to CONNECTED (the lastSeenMs upsert rides this).
                    transitionTo(ConnectionState.CONNECTED)
                }
                val fired = ladder.tick(sseHealthy = healthy, visible = true)
                if (PollLadderPolicy.Lane.STATUS in fired) {
                    val probe = probeCatching { requireClient()?.getStatus() }
                    val polled = probe.getOrNull()
                    when {
                        polled != null -> {
                            applyStatus(polled)
                            pollFailures = 0
                        }
                        probe.exceptionOrNull() is CompanionApiException.Auth -> return SteadyVerdict.AUTH
                        else -> pollFailures += 1
                    }
                    if (pollFailures >= CONNECTION_LOST_FAILURES && !healthy) {
                        // The web's connection-lost verdict (3 consecutive
                        // poll failures while the SSE reads down): push AND
                        // pull are both gone — the connect ladder takes over.
                        return SteadyVerdict.LINK_DEAD
                    }
                } else if (healthy && pollFailures > 0) {
                    pollFailures = 0
                }
            }
        } finally {
            // The steady phase is the status lane's owner: end its job on the
            // way out. NonCancellable + the lane mutex so this stays ordered
            // against a concurrent transport rebuild — whichever runs second
            // sees the truth (a rebuild that ran first gets its lanes closed;
            // one that runs second skips the relaunch and leaves the already
            // applied override for the next steady run to dial). Plain
            // withLock would throw without acquiring on a cancelled
            // coroutine, leaking the ordering guarantee exactly when teardown
            // needs it most.
            withContext(NonCancellable) {
                laneMutex.withLock {
                    steadyActive = false
                    statusLaneJob?.cancel()
                    statusLaneJob = null
                }
            }
        }
        // Cancellation unwinds here; the verdict is never consumed by runLink
        // (its own isActive check ends the ladder first).
        return SteadyVerdict.LINK_DEAD
    }

    /**
     * One status SSE connection: open → collect frames → first failure ends
     * the lane (transport error OR the device's clean lifetime close — both
     * are "the lane ended" to the tick loop, which owns the reopen cadence).
     */
    private suspend fun CoroutineScope.statusLaneConnection() {
        val client = requireClient()
        if (client == null) {
            statusLaneDown.set(true)
            return
        }
        val url = client.statusEventsUrl()
        val stream = sse(client).open(url, client.sharedAuthHeaders())
        if (!currentCoroutineContext().isActive) {
            stream.cancel()
            return
        }
        statusStream.set(stream)
        laneOpenListener?.invoke(LaneKind.STATUS, url)
        // Stamp AFTER the actual open too: the dial can take milliseconds.
        statusLaneDown.set(false)
        statusLaneLastEventMs.set(clock())
        val collector = launch { stream.events.collect(::onStatusFrame) }
        try {
            stream.failures.first()
        } finally {
            collector.cancel()
            stream.cancel()
            statusStream.compareAndSet(stream, null)
            // onerror parity: a known failure reads unhealthy immediately —
            // the clock alone would keep the lane "healthy" for up to one
            // dead-bar of silence after the socket actually died.
            statusLaneDown.set(true)
        }
    }

    /** Launches one status lane run (callers hold [laneMutex] and assign the returned job). */
    private fun launchStatusLane(): Job {
        // Stamp at LAUNCH: the freshness clock must read healthy at lane birth
        // even if the first tick races this coroutine onto a worker thread.
        statusLaneDown.set(false)
        statusLaneLastEventMs.set(clock())
        val scope = activeScope.get()
        val job = scope?.launch { statusLaneConnection() }
        if (job == null) {
            // Torn down mid-launch: mark down so nothing waits on a lane that
            // will never exist; the inert job is a completed no-op.
            statusLaneDown.set(true)
            return Job().apply { complete() }
        }
        return job
    }

    /** The status lane's frame handler: conflate `status`, follow `transport`. */
    private fun onStatusFrame(event: SseEvent) {
        when (event.name) {
            STATUS_EVENT_NAME -> {
                // Freshness rides ARRIVAL — parse success or not — exactly
                // like the web's separate `status` listener stamping the clock.
                statusLaneLastEventMs.set(clock())
                runCatching { statusAdapter.fromJson(event.data) }.getOrNull()?.let(::applyStatus)
            }
            TRANSPORT_EVENT_NAME -> onTransportFrame(event.data)
            else -> Unit // unknown names are forward-compat noise; ping comments never reach us (okhttp-sse skips them)
        }
    }

    /**
     * The `transport` notice: the device restarted its listener under the
     * announced scheme. Same guards as the web's transportSwitchUrl: only
     * http/https are actionable, and a notice announcing the scheme we are
     * ALREADY on stays put (a notice that raced its own switch).
     */
    private fun onTransportFrame(data: String) {
        val notice = runCatching { transportAdapter.fromJson(data) }.getOrNull() ?: return
        val scheme = notice["scheme"] ?: return
        if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) return
        val targetTls = scheme == SCHEME_HTTPS
        if (effectiveDevice()?.useTls == targetTls) return
        val scope = activeScope.get() ?: return
        scope.launch { rebuildLanes(targetTls) }
    }

    /**
     * The transport switch, executed once per notice: flip the session-local
     * override, rebuild the client + SSE source, then close and re-dial both
     * lanes. Runs on the session scope (not the frame-handling lane, whose
     * cancellation is the rebuild's own side effect).
     */
    private suspend fun rebuildLanes(targetTls: Boolean) {
        val scope = activeScope.get() ?: return
        laneMutex.withLock {
            if (activeScope.get() !== scope) return@withLock // torn down / restarted while waiting
            if (effectiveDevice()?.useTls == targetTls) return@withLock // a newer notice already moved us
            schemeOverride = targetTls
            // Force the next requireClient() to rebuild engine + URLs on the
            // new scheme (and the SSE front door with it).
            client = null
            sseSource = null
            if (!steadyActive) return@withLock
            // Both lanes re-dial on the new client. The web reloads the page;
            // a session re-dials in place, keeping its state (status snapshot,
            // event feed) — the documented deviation (class KDoc).
            statusLaneJob?.cancel()
            detectionLaneJob?.cancel()
            detectionLaneJob = scope.launch { detectionLaneBody() }
            statusLaneJob = launchStatusLane()
        }
    }

    /**
     * The detection-event lane's whole lifecycle (useEventStream.ts port):
     * wait for a contactable window → SSE → on failure poll every 10 s and
     * retry the SSE after 60 s → repeat, forever, independently of the
     * status lane's health (the web mounts the stream for the page's
     * lifetime).
     */
    private suspend fun CoroutineScope.detectionLaneBody() {
        while (currentCoroutineContext().isActive) {
            // The lane runs whenever the session believes the device is
            // contactable at all (CONNECTED, or RECONNECTING with the poll
            // fallback active); OFFLINE/AUTH_FAILED park it (and terminal
            // teardown cancels it outright).
            _connectionState.first {
                it == ConnectionState.CONNECTED || it == ConnectionState.RECONNECTING
            }
            val client = requireClient()
            if (client == null) {
                // Roster vanished mid-session: idle-wait and re-check; the
                // state gate above re-opens on the next reconnect anyway.
                delayFn(timings.tickMs)
                continue
            }
            val url = client.detectionEventsStreamUrl()
            val stream = sse(client).open(url, client.sharedAuthHeaders())
            if (!currentCoroutineContext().isActive) {
                stream.cancel()
                return
            }
            detStream.set(stream)
            laneOpenListener?.invoke(LaneKind.DETECTION, url)
            val collector = launch { stream.events.collect(::onDetectionFrame) }
            // Live phase: no polls while the push lane flows; watch for its
            // one ending (any failure — transport error or clean close).
            var degraded = false
            while (currentCoroutineContext().isActive) {
                val failure = withTimeoutOrNull(timings.tickMs) { stream.failures.first() }
                if (failure != null) {
                    degraded = true
                    break
                }
            }
            collector.cancel()
            stream.cancel()
            detStream.compareAndSet(stream, null)
            if (!currentCoroutineContext().isActive || !degraded) return
            // Degraded phase — useEventStream semantics: poll immediately,
            // then every POLL_INTERVAL_MS; the SSE retry fires after
            // SSE_RETRY_MS regardless of visibility (the web's setTimeout),
            // while only the POLLS pause on document.hidden.
            pollDetection(client)
            var nextPollAtMs = clock() + timings.detectionPollMs
            val sseRetryAtMs = clock() + timings.detectionSseRetryMs
            while (currentCoroutineContext().isActive) {
                delayFn(timings.tickMs)
                val now = clock()
                if (now >= sseRetryAtMs) break // SSE retry due: re-dial above
                if (!visible.get()) continue
                if (now >= nextPollAtMs) {
                    pollDetection(client)
                    nextPollAtMs = now + timings.detectionPollMs
                }
            }
            if (!currentCoroutineContext().isActive) return
        }
    }

    /** One detection poll: merge the page through the buffer's dedupe (transient errors leave the feed untouched — web parity). */
    private suspend fun pollDetection(client: LenscastApiClient) {
        val page = probeCatching { client.getDetectionEvents(limit = DETECTION_POLL_LIMIT) }.getOrNull() ?: return
        detectionBuffer.onPollRefresh(page.events)
        publishDetectionFeed()
    }

    /** The SSE frame handler: data-only frames decode into the deduped, capped, newest-first buffer. */
    private fun onDetectionFrame(event: SseEvent) {
        // The detection stream's wire shape is the SSE-spec default (unnamed)
        // event; a named frame here is not detection telemetry.
        if (event.name != null) return
        val decoded = runCatching { detectionAdapter.fromJson(event.data) }.getOrNull() ?: return
        detectionBuffer.onSse(decoded)
        publishDetectionFeed()
    }

    /** Republishes the buffer's snapshot into the conflated feed flow. */
    private fun publishDetectionFeed() {
        _detectionEvents.update { detectionBuffer.snapshot() }
    }

    // ── On-demand detection seams (wave 4 — the events tab's refresh/clear) ──

    /**
     * A manual kick of the detection poll lane: one `GET /api/detection/events`
     * page merged through the buffer's dedupe, exactly as the lane's degraded
     * phase polls. The events tab's refresh calls it so a manual pull truly
     * re-reads the device's log instead of re-rendering the already-live
     * buffer. Runs on the CALLER's context — no lane is launched or relaunched
     * (laneMutex stays out of the way), and the buffer's internal lock makes
     * this safe beside a live SSE arrival. Silent on failure: a transient
     * error leaves the feed untouched ([pollDetection]'s web parity); a
     * roster-less session no-ops (no client to dial).
     */
    suspend fun manualDetectionPoll() {
        val client = requireClient() ?: return
        pollDetection(client)
    }

    /**
     * Wipes the session's in-memory event feed (useEventStream's `clear`, the
     * buffer's own documented clear-after-server-delete shape): the events tab
     * calls it once the device confirmed `DELETE /api/detection/events`, so
     * the feed mirrors the emptied device log immediately instead of waiting
     * for the lane's next poll refresh to reconcile. (Retires the former
     * documented deviation — the buffer surviving a clear.)
     */
    fun clearDetectionFeed() {
        detectionBuffer.clear()
        publishDetectionFeed()
    }

    // ── Small helpers ──

    /** The session's view of the device: roster snapshot + session-local scheme override. */
    private fun effectiveDevice(): CompanionDevice? {
        val current = roster?.find(deviceId) ?: return null
        // Re-read every call: roster edits (re-pair changing host/port)
        // propagate on the next client rebuild; the override wins meanwhile.
        return current.copy(useTls = schemeOverride ?: current.useTls)
    }

    /**
     * The session's API client, built INTERNALLY from the roster's device
     * snapshot + the live credential provider (a re-pair takes effect on the
     * next request without rebuilding). Rebuilt whenever the effective device
     * changes (transport switch, roster edit). Null when there is no dialable
     * device — the roster-missing fail-fast path.
     */
    private fun requireClient(): LenscastApiClient? {
        val wanted = effectiveDevice() ?: return null
        client?.let { built -> if (built.device() == wanted) return built }
        val creds = credentials ?: return null
        return LenscastApiClient(wanted) { creds.get(deviceId) }.also {
            client = it
            sseSource = null // the SSE engine rides the client's — rebuild with it
        }
    }

    /**
     * The SSE front door for the current client: the shared engine's pool +
     * pinning ride along, but the long-lived sockets cannot live under the
     * JSON lane's request-response budgets (an 8 s read / 12 s call timeout
     * would kill every healthy stream mid-connection) — both are zeroed on
     * the derived engine, keeping the 3 s connect budget so a dead device
     * still fails a dial in seconds.
     */
    private fun sse(client: LenscastApiClient): SseEventSource {
        sseSource?.let { return it }
        val longLived = client.sharedEngine().newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        return SseEventSource(longLived).also { sseSource = it }
    }

    /** Conflated status lane: every arrival supersedes the last (useAppState's setStatus shape). */
    private fun applyStatus(status: DeviceStatus) {
        _status.value = status
    }

    /** A poll during reconnect backoff — warms [status] without touching the state machine. */
    private suspend fun warmPoll() {
        val client = requireClient() ?: return
        val polled = probeCatching { client.getStatus() }.getOrNull() ?: return
        applyStatus(polled)
    }

    /** The scheme/host/port the session currently dials — the probe seam's payload. */
    private fun dialBase(client: LenscastApiClient): String {
        val device = client.device()
        return "${if (device.useTls) "https" else "http"}://${device.host}:${device.port}"
    }

    /**
     * The state machine's one write path. The update lambda re-checks the run
     * at CAS time, so a lane coroutine unwinding after a teardown cannot
     * resurrect a state — a stopped session accepts only the stop-imposed
     * IDLE. lastSeenMs rides ONLY the →CONNECTED / →OFFLINE transitions
     * (never the ~1/s status ticks — the roster file is persistence, not
     * telemetry).
     */
    private fun transitionTo(next: ConnectionState) {
        val previous = _connectionState.value
        _connectionState.update { current ->
            if (activeScope.get() == null && next != ConnectionState.IDLE) current else next
        }
        if (previous == next) return
        when (next) {
            ConnectionState.CONNECTED, ConnectionState.OFFLINE -> upsertLastSeen()
            else -> Unit
        }
    }

    /** Stamps the roster's lastSeenMs from the CURRENT roster copy (never the session-local scheme copy). */
    private fun upsertLastSeen() {
        val store = roster ?: return
        val current = store.find(deviceId) ?: return // unknown device: nothing to stamp
        store.upsert(current.copy(lastSeenMs = clock()))
    }

    /** Backoff for connect-ladder attempt [attempt] (1-based): the tier list, capped at its last entry. */
    private fun ladderDelay(attempt: Int): Long =
        timings.ladderBackoffMs[minOf(attempt - 1, timings.ladderBackoffMs.lastIndex)]

    /** True once the cap tier has repeated [SessionTimings.ladderGiveUpAtCapTier] times (~2 min at defaults). */
    private fun ladderExhausted(attempt: Int): Boolean =
        (attempt - timings.ladderBackoffMs.size + 1) >= timings.ladderGiveUpAtCapTier

    /**
     * The terminal write: settle the state first (while the scope still lets
     * transitions through), then tear the run down — a terminal session is a
     * stopped session, so the next [start] is a fresh run.
     */
    private fun finishTerminal(state: ConnectionState) {
        transitionTo(state)
        teardown()
    }

    /**
     * The one teardown path (stop() and the terminal states share it): block
     * lane transitions, close both SSE sockets, cancel the child scope.
     * Idempotent — the scope swap is the gate; every second call is a no-op.
     */
    private fun teardown() {
        val scope = activeScope.getAndSet(null) ?: return
        statusStream.getAndSet(null)?.cancel()
        detStream.getAndSet(null)?.cancel()
        scope.cancel()
    }

    /**
     * Probe-friendly exception capture: a coroutine cancellation is NEVER a
     * probe verdict — it unwinds (the stdlib's `runCatching` would swallow it
     * into a Result and the ladder would treat a cancelled session as a
     * dead device).
     */
    private suspend fun <T> probeCatching(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}

/**
 * The companion mode's one session registry: lazily creates and caches a
 * [DeviceSession] per device id, so every consumer (the device screen's
 * tabs, the roster's live badges) shares one connection, one status
 * conflation loop, and one event feed per device — never parallel
 * reconnect ladders racing each other. Each session is constructed with its
 * [LenscastApiClient] built INTERNALLY from the roster's device snapshot
 * (the manager wires the collaborators via the session's internal attach);
 * a device missing from the roster still gets a cached session — it fails
 * fast to OFFLINE on start instead of crashing the caller (see the session
 * class KDoc). [release] stops the session FIRST, then drops the cache
 * entry: the dispose seam for when the user removes a device or the process
 * winds down.
 *
 * Signatures frozen in wave 0; implemented in wave 2; new members may be
 * added.
 */
class DeviceSessionManager(
    private val roster: DeviceRosterStore,
    private val credentials: CompanionCredentialStore,
    private val scope: CoroutineScope,
) {

    private val sessions = mutableMapOf<String, DeviceSession>()

    /** The cached session for [deviceId], creating it on first request. */
    fun session(deviceId: String): DeviceSession = synchronized(sessions) {
        sessions.getOrPut(deviceId) {
            DeviceSession(deviceId).also { it.attach(roster, credentials, scope) }
        }
    }

    /** Stops and drops the cached session; no-op when none is cached. */
    fun release(deviceId: String) {
        val removed = synchronized(sessions) { sessions.remove(deviceId) }
        // Stop FIRST, then drop: a released session must not keep dialing
        // (its state settles to IDLE, its sockets close, its scope cancels).
        removed?.stop()
    }

    /** Every live cached session, in cache order. */
    fun active(): List<DeviceSession> = synchronized(sessions) { sessions.values.toList() }
}

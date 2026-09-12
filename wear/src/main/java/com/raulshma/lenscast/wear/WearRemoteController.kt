package com.raulshma.lenscast.wear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The remote's single state holder: merges the live settings with the async
 * surfaces (status poll, snapshot, stream toggle, photo capture, detection
 * feed) into one [UiState] StateFlow the screens render. Owns the periodic
 * loops — the status poll (the mirror for the stream toggle), the snapshot
 * refresh, and the detection-events tail — and serializes each surface so a
 * slow fetch never stacks duplicates.
 *
 * Loop lifecycle belongs to the caller: the activities launch [start] inside
 * a resumed-lifecycle block, so every loop stops with the screen and the
 * watch keeps its battery (deliberately NO background polling, NO Wear Data
 * Layer). Every action is a plain suspend entry point the UI fires from its
 * own scope; failures land in the matching RequestState, never in an
 * exception.
 *
 * The alert loop is the pure [WearAlertPolicy] plus three injected side
 * effects: the persisted seen-state ([WearAlertStateStoreApi]), the haptic +
 * notification ([DetectionAlerter]), and the tile refresh ([TilePublisher]).
 * The default no-op dependencies keep the controller constructible in plain
 * JVM contexts.
 */
/**
 * The persisted alert seen-state seam (the SharedPreferences store).
 * Two methods, so not a `fun interface` — [WearAlertStateStore] is the
 * production implementation.
 */
interface WearAlertStateStoreApi {
    fun load(): WearAlertPolicy.SeenState?
    fun save(state: WearAlertPolicy.SeenState)
}

/** The side-effect seam fired after every successful status poll (tile refresh). */
fun interface TilePublisher {
    fun onStatus(status: WearStatus)
}

class WearRemoteController(
    private val settingsStore: WearSettingsStore,
    private val client: WearApiClient,
    private val alertStateStore: WearAlertStateStoreApi? = null,
    private val alerter: DetectionAlerter? = null,
    private val tilePublisher: TilePublisher? = null,
) {

    /** Everything the UI draws, in one immutable snapshot. */
    data class UiState(
        val configured: Boolean = false,
        val status: RequestState<WearStatus> = RequestState.Idle,
        val snapshot: RequestState<SnapshotFrame> = RequestState.Idle,
        val streamToggle: RequestState<Unit> = RequestState.Idle,
        val capture: RequestState<Unit> = RequestState.Idle,
        /** The banner event currently surfaced, null when dismissed/unraised. */
        val alert: WearDetectionEvent? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The in-flight loops; non-null exactly while [start] is in effect. */
    private var statusLoop: Job? = null
    private var snapshotLoop: Job? = null
    private var eventsLoop: Job? = null

    /**
     * Launches the poll loops in [scope]. Idempotent per surface: calling
     * start again replaces the previous loop instead of doubling it.
     */
    fun start(scope: CoroutineScope) {
        if (statusLoop?.isActive == true) return
        statusLoop = scope.launch { pollStatusForever() }
        snapshotLoop = scope.launch { refreshSnapshotForever() }
        eventsLoop = scope.launch { pollEventsForever() }
    }

    /** Stops every loop; the drawn state stays for the next resume. */
    fun stop() {
        statusLoop?.cancel()
        snapshotLoop?.cancel()
        eventsLoop?.cancel()
        statusLoop = null
        snapshotLoop = null
        eventsLoop = null
    }

    /**
     * The status poll: the stream toggle's truth mirror. Background polls
     * keep the last good status visible and fold failures into Error — a
     * watch that leaves WiFi must show "unreachable", not a stale Active.
     */
    private suspend fun pollStatusForever() {
        while (true) {
            runOne { client.fetchStatus() }
                .onSuccess { value ->
                    _state.update { it.copy(status = RequestState.Success(value), configured = true) }
                    tilePublisher?.onStatus(value)
                }
                .onFailure { e ->
                    _state.update { it.copy(status = RequestState.Error(shortMessage(e))) }
                }
            delay(STATUS_POLL_MS)
        }
    }

    /**
     * The detection-events tail: the same resumed-only cadence as the status
     * poll, staggered half a lap so the two requests never collide on the
     * radio. Failures fold into a silent retry — the status header already
     * owns "unreachable". New events surface through the policy: persisted
     * dedup ([WearAlertStateStoreApi]), haptic + notification
     * ([DetectionAlerter]), and the in-app banner (freshness-gated by
     * [WearAlertPolicy], so a stale-but-unseen event only updates the ring).
     */
    private suspend fun pollEventsForever() {
        delay(EVENT_POLL_STAGGER_MS)
        while (true) {
            val settings = settingsStore.current()
            if (settings.isConfigured && settings.alertsEnabled && alertStateStore != null) {
                runOne { client.fetchDetectionEvents(EVENT_FEED_LIMIT) }
                    .onSuccess { feed ->
                        val verdict = WearAlertPolicy.evaluate(
                            feed,
                            alertStateStore.load(),
                            System.currentTimeMillis(),
                        )
                        verdict.nextSeen?.let(alertStateStore::save)
                        val newest = verdict.newEvents.lastOrNull() ?: return@onSuccess
                        alerter?.onNewEvent(newest)
                        _state.update { it.copy(alert = newest) }
                    }
            }
            delay(EVENT_POLL_MS)
        }
    }

    /** User dismissal of the in-app alert banner (the notification stays). */
    fun dismissAlert() {
        _state.update { it.copy(alert = null) }
    }

    /**
     * The snapshot loop: a slow cadence for the pane's live feel, with the
     * manual refresh button forcing an immediate lap. Fetches run back to
     * back within the loop, so a slow snapshot never overlaps itself.
     */
    private suspend fun refreshSnapshotForever() {
        while (true) {
            val inFlight = _state.value.snapshot is RequestState.Loading
            if (!inFlight) refreshSnapshot(manual = false)
            delay(SNAPSHOT_REFRESH_MS)
        }
    }

    /** One snapshot fetch; user-initiated refresh shows the loading surface. */
    suspend fun refreshSnapshot(manual: Boolean) {
        runOne { client.fetchSnapshot() }
            .onSuccess { frame ->
                _state.update { it.copy(snapshot = RequestState.Success(frame)) }
            }
            .onFailure { e ->
                // A failed background refresh keeps the last good frame on
                // screen; only a manual refresh swaps in the error card.
                _state.update {
                    it.copy(
                        snapshot = if (manual || it.snapshot is RequestState.Idle) {
                            RequestState.Error(shortMessage(e))
                        } else {
                            it.snapshot
                        }
                    )
                }
            }
    }

    /** POST /api/stream/start|stop, chosen by the last known stream state. */
    suspend fun toggleStream(currentlyActive: Boolean) {
        _state.update { it.copy(streamToggle = RequestState.Loading) }
        try {
            val result = if (currentlyActive) client.stopStream() else client.startStream()
            _state.update {
                it.copy(
                    streamToggle = if (result.success) RequestState.Success(Unit)
                    else RequestState.Error(result.error ?: "Phone rejected the command")
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(streamToggle = RequestState.Error(shortMessage(e))) }
        }
        // An immediate poll re-mirrors the toggle's outcome (and repairs the
        // label when the phone's real state disagreed with our mirror).
        runOne { client.fetchStatus() }
            .onSuccess { value -> _state.update { it.copy(status = RequestState.Success(value)) } }
    }

    /** POST /api/capture with the loading + confirmation animation states. */
    suspend fun capturePhoto() {
        _state.update { it.copy(capture = RequestState.Loading) }
        try {
            val result = client.capturePhoto()
            _state.update {
                it.copy(
                    capture = if (result.success) RequestState.Success(Unit)
                    else RequestState.Error(result.error ?: "Capture failed")
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(capture = RequestState.Error(shortMessage(e))) }
        }
    }

    /**
     * Retires the photo confirmation after its on-screen moment — called by
     * the UI's auto-clear timer so the button returns to its resting look.
     */
    fun clearCaptureConfirmation() {
        if (_state.value.capture is RequestState.Success) {
            _state.update { it.copy(capture = RequestState.Idle) }
        }
    }

    /**
     * The one-shot settings load: decides the "configured" gate the UI uses
     * to send a first-run user to the settings screen. Called once per
     * resume; after this, the poll keeps [UiState.configured] truthful.
     */
    suspend fun loadSettingsGate() {
        val settings = settingsStore.current()
        _state.update { it.copy(configured = settings.isConfigured) }
    }

    /** Runs one suspend fetch, mapping cancellations through and failures to a result. */
    private suspend fun <T> runOne(fetch: suspend () -> T): Result<T> = runCatching { fetch() }

    /** Network errors arrive as IOException/UnknownHost chains; shorten for the round screen. */
    private fun shortMessage(e: Throwable): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return raw.lineSequence().firstOrNull().orEmpty().take(60)
    }

    private companion object {
        /** The status cadence — fast enough to mirror the phone, slow enough for battery. */
        const val STATUS_POLL_MS = 5_000L

        /** The snapshot cadence: a living preview pane without draining the link. */
        const val SNAPSHOT_REFRESH_MS = 10_000L

        /** The detection feed rides the status cadence, staggered half a lap. */
        const val EVENT_POLL_MS = STATUS_POLL_MS
        const val EVENT_POLL_STAGGER_MS = STATUS_POLL_MS / 2

        /** The feed window the alert policy needs (the server clamps again). */
        const val EVENT_FEED_LIMIT = 5
    }
}

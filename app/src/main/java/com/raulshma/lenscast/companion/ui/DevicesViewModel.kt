package com.raulshma.lenscast.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.di.CompanionGraph
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.DeviceSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One roster row's reachability verdict — the chip the card renders.
 * Refresh-on-open/manual only: the roster screen never polls (live status
 * belongs to the device screen's session lane), so PROBING is the resting
 * state between refreshes, not a transient.
 */
enum class DeviceProbeStatus { PROBING, ONLINE, OFFLINE, UNAUTHORIZED }

/** One card's render facts: the roster snapshot + the latest probe verdict. */
data class DeviceRow(
    val device: CompanionDevice,
    val status: DeviceProbeStatus,
)

/**
 * The devices screen's MVI state: [Loading] before the first roster read,
 * then [Ready] with one row per paired device.
 */
sealed interface DevicesUiState {

    /** The initial read is in flight (sub-second; the roster is a local file). */
    data object Loading : DevicesUiState

    /** The roster snapshot, roster order. */
    data class Ready(
        val rows: List<DeviceRow>,
        /** The id pending a remove-confirm dialog, when one is open. */
        val pendingRemovalId: String? = null,
        /** True while a capture-all volley is in flight — the affordance goes inert. */
        val capturingAll: Boolean = false,
    ) : DevicesUiState

    /** The roster's id when nothing is paired — the empty state. */
    data object Empty : DevicesUiState
}

/** One-shot effects the screen executes outside the state tree. */
sealed interface DevicesEffect {

    /** The device named [deviceName] was removed — snackbar material. */
    data class Removed(val deviceName: String) : DevicesEffect

    /**
     * One device's capture-all verdict — snackbar material. [ok] mirrors the
     * device's POST /api/capture answer (a transport or HTTP failure is
     * false); the volley fires one of these per ONLINE device, exactly the
     * web console's per-camera capture badges.
     */
    data class Captured(val deviceName: String, val ok: Boolean) : DevicesEffect
}

/**
 * The companion roster's ViewModel — the devices screen's state owner.
 *
 * Probes: each row's [DeviceProbeStatus] comes from one
 * `apiClient(device).getStatus()` round trip (the client's LAN budgets: 3s
 * connect / 8s read / 12s whole call), fired on open and on manual refresh
 * only — no polling loop lives here on purpose (the roster is a glanceable
 * list; continuous reachability is the device screen's session's job, and
 * N parallel status pollers would fight it). At most [MAX_CONCURRENT_PROBES]
 * run at once (a home roster can outgrow the radio's patience for a burst
 * of simultaneous TLS handshakes); a refresh cancels the previous batch so
 * two refresh taps never double-fire.
 *
 * Verdicts: 2xx → [DeviceProbeStatus.ONLINE]; 401 →
 * [DeviceProbeStatus.UNAUTHORIZED] (the re-pair cue); every other failure —
 * transport, HTTP errors, and pin mismatches (an identity failure is a
 * connectivity verdict on this screen; the device screen owns the loud
 * imposter dialog) — → [DeviceProbeStatus.OFFLINE].
 *
 * Capture-all (wave 4, the web console's volley): POST /api/capture on every
 * currently-ONLINE row at once (same radio gate, per-device verdicts as
 * one-shot effects; offline rows skip silently — see [captureAll]).
 *
 * Removal is the one destructive action: roster.remove +
 * credentials.remove (the encrypted secret) + sessions.release (drop any
 * cached live session — [DeviceSessionManager.active] would expose a live
 * one, but DeviceSession.stop() lands in wave 2 and release is the only
 * implemented seam today, so releasing the cache entry is the complete
 * guard) → a [DevicesEffect.Removed] snackbar.
 */
class DevicesViewModel(
    private val roster: DeviceRosterStore,
    private val credentials: CompanionCredentialStore,
    private val sessions: DeviceSessionManager,
    private val apiClient: (CompanionDevice) -> LenscastApiClient,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<DevicesUiState>(DevicesUiState.Loading)
    val state = _state.asStateFlow()

    private val _effects = Channel<DevicesEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** The radio gate: at most this many status probes in flight. */
    private val probeSlots = Semaphore(MAX_CONCURRENT_PROBES)

    /** The live probe batch; a refresh cancels it before launching the next. */
    private var probeJob: Job? = null

    /** The live capture-all volley; a newer tap cancels it before launching the next. */
    private var captureJob: Job? = null

    init {
        refresh()
    }

    /**
     * Re-reads the roster and re-probes every row. Rows publish immediately
     * at [DeviceProbeStatus.PROBING] so the list never blanks while the
     * round trips are in flight.
     */
    fun refresh() {
        probeJob?.cancel()
        val devices = roster.all()
        _state.update {
            if (devices.isEmpty()) {
                DevicesUiState.Empty
            } else {
                DevicesUiState.Ready(devices.map { DeviceRow(it, DeviceProbeStatus.PROBING) })
            }
        }
        if (devices.isEmpty()) return
        probeJob = scope.launch {
            devices.map { device ->
                async {
                    probeSlots.withPermit {
                        val status = probe(device)
                        updateRow(device.id, status)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Re-reads the roster and refreshes only if its membership drifted from
     * what this ViewModel currently holds (e.g. returning after pairing a new
     * device without destroying the ViewModel).
     */
    fun refreshIfRosterChanged() {
        val currentIds: List<String> = when (val current = _state.value) {
            DevicesUiState.Loading -> return
            DevicesUiState.Empty -> emptyList()
            is DevicesUiState.Ready -> current.rows.map { it.device.id }
        }
        val persistedIds = roster.all().map { it.id }
        if (persistedIds != currentIds) {
            refresh()
        }
    }

    /**
     * Removes one device end-to-end: the roster entry, its encrypted
     * credentials, and any cached session — then confirms via effect.
     */
    fun remove(deviceId: String) {
        val device = roster.find(deviceId)
        roster.remove(deviceId)
        credentials.remove(deviceId)
        // The session cache is the only live-link seam so far; release drops
        // the entry (DeviceSession.stop() lands in wave 2 — until then a
        // removed device's lanes die with its next access anyway, since the
        // roster no longer resolves the id).
        sessions.release(deviceId)
        _state.update { current ->
            when (current) {
                is DevicesUiState.Ready -> {
                    val rows = current.rows.filterNot { it.device.id == deviceId }
                    if (rows.isEmpty()) DevicesUiState.Empty else current.copy(rows = rows)
                }
                else -> current
            }
        }
        device?.let { _effects.trySend(DevicesEffect.Removed(it.name)) }
    }

    /** Opens/closes the remove-confirm dialog; the removal itself is [remove]. */
    fun setPendingRemoval(deviceId: String?) {
        _state.update { current ->
            if (current is DevicesUiState.Ready) current.copy(pendingRemovalId = deviceId) else current
        }
    }

    /**
     * The capture-all volley (web-console parity): POST /api/capture on every
     * currently-ONLINE row — rows still PROBING and the OFFLINE/UNAUTHORIZED
     * verdicts are skipped silently (the web's volley does not badge
     * unreachable cameras either). Targets snapshot at tap time; a newer tap
     * cancels the in-flight volley; the roster's radio gate
     * ([MAX_CONCURRENT_PROBES] semaphore) bounds the concurrent dials. Each
     * device's verdict lands as a [DevicesEffect.Captured]; the volley's
     * in-flight flag rides the state so the affordance goes inert.
     */
    fun captureAll() {
        val ready = _state.value as? DevicesUiState.Ready ?: return
        if (ready.capturingAll) return
        val targets = ready.rows.filter { it.status == DeviceProbeStatus.ONLINE }.map { it.device }
        if (targets.isEmpty()) return
        _state.update { current ->
            if (current is DevicesUiState.Ready) current.copy(capturingAll = true) else current
        }
        captureJob?.cancel()
        captureJob = scope.launch {
            targets.map { device ->
                async {
                    probeSlots.withPermit {
                        val ok = try {
                            apiClient(device).capturePhoto()
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CompanionApiException) {
                            false
                        } catch (e: IOException) {
                            false
                        }
                        _effects.trySend(DevicesEffect.Captured(device.name, ok))
                    }
                }
            }.awaitAll()
            _state.update { current ->
                if (current is DevicesUiState.Ready) current.copy(capturingAll = false) else current
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }

    /** One device's reachability verdict, per the class KDoc's mapping. */
    private suspend fun probe(device: CompanionDevice): DeviceProbeStatus =
        try {
            apiClient(device).getStatus()
            DeviceProbeStatus.ONLINE
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompanionApiException.Auth) {
            DeviceProbeStatus.UNAUTHORIZED
        } catch (e: CompanionApiException) {
            DeviceProbeStatus.OFFLINE
        } catch (e: IOException) {
            DeviceProbeStatus.OFFLINE
        }

    /** Atomic row patch: the one state write a probe conclusion makes. */
    private fun updateRow(deviceId: String, status: DeviceProbeStatus) {
        _state.update { current ->
            if (current !is DevicesUiState.Ready) {
                current
            } else {
                current.copy(
                    rows = current.rows.map { row ->
                        if (row.device.id == deviceId) row.copy(status = status) else row
                    },
                )
            }
        }
    }

    class Factory(
        private val companion: CompanionGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DevicesViewModel(
                roster = companion.roster,
                credentials = companion.credentials,
                sessions = companion.sessions,
                apiClient = companion::apiClient,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ) as T
        }
    }

    companion object {
        /**
         * The roster probe burst cap — four simultaneous round trips cover
         * any plausible home roster while keeping the Wi-Fi radio (and the
         * remote devices' single-threaded NanoHTTPD lane) out of congestion.
         */
        const val MAX_CONCURRENT_PROBES = 4
    }
}

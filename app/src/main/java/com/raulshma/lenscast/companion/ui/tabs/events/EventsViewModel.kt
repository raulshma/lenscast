package com.raulshma.lenscast.companion.ui.tabs.events

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.DetectionStats
import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import com.raulshma.lenscast.companion.di.CompanionGraph
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The events tab's MVI state: [Loading] before the state combination's first
 * emission (a sub-second flash — every input flow already carries a value),
 * then [Ready].
 */
sealed interface EventsUiState {

    /** The [stateIn] seed; never observed past the first combination pass. */
    data object Loading : EventsUiState

    /**
     * The tab's render facts. [events] is the session's live feed verbatim
     * (the EventStreamBuffer snapshot — deduped, capped, NEWEST-FIRST; the
     * session is the display source, the tab never forks a second list),
     * [stats] the REST aggregates once the stats poll has landed, [connection]
     * the live-link verdict behind the chip.
     */
    data class Ready(
        val events: List<RemoteDetectionEvent>,
        val stats: DetectionStats?,
        val connection: ConnectionState,
        /** False (paired as viewer) hides every destructive action. */
        val isAdmin: Boolean,
        /** True while a destructive call is in flight — the buttons go inert. */
        val busy: Boolean = false,
        /** True while the clear-all confirm dialog is open. */
        val pendingClearConfirm: Boolean = false,
    ) : EventsUiState
}

/**
 * One-shot effects the tab executes outside the state tree (snackbar copy).
 * [Notice] carries the message's resource; [TestFired] carries the device's
 * own dispatched-sink names verbatim (device data, resolved at the UI edge).
 */
sealed interface EventsEffect {

    /** A snackbar notice. */
    data class Notice(@StringRes val resId: Int) : EventsEffect

    /**
     * The test-fire's answer: [success] mirrors the payload's verdict and
     * [dispatchedActions] names the alert sinks that took the synthetic
     * alert (empty when none did, or when the device answered success=false).
     */
    data class TestFired(
        val success: Boolean,
        val dispatchedActions: List<String>,
    ) : EventsEffect
}

/**
 * The events tab's ViewModel — the state owner over the session's event feed
 * plus the REST surface the tab adds (stats strip, clear-all, test-fire).
 *
 * Feed: [DeviceSession.detectionEvents] IS the display source (the SSE lane's
 * deduped, newest-first buffer); this VM combines it into [state] and never
 * fetches its own event list. A manual refresh re-fetches the stats AND
 * kicks the session's on-demand poll seam ([DeviceSession.manualDetectionPoll],
 * wave 4) so the pull truly re-reads the device's log — the merged page
 * flows back through the same buffer. The seam is silent on failure (the
 * feed stays as-is; only the stats verdict notifies).
 *
 * Role: [isAdmin] resolves once in the Factory from the roster's paired
 * role — `viewer` hides every destructive action, and a [Forbidden] answer
 * (a stale snapshot, or the device's own RoleGatePolicy) surfaces a snackbar
 * effect regardless of which call tripped it.
 *
 * Clear-all: DELETE /api/detection/events is the server truth; on success the
 * VM wipes the session's in-memory buffer through
 * [DeviceSession.clearDetectionFeed] (wave 4 — the feed mirrors the emptied
 * device log immediately instead of waiting for the lane's next poll refresh
 * to reconcile), then re-fetches the stats.
 */
class EventsViewModel(
    private val session: DeviceSession,
    /** The client for this session's device; null when the roster lost it (a settled no-dial state). */
    private val api: LenscastApiClient?,
    /** The paired role from the roster: only the literal "viewer" is read-only. */
    private val isAdmin: Boolean,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val stats = MutableStateFlow<DetectionStats?>(null)
    private val busy = MutableStateFlow(false)
    private val pendingClearConfirm = MutableStateFlow(false)

    private var statsJob: Job? = null

    /**
     * The single state flow: the session's feed and link state combined with
     * the tab-local REST facts — one atomic emission per change, no partial
     * states.
     */
    val state: StateFlow<EventsUiState> = combine(
        session.detectionEvents,
        session.connectionState,
        stats,
        busy,
        pendingClearConfirm,
    ) { events, connection, stats, busy, pendingClear ->
        EventsUiState.Ready(
            events = events,
            stats = stats,
            connection = connection,
            isAdmin = isAdmin,
            busy = busy,
            pendingClearConfirm = pendingClear,
        )
    }.stateIn(scope, SharingStarted.Eagerly, EventsUiState.Loading)

    private val _effects = Channel<EventsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        fetchStats(notifyOnFailure = false)
    }

    /**
     * Manual refresh: re-fetches the stats aggregates AND kicks the session's
     * manual detection poll, so the feed genuinely re-reads the device's log
     * (the merged page flows back through the session buffer — the poll seam
     * is silent on failure; only the stats verdict notifies).
     */
    fun refresh() {
        fetchStats(notifyOnFailure = true)
        scope.launch { session.manualDetectionPoll() }
    }

    /** Opens/closes the clear-all confirm dialog. */
    fun setPendingClearConfirm(enabled: Boolean) {
        pendingClearConfirm.update { enabled }
    }

    /** Confirms the clear-all dialog (admin only): DELETE, then a stats re-fetch on success. */
    fun confirmClear() {
        if (!isAdmin || busy.value || !pendingClearConfirm.value) return
        busy.value = true
        pendingClearConfirm.value = false
        scope.launch { runClear() }
    }

    /** Fires the synthetic detection alert (admin only) and surfaces the dispatched sinks. */
    fun testFire() {
        if (!isAdmin || busy.value) return
        busy.value = true
        scope.launch { runTestFire() }
    }

    override fun onCleared() {
        scope.cancel()
    }

    private suspend fun runClear() {
        try {
            val verdict = try {
                val answer = api?.clearDetectionEvents()
                if (answer?.success == true) {
                    // The device log is emptied — mirror it in the session's
                    // feed right away (the wave-4 buffer-clear seam), then
                    // re-fetch the stats so the strip shows the reset.
                    session.clearDetectionFeed()
                    fetchStats(notifyOnFailure = false)
                    EventsEffect.Notice(R.string.companion_events_cleared)
                } else {
                    EventsEffect.Notice(R.string.companion_events_clear_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException.Auth) {
                EventsEffect.Notice(R.string.companion_events_signin_required)
            } catch (e: CompanionApiException.Forbidden) {
                EventsEffect.Notice(R.string.companion_events_forbidden)
            } catch (e: CompanionApiException) {
                EventsEffect.Notice(R.string.companion_events_clear_failed)
            } catch (e: IOException) {
                EventsEffect.Notice(R.string.companion_events_action_failed)
            }
            _effects.trySend(verdict)
        } finally {
            busy.value = false
        }
    }

    private suspend fun runTestFire() {
        try {
            val verdict = try {
                val answer = api?.detectionTest()
                if (answer != null) {
                    EventsEffect.TestFired(success = answer.success, dispatchedActions = answer.dispatchedActions)
                } else {
                    EventsEffect.Notice(R.string.companion_events_action_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException.Auth) {
                EventsEffect.Notice(R.string.companion_events_signin_required)
            } catch (e: CompanionApiException.Forbidden) {
                EventsEffect.Notice(R.string.companion_events_forbidden)
            } catch (e: CompanionApiException) {
                EventsEffect.Notice(R.string.companion_events_action_failed)
            } catch (e: IOException) {
                EventsEffect.Notice(R.string.companion_events_action_failed)
            }
            _effects.trySend(verdict)
        } finally {
            busy.value = false
        }
    }

    /**
     * One stats round trip; a refresh cancels the previous one so two taps
     * never double-fire. Non-Forbidden failures stay silent when
     * [notifyOnFailure] is false (the init fetch — the strip just stays
     * hidden); a Forbidden surfaces regardless, per the role contract.
     */
    private fun fetchStats(notifyOnFailure: Boolean) {
        statsJob?.cancel()
        statsJob = scope.launch {
            try {
                val fetched = api?.getDetectionStats()
                if (fetched != null) stats.update { fetched }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException.Forbidden) {
                _effects.trySend(EventsEffect.Notice(R.string.companion_events_forbidden))
            } catch (e: Exception) {
                if (notifyOnFailure) {
                    _effects.trySend(EventsEffect.Notice(R.string.companion_events_action_failed))
                }
            }
        }
    }

    class Factory(
        private val companion: CompanionGraph,
        private val session: DeviceSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // The paired role gates the destructive actions; a device that
            // left the roster reads admin (the device will 403 the writes
            // anyway — the Forbidden snackbar covers the stale snapshot).
            val device = companion.roster.find(session.deviceId)
            return EventsViewModel(
                session = session,
                api = device?.let { companion.apiClient(it) },
                isAdmin = device?.role != ROLE_VIEWER,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ) as T
        }
    }

    companion object {
        /** The roster role string that degrades the tab to read-only. */
        const val ROLE_VIEWER = "viewer"
    }
}

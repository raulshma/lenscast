package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.RemoteAuditEntry
import com.raulshma.lenscast.companion.client.dto.StreamClientsInfo
import com.raulshma.lenscast.companion.client.dto.SystemInfo
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The remote App-settings screen's diagnostics seam — the System tab's
 * diagnostics cards, folded into the paired device's settings surface as one
 * source (P6): the `GET /api/system` info card (plus its lens list), the
 * `GET /api/audit` trail (paged like the tab's), the `GET /api/stream/clients`
 * roster with kick, the restart/reset camera maintenance actions, and the
 * settings export/import pair. The LOCAL App settings screen never gains any
 * of this (parity = the local surface; this section is remote-only and is
 * delivered to the screen as a nullable object — null is the local backend).
 *
 * Reads poll on a WhileSubscribed beat (the [RemoteSettingsSource] idiom);
 * the audit page loads once on the beat and widens through [loadMoreAudit]
 * with the retired companion System tab's exact exhaustion rule. The page
 * rides [AuditBlock] — that tab's model, re-homed here when the tab shell
 * was retired (the settings screen's cards consume it verbatim).
 *
 * Role gate: [canManage] is the roster's admin verdict, and it is stricter
 * than the settings source's write gate — restart/reset and kick are
 * device-mutating lifecycle actions, so they stay admin-only (a viewer
 * pairing renders none of the maintenance affordances and the writes no-op).
 */
interface SettingsDiagnostics {

    /** The device's `GET /api/system` snapshot; null until one lands. */
    val info: StateFlow<SystemInfo?>

    /** The device's lens list (the info card's lens rows); null until one lands. */
    val lenses: StateFlow<LensesResponse?>

    /** The audit trail page (newest-first); null until the first page lands. */
    val audit: StateFlow<AuditBlock?>

    /** The stream client rosters (HTTP + RTSP); null until one lands. */
    val clients: StateFlow<StreamClientsInfo?>

    /** Outcome of the latest maintenance action (restart / reset camera). */
    val maintenanceStatus: StateFlow<MaintenanceStatus?>

    /** Whether the maintenance actions render at all (the roster's admin verdict). */
    val canManage: Boolean

    /** Widens the audit page by one page size (the tab's paging rule). */
    fun loadMoreAudit()

    /** Drops one HTTP viewer's connection (`DELETE /api/stream/clients/{id}`). */
    fun kickClient(id: String)

    /**
     * Restarts the DEVICE's app — `mode=soft` runs the in-process soft
     * ladder; null runs the deep (quit + relaunch) restart. The caller has
     * already confirmed the action (the dialog is the screen's).
     */
    fun restartApp(soft: Boolean)

    /** The bounded camera-subsystem recovery (`POST /api/system/reset-camera`). */
    fun resetCamera()

    /**
     * `GET /api/settings/export` — the versioned export envelope's raw JSON
     * (the same document the dashboard's export button downloads). Null on
     * failure; the caller owns what it does with the bytes (share sheet).
     */
    suspend fun exportSettings(): String?

    /**
     * `POST /api/settings/import` with a picked file's JSON (an export
     * envelope or a bare settings document — the route's own parser decides).
     * True when the device accepted it; the beat re-reads the resulting doc.
     */
    suspend fun importSettings(json: String): Boolean
}

/**
 * The audit trail's page: newest-first entries, the trail's total, the limit
 * the current page was fetched with, and the paging flags — [exhausted] once
 * the page covers the trail (or a bigger limit stopped returning more, the
 * server's own cap guard). Re-homed verbatim from the retired companion
 * System tab's model vocabulary; the settings screen's audit card renders it.
 */
data class AuditBlock(
    val entries: List<RemoteAuditEntry>,
    val total: Int,
    val limit: Int,
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
)

/** Outcome of a remote maintenance action (`restartApp` or `resetCamera`). */
sealed interface MaintenanceStatus {
    data class RestartSent(val soft: Boolean) : MaintenanceStatus
    data object RestartDeniedExactAlarm : MaintenanceStatus
    data class RestartFailed(val detail: String?) : MaintenanceStatus
    data object ResetCameraSent : MaintenanceStatus
    data class ResetCameraFailed(val detail: String?) : MaintenanceStatus
}

/**
 * The diagnostics seam's REMOTE backend over [LenscastApiClient]. One beat
 * polls `/api/system` + `/api/camera/lenses` + `/api/stream/clients` while a
 * screen is subscribed; the audit page loads with the tab's
 * [SYSTEM_AUDIT_PAGE_LIMIT] and widens on demand. Every read failure keeps
 * the last value (sections degrade independently, the tab's rule); every
 * maintenance write is fire-and-forget through the client, gated by
 * [canManage] (admin-only — the device's own RoleGatePolicy stays the real
 * authority).
 */
class RemoteDiagnosticsSource(
    /** The device's JSON client; null when the roster lost the device. */
    private val api: LenscastApiClient?,
    /** The roster role's admin verdict — the maintenance actions' gate. */
    override val canManage: Boolean,
    /** The poll beat (the injectable record; tests shrink it). */
    private val timings: ConsoleTimings = ConsoleTimings(),
    /** The scope the beat and the action round trips run on. */
    private val scope: CoroutineScope,
) : SettingsDiagnostics {

    private val _info = MutableStateFlow<SystemInfo?>(null)
    private val _lenses = MutableStateFlow<LensesResponse?>(null)
    private val _clients = MutableStateFlow<StreamClientsInfo?>(null)
    private val _audit = MutableStateFlow<AuditBlock?>(null)
    private val _maintenanceStatus = MutableStateFlow<MaintenanceStatus?>(null)

    override val maintenanceStatus: StateFlow<MaintenanceStatus?> = _maintenanceStatus.asStateFlow()

    /** Serializes kick/export/import round trips (one maintenance action at a time). */
    private val actionMutex = Mutex()

    /**
     * Serializes the roster READ across the beat's polls and the kick's
     * refresh: a poll that started before a kick cannot land its pre-kick
     * roster over the kick's own post-kick re-read (the mutex orders the
     * publications — a read holds the lock until its response is published).
     */
    private val rosterMutex = Mutex()

    /** Guards the audit page against overlapping loads. */
    private var auditLoadJob: Job? = null

    /**
     * The audit page's change stamp: a load records it before dialing and
     * publishes only if it still holds, so a response that started before a
     * superseding load cannot land over the newer page.
     */
    private val auditGeneration = AtomicInteger(0)

    /**
     * The activation refresh: replaces the audit snapshot with a fresh page
     * at [limit] (the current snapshot's own limit, so widened pagination
     * survives a deactivate/reactivate round trip). Supersedes any
     * in-flight [loadMoreAudit] — its job is cancelled and its stamped
     * response invalidated — so the earlier widening cannot overwrite the
     * refreshed page. True when a page landed.
     */
    private suspend fun refreshAudit(limit: Int): Boolean {
        auditLoadJob?.cancel()
        auditLoadJob = null
        auditGeneration.incrementAndGet()
        return loadAudit(limit)
    }

    /**
     * Fetches the audit page at [limit] and lands it as an [AuditBlock];
     * true when one landed. Exhaustion keeps BOTH verdicts: the trail's
     * coverage (`entries >= total`) AND the server's own page cap — a
     * response that returned strictly less than the page asked for while
     * the trail claims more (`entries < limit`) can never be widened into
     * the rest, the same verdict a widened [loadMoreAudit] lands. Without
     * the cap half, a reactivation refresh would un-exhaust a cap-limited
     * page (it re-reads only the total) and Load More would just re-dial
     * the same capped answer.
     */
    private suspend fun loadAudit(limit: Int): Boolean {
        val client = api ?: return false
        val generation = auditGeneration.get()
        val page = try {
            client.auditEntries(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (auditGeneration.get() != generation) return false
        _audit.value = AuditBlock(
            entries = page.entries,
            total = page.total,
            limit = limit,
            exhausted = page.entries.size >= page.total || page.entries.size < limit,
        )
        return true
    }

    /** Monotonic beat tick, the other sources' conflation guard. */
    private val beat: Flow<Long> = flow {
        var tick = 0L
        // The beat restarts from scratch on every activation (the shareIn
        // grace expiry cancels the upstream; the next subscribe re-runs the
        // cold flow), so this flag marks "the audit refresh has not landed
        // in this activation": it fires on the first tick — an exhausted
        // non-null page must not pin a stale trail across activations — and
        // STAYS set while the read keeps failing, so a transient failure on
        // the activation's first page retries on the next beat instead of
        // pinning a null audit forever (load-more cannot even start on a
        // null page to recover it).
        var auditRefreshPending = true
        while (true) {
            fetchInfo()
            fetchClients()
            if (auditRefreshPending) {
                auditRefreshPending = !refreshAudit(_audit.value?.limit ?: SYSTEM_AUDIT_PAGE_LIMIT)
            }
            emit(++tick)
            delay(timings.settingsPollMs)
        }
    }

    /**
     * The shared upstream every public flow derives from: collecting ANY of
     * them collects the beat (the RemoteAutomationSource rule — a screen
     * watching only `clients` still drives the poll), and every write's
     * landed value re-emits without waiting a tick. The cold [beat] is
     * shared ONCE ([shareIn], still on the [SUBSCRIBE_GRACE_MS] grace so it
     * parks when nothing subscribes): the four projections below all ride
     * this ONE polling loop instead of each spawning their own.
     */
    private val sharedBeat: Flow<Long> =
        beat.shareIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS))

    private val poll: Flow<Int> =
        merge(sharedBeat, _info, _lenses, _clients, _audit).map { _info.value?.processUptimeMs?.toInt() ?: 0 }

    override val info: StateFlow<SystemInfo?> =
        poll.map { _info.value }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), null)

    override val lenses: StateFlow<LensesResponse?> =
        poll.map { _lenses.value }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), null)

    override val clients: StateFlow<StreamClientsInfo?> =
        poll.map { _clients.value }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), null)

    override val audit: StateFlow<AuditBlock?> =
        poll.map { _audit.value }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), null)

    private suspend fun fetchInfo() {
        val client = api ?: return
        try {
            _info.value = client.getSystemInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // transient failure keeps the last snapshot
        }
        // The lens list rides the same beat and degrades independently (the
        // tab's rule): a system-info failure doesn't skip the read, and a
        // lens failure keeps the last answer.
        try {
            _lenses.value = client.getLenses()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun fetchClients() {
        val client = api ?: return
        rosterMutex.withLock {
            try {
                _clients.value = client.getStreamClients()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }


    override fun loadMoreAudit() {
        val client = api ?: return
        val current = _audit.value ?: return
        if (current.loadingMore || current.exhausted) return
        if (auditLoadJob?.isActive == true) return
        _audit.value = current.copy(loadingMore = true)
        val nextLimit = current.limit + SYSTEM_AUDIT_PAGE_LIMIT
        auditLoadJob = scope.launch {
            try {
                // Stamp before dialing: a response that predates a
                // superseding refresh (activation or otherwise) must not
                // land over the refreshed page.
                val myGeneration = auditGeneration.incrementAndGet()
                val page = try {
                    client.auditEntries(nextLimit)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (auditGeneration.get() == myGeneration) {
                    _audit.value = if (page == null) {
                        current.copy(loadingMore = false)
                    } else {
                        AuditBlock(
                            entries = page.entries,
                            total = page.total,
                            limit = nextLimit,
                            loadingMore = false,
                            exhausted = page.entries.size >= page.total ||
                                page.entries.size <= current.entries.size,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    override fun kickClient(id: String) {
        val client = api ?: return
        if (!canManage) return
        scope.launch {
            actionMutex.withLock {
                try {
                    client.kickClient(id)
                    fetchClients()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun restartApp(soft: Boolean) {
        val client = api ?: return
        if (!canManage) return
        scope.launch {
            actionMutex.withLock {
                try {
                    val response = client.restartApp(if (soft) RESTART_MODE_SOFT else null)
                    _maintenanceStatus.value = when {
                        response.success -> MaintenanceStatus.RestartSent(soft)
                        response.reason == RESTART_REASON_EXACT_ALARM_NOT_GRANTED ->
                            MaintenanceStatus.RestartDeniedExactAlarm
                        else -> MaintenanceStatus.RestartFailed(
                            response.hint ?: response.error ?: response.reason,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CompanionApiException) {
                    _maintenanceStatus.value = MaintenanceStatus.RestartFailed(e.message)
                } catch (_: IOException) {
                    // The device answers OK before its teardown; a dropped
                    // connection mid-teardown is indistinguishable from the
                    // deep restart's socket death.
                    _maintenanceStatus.value = MaintenanceStatus.RestartSent(soft)
                }
            }
        }
    }

    override fun resetCamera() {
        val client = api ?: return
        if (!canManage) return
        scope.launch {
            actionMutex.withLock {
                try {
                    val response = client.resetCamera()
                    _maintenanceStatus.value = if (response.success) {
                        MaintenanceStatus.ResetCameraSent
                    } else {
                        MaintenanceStatus.ResetCameraFailed(
                            response.hint ?: response.error ?: response.reason,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _maintenanceStatus.value = MaintenanceStatus.ResetCameraFailed(e.message)
                }
            }
        }
    }

    override suspend fun exportSettings(): String? {
        val client = api ?: return null
        // The export is not viewer-safe: the write-only credentials serialize
        // blank, but `webhookHeaders` is a user-authored map that round-trips
        // in full and frequently carries an Authorization header — the same
        // admin-only verdict the device's RoleGatePolicy now answers the
        // route with. Enforced here too, before the bytes leave the source.
        if (!canManage) return null
        return try {
            actionMutex.withLock { client.exportSettings() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun importSettings(json: String): Boolean {
        val client = api ?: return false
        if (!canManage) return false
        return try {
            actionMutex.withLock { client.importSettings(json).success }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {

        /** The subscribe grace that parks the beat when no screen shows. */
        private const val SUBSCRIBE_GRACE_MS = 5_000L

        /** The restart route's soft-mode query value (`?mode=soft`; absent = deep). */
        const val RESTART_MODE_SOFT = "soft"

        /** Wire refusal reason when `SCHEDULE_EXACT_ALARM` is not granted on the remote device. */
        const val RESTART_REASON_EXACT_ALARM_NOT_GRANTED = "exact_alarm_not_granted"

        /**
         * The audit page size — the System tab's own constant's value (four
         * steps cover the server's 200-entry cap on the device's
         * single-threaded lane), duplicated as a const here because the tab
         * keeps its constant private to its ViewModel.
         */
        const val SYSTEM_AUDIT_PAGE_LIMIT = 50
    }
}

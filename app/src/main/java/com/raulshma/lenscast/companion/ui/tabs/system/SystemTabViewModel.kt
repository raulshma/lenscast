package com.raulshma.lenscast.companion.ui.tabs.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.AuditResponse
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.SettingsUpdate
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.companion.client.dto.SystemInfo
import com.raulshma.lenscast.companion.di.CompanionGraph
import com.raulshma.lenscast.companion.session.DeviceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The system tab's ViewModel — the state owner for the four cards (device
 * info + lenses, the detection/capture settings editor, the web-session
 * roster, the audit trail), one [LenscastApiClient] against the paired
 * device.
 *
 * Load: one parallel section burst on [init] and on manual refresh — info,
 * settings, identity, audit — with every section landing independently (a
 * failed settings read degrades to that card's "unavailable" note; it never
 * blanks the cards that did land). A refresh publishes into the existing
 * [SystemUiState.Ready] (cards never blank while their re-read runs); the
 * first load shows [SystemUiState.Loading]. `Forbidden` anywhere maps to the
 * snackbar effect (the role the roster knew has gone stale); a 401 surfaces
 * through the same effect (the re-pair cue).
 *
 * Role: the write surface gates on the device role — the pairing-time
 * snapshot the factory resolves off the roster, overridden by the live
 * GET /api/auth/session answer once it lands. `viewer` never edits settings
 * and never fetches (or revokes) the admin-only session roster; the server's
 * RoleGatePolicy stays the real authority and its 403 still maps to the
 * snackbar effect.
 *
 * Settings saves are the web console's GET-merge-PUT, serialized: fetch the
 * fresh document, apply ONLY the fields this editor owns onto it (a PUT
 * section persists wholesale, so the merge is what protects concurrently
 * changed fields — see [withEditorFields]), PUT the merged streaming
 * section, and on success adopt the merged document as confirmed. Any
 * failure reverts the draft (the editor falls back to the last confirmed
 * document) and effects the revert snackbar. The mutex serializes saves so
 * two overlapping saves cannot interleave their GET-merge-PUT sequences —
 * the later save would otherwise PUT a pre-save snapshot over the earlier
 * save's result (the exact race the web client's save chain guards).
 *
 * Strict MVI: sealed [SystemUiState], atomic `_state.update` writes, effects
 * through `Channel(BUFFERED)` + `receiveAsFlow`, real threads — no virtual
 * time anywhere.
 */
class SystemTabViewModel(
    /** The device's API client; null when the roster/credentials are gone — the tab lands failed. */
    private val api: LenscastApiClient?,
    /** The pairing-time role snapshot from the roster (`"admin"` | `"viewer"` | null). */
    private val rosterRole: String?,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<SystemUiState>(SystemUiState.Loading)
    val state = _state.asStateFlow()

    private val _effects = Channel<SystemTabEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /**
     * The live role from the newest successful GET /api/auth/session — the
     * roster snapshot until the device itself answers.
     */
    @Volatile
    private var liveRole: String? = null

    /** The effective role: the device's live answer wins over the roster's. */
    private val role: String?
        get() = liveRole ?: rosterRole

    private val isViewer: Boolean
        get() = role == ROLE_VIEWER

    /** Serializes settings saves (the GET-merge-PUT chain's lock). */
    private val saveMutex = Mutex()

    /** Guards against overlapping refresh bursts; one at a time. */
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /**
     * Re-runs the parallel section burst. Sections that fail keep whatever
     * they rendered before; [SystemUiState.Ready.failed] turns on only when
     * nothing is present AND everything failed (the retry affordance).
     */
    fun refresh() {
        val client = api
        if (client == null) {
            _state.update { current ->
                when (current) {
                    is SystemUiState.Ready -> current.copy(failed = true, refreshing = false)
                    else -> SystemUiState.Ready(failed = true)
                }
            }
            return
        }
        if (refreshJob?.isActive == true) return
        val seed = _state.value as? SystemUiState.Ready
        if (seed == null) {
            _state.value = SystemUiState.Loading
        } else {
            _state.update { current ->
                if (current is SystemUiState.Ready) current.copy(refreshing = true) else current
            }
        }
        refreshJob = scope.launch {
            val info = async { readInfo(client) }
            val settings = async { readSettings(client) }
            val identity = async { readIdentity(client) }
            val audit = async { readAuditPage(client, AUDIT_PAGE_LIMIT) }
            val infoPair = info.await()
            val settingsBlock = settings.await()
            val identityBlock = identity.await()
            val auditBlock = audit.await()
            _state.update { current ->
                val previous = current as? SystemUiState.Ready
                val ready = SystemUiState.Ready(
                    info = infoPair?.first ?: previous?.info,
                    lenses = infoPair?.second ?: previous?.lenses,
                    settings = settingsBlock ?: previous?.settings,
                    identity = identityBlock ?: previous?.identity,
                    audit = auditBlock ?: previous?.audit,
                )
                val nothing = ready.info == null && ready.settings == null &&
                    ready.identity == null && ready.audit == null
                ready.copy(failed = nothing, refreshing = false)
            }
        }
    }

    /**
     * Applies one editor edit onto the base document. No-op for viewers and
     * while a save is in flight; the edit becomes the draft overlay (the
     * dirty flag) until saved or reverted.
     */
    fun editSettings(transform: (StreamingSettings) -> StreamingSettings) {
        if (isViewer) return
        _state.update { current ->
            if (current !is SystemUiState.Ready) return@update current
            val block = current.settings ?: return@update current
            if (block.saving) return@update current
            val base = block.draft ?: block.document.streaming
            current.copy(settings = block.copy(draft = transform(base)))
        }
    }

    /** Drops the draft overlay — back to the last confirmed document. */
    fun revertSettings() {
        _state.update { current ->
            if (current !is SystemUiState.Ready) {
                current
            } else {
                current.copy(settings = current.settings?.copy(draft = null))
            }
        }
    }

    /**
     * The serialized GET-merge-PUT save (see the class KDoc). No-op when
     * nothing is dirty, a save is already running, or the session is viewer.
     */
    fun saveSettings() {
        val client = api ?: return
        if (isViewer) return
        scope.launch {
            saveMutex.withLock {
                val ready = _state.value as? SystemUiState.Ready ?: return@withLock
                val block = ready.settings ?: return@withLock
                val draft = block.draft ?: return@withLock
                if (block.saving) return@withLock
                _state.update { current ->
                    if (current is SystemUiState.Ready) {
                        current.copy(settings = current.settings?.copy(saving = true))
                    } else {
                        current
                    }
                }
                try {
                    val fresh = client.getSettings()
                    val merged = fresh.streaming.withEditorFields(draft)
                    client.putSettings(SettingsUpdate(streaming = merged))
                    _state.update { current ->
                        if (current is SystemUiState.Ready) {
                            current.copy(
                                settings = SettingsBlock(
                                    document = AllSettings(camera = fresh.camera, streaming = merged),
                                    draft = null,
                                    saving = false,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                    _effects.trySend(SystemTabEffect.Notice(R.string.companion_system_settings_saved))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CompanionApiException) {
                    // Revert: drop the draft, back to the confirmed document.
                    _state.update { current ->
                        if (current is SystemUiState.Ready) {
                            current.copy(
                                settings = current.settings?.copy(saving = false, draft = null),
                            )
                        } else {
                            current
                        }
                    }
                    _effects.trySend(
                        when (e) {
                            is CompanionApiException.Forbidden ->
                                SystemTabEffect.Notice(R.string.companion_system_error_forbidden)
                            else ->
                                SystemTabEffect.Notice(R.string.companion_system_settings_save_failed)
                        },
                    )
                }
            }
        }
    }

    /** Revokes one web session by token prefix (admin; the UI hides it for viewers). */
    fun revokeSession(prefix: String) {
        val client = api ?: return
        if (isViewer) return
        setIdentityBusy(true)
        scope.launch {
            try {
                client.revokeSession(prefix)
                _effects.trySend(SystemTabEffect.Notice(R.string.companion_system_session_revoked))
                reloadIdentity(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException) {
                _effects.trySend(SystemTabEffect.forError(e))
                setIdentityBusy(false)
            }
        }
    }

    /** Arms/cancels the logout-confirm dialog. */
    fun setPendingLogout(pending: Boolean) {
        _state.update { current ->
            if (current is SystemUiState.Ready) {
                current.copy(identity = current.identity?.copy(pendingLogout = pending))
            } else {
                current
            }
        }
    }

    /** Drops this app's own dashboard session server-side, then re-reads the identity. */
    fun logout() {
        val client = api ?: return
        setPendingLogout(false)
        setIdentityBusy(true)
        scope.launch {
            try {
                client.logout()
                _effects.trySend(SystemTabEffect.Notice(R.string.companion_system_logged_out))
                reloadIdentity(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException) {
                _effects.trySend(SystemTabEffect.forError(e))
                setIdentityBusy(false)
            }
        }
    }

    /**
     * Widens the audit page by one page size and re-fetches (the API has no
     * cursor: newest-first `take(limit)` per call). Marks the page exhausted
     * when the trail is covered or a bigger limit stopped returning more
     * (the server's entry cap).
     */
    fun loadMoreAudit() {
        val client = api ?: return
        val ready = _state.value as? SystemUiState.Ready ?: return
        val audit = ready.audit ?: return
        if (audit.loadingMore || audit.exhausted) return
        _state.update { current ->
            if (current is SystemUiState.Ready) {
                current.copy(audit = current.audit?.copy(loadingMore = true))
            } else {
                current
            }
        }
        val nextLimit = audit.limit + AUDIT_PAGE_LIMIT
        scope.launch {
            // readAuditEntries maps its own failures to effects and lands
            // null; either way the page's spinner state resolves here.
            val page = readAuditEntries(client, nextLimit)
            _state.update { current ->
                if (current !is SystemUiState.Ready) return@update current
                val block = current.audit ?: return@update current
                current.copy(
                    audit = if (page == null) {
                        block.copy(loadingMore = false)
                    } else {
                        block.copy(
                            entries = page.entries,
                            total = page.total,
                            limit = nextLimit,
                            loadingMore = false,
                            exhausted = page.entries.size >= page.total ||
                                page.entries.size <= audit.entries.size,
                        )
                    },
                )
            }
        }
    }

    // ── Section readers (each lands independently; errors map to effects) ──

    /** GET /api/system + GET /api/camera/lenses, together the info card. */
    private suspend fun readInfo(client: LenscastApiClient): Pair<SystemInfo, LensesResponse?>? =
        guarded {
            val info = client.getSystemInfo()
            val lenses = try {
                client.getLenses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException) {
                onSectionError(e)
                null
            }
            info to lenses
        }

    /** GET /api/settings → the editor block (all roles read; viewers render read-only). */
    private suspend fun readSettings(client: LenscastApiClient): SettingsBlock? =
        guarded { SettingsBlock(document = client.getSettings()) }

    /**
     * The auth trio: status → session (the live role capture) → the session
     * roster, admin sessions only when a sign-in is required and live.
     */
    private suspend fun readIdentity(client: LenscastApiClient): IdentityBlock? =
        guarded {
            val status = client.authStatus()
            val session = client.authSession()
            if (session.authenticated) liveRole = session.role
            val sessions = if (status.required && session.authenticated && !isViewer) {
                try {
                    client.authSessions().sessions
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CompanionApiException) {
                    onSectionError(e)
                    null
                }
            } else {
                null
            }
            IdentityBlock(
                authRequired = status.required,
                authenticated = session.authenticated,
                role = session.role,
                sessions = sessions,
            )
        }

    /** GET /api/audit at the page limit → the newest-first page. */
    private suspend fun readAuditPage(client: LenscastApiClient, limit: Int): AuditBlock? {
        val page = readAuditEntries(client, limit) ?: return null
        return AuditBlock(
            entries = page.entries,
            total = page.total,
            limit = limit,
            exhausted = page.entries.size >= page.total,
        )
    }

    private suspend fun readAuditEntries(client: LenscastApiClient, limit: Int): AuditResponse? =
        guarded { client.auditEntries(limit) }

    /** The shared section guard: map failures to effects, sections land null. */
    private inline fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompanionApiException) {
            onSectionError(e)
            null
        }

    private fun onSectionError(e: CompanionApiException) {
        _effects.trySend(SystemTabEffect.forError(e))
    }

    /** Toggles the auth card's busy flag (revoke/logout round trips). */
    private fun setIdentityBusy(busy: Boolean) {
        _state.update { current ->
            if (current is SystemUiState.Ready) {
                current.copy(identity = current.identity?.copy(busy = busy))
            } else {
                current
            }
        }
    }

    /** Re-runs just the identity section into the live state (post revoke/logout). */
    private suspend fun reloadIdentity(client: LenscastApiClient) {
        val identity = readIdentity(client)
        _state.update { current ->
            if (current is SystemUiState.Ready) {
                current.copy(identity = identity ?: current.identity?.copy(busy = false))
            } else {
                current
            }
        }
    }

    /**
     * The save-side merge: the editor's draft fields applied onto the
     * freshly fetched streaming section. ONLY the fields this v1 editor owns
     * ride from the draft — everything else stays exactly what the device
     * just answered (concurrent changes are never clobbered).
     */
    private fun StreamingSettings.withEditorFields(draft: StreamingSettings): StreamingSettings = copy(
        motionDetectionEnabled = draft.motionDetectionEnabled,
        motionSensitivityPercent = draft.motionSensitivityPercent,
        motionRecordingEnabled = draft.motionRecordingEnabled,
        motionPostRollSeconds = draft.motionPostRollSeconds,
        motionCooldownSeconds = draft.motionCooldownSeconds,
        motionArmScheduleEnabled = draft.motionArmScheduleEnabled,
        motionArmStartMinute = draft.motionArmStartMinute,
        motionArmEndMinute = draft.motionArmEndMinute,
        motionArmDaysMask = draft.motionArmDaysMask,
        soundDetectionEnabled = draft.soundDetectionEnabled,
        soundThresholdPercent = draft.soundThresholdPercent,
        soundAdaptiveNoiseFloor = draft.soundAdaptiveNoiseFloor,
        soundRecordingEnabled = draft.soundRecordingEnabled,
        soundCooldownSeconds = draft.soundCooldownSeconds,
        mlDetectionEnabled = draft.mlDetectionEnabled,
        mlMinScorePercent = draft.mlMinScorePercent,
        mlIncludePerson = draft.mlIncludePerson,
        mlIncludePets = draft.mlIncludePets,
        mlIncludeVehicles = draft.mlIncludeVehicles,
        tamperDetectionEnabled = draft.tamperDetectionEnabled,
        captureRetentionDays = draft.captureRetentionDays,
        eventRetentionDays = draft.eventRetentionDays,
        storageQuotaMb = draft.storageQuotaMb,
    )

    class Factory(
        private val companion: CompanionGraph,
        private val session: DeviceSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // The roster resolves the device (and its pairing-time role); the
            // session hands over its API client. Either seam can be gone by
            // the time this runs (a removed device, cleared credentials) —
            // the tab lands on its failed state rather than crashing.
            val role = companion.roster.find(session.deviceId)?.role
            val api = try {
                session.api()
            } catch (_: IllegalStateException) {
                null
            }
            return SystemTabViewModel(
                api = api,
                rosterRole = role,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ) as T
        }
    }

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_VIEWER = "viewer"

        /**
         * The audit page size: four steps cover the server's whole trail cap
         * (200), keeping each re-fetch small on the device's single-threaded
         * NanoHTTPD lane.
         */
        const val AUDIT_PAGE_LIMIT = 50
    }
}

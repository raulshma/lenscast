package com.raulshma.lenscast.companion.ui.tabs.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The media tab's state owner — strict MVI over the device's gallery,
 * recordings and interval-capture surface (the web dashboard's gallery
 * console, ported to the companion tab).
 *
 * Loads, on construction, four independent reads in parallel: the gallery's
 * first page, the recording status, the NVR recording sessions, and the
 * interval-capture status. Each lands its own state slice through one atomic
 * [_state.update]; a section's failure only names that section in
 * [MediaUiState.Ready.failedSections] (the rest renders whatever arrived),
 * and `refresh()` re-runs everything so the error banner can offer a retry.
 *
 * Gallery paging mirrors the web client: [getGallery][LenscastApiClient.getGallery]
 * pages of [PAGE_SIZE] accumulate — a page's items join the already-loaded
 * ones (deduped by id, a server replay guard) — and [loadMore] fetches the
 * next page when the grid scrolls near its end; the VM re-entrancy-guards so
 * double triggers never double-fetch. Switching the type filter restarts
 * paging from page 1 with the `type` param (filter vocabularies never mix).
 *
 * Deletion is admin-only (`canManage` comes from the roster role): single
 * delete arms `PendingDelete.Single`, long-press multi-select + batch arms
 * `PendingDelete.Batch`; the confirm executes one round trip whose verdict
 * maps 2xx → items removed + `Deleted(count)`, 401 → `AuthRequired`, 403 →
 * `Forbidden` (viewer/CSRF refusal — nothing removed), anything else →
 * `ActionFailed`. Recording start/stop and interval start/stop follow the
 * same verdict mapping, re-polling their status read so the cards show the
 * device's answer rather than an optimistic guess.
 */
class MediaViewModel(
    private val api: LenscastApiClient,
    /** The roster role gate — `viewer` hides every destructive control. */
    val canManage: Boolean,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<MediaUiState>(MediaUiState.Loading)
    val state = _state.asStateFlow()

    private val _effects = Channel<MediaEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var galleryJob: Job? = null
    private var recordingsJob: Job? = null
    private var intervalJob: Job? = null
    private var commandJob: Job? = null

    /** Pages already folded into the state; the `page` param's next value. */
    private var loadedPages = 0

    init {
        refresh()
    }

    /** Reloads every section — the error banner's retry and pull-to-refresh stand-in. */
    fun refresh() {
        loadGalleryPage(reset = true)
        loadRecordings()
        loadInterval()
    }

    // ── Gallery ──

    /**
     * Fetches the next gallery page when the grid nears its end (the grid
     * calls this from a scroll-end trigger; repeat taps/calls are guarded).
     */
    fun loadMore() {
        val ready = _state.value as? MediaUiState.Ready ?: return
        if (!ready.hasMore || ready.isLoadingMore) return
        loadGalleryPage(reset = false)
    }

    /** Switches the type filter: clears the loaded pages and restarts at page 1. */
    fun setFilter(filter: MediaGalleryFilter) {
        val ready = _state.value as? MediaUiState.Ready ?: return
        if (ready.filter == filter) return
        updateState { current ->
            current.copy(
                filter = filter,
                items = emptyList(),
                total = 0,
                viewer = null,
                selection = emptySet(),
            )
        }
        loadGalleryPage(reset = true)
    }

    private fun loadGalleryPage(reset: Boolean) {
        galleryJob?.cancel()
        // The wire page index is 0-based (GalleryPage.of drops page*pageSize;
        // the web client's first page rides with no page param at all). Sending
        // 1 as the FIRST page asked for the second: the real server returned an
        // empty items array next to total=2 and the grid rendered "No captures
        // on this device" under two captures. [loadedPages] counts pages.
        val page = if (reset) 0 else loadedPages
        val filter = (_state.value as? MediaUiState.Ready)?.filter ?: MediaGalleryFilter.ALL
        if (reset) {
            updateState { it.copy(isLoadingMore = false, hasMore = true) }
        } else {
            updateState { it.copy(isLoadingMore = true) }
        }
        galleryJob = scope.launch {
            val result = attempt { api.getGallery(page = page, pageSize = PAGE_SIZE, type = filter.wire) }
            result
                .onSuccess { response ->
                    loadedPages = page + 1
                    updateState { current ->
                        // reset replaces the pages; a next page appends to the
                        // already-loaded ones, deduped by id (a server replay
                        // or an overlapping page must not duplicate tiles).
                        val base = if (reset) emptyList() else current.items
                        val seen = base.mapTo(mutableSetOf()) { it.id }
                        val merged = base + response.items
                            .map { it.toUiItem(api.mediaUrl(it.id), api.mediaDownloadUrl(it.id)) }
                            .filter { seen.add(it.id) }
                        current.copy(
                            items = merged,
                            total = response.total,
                            hasMore = response.hasMore,
                            isLoadingMore = false,
                            failedSections = current.failedSections - MediaSection.GALLERY,
                        )
                    }
                }
                .onFailure { failure ->
                    updateState { current ->
                        current.copy(
                            isLoadingMore = false,
                            // A failed load-more keeps the loaded pages; only a
                            // failed page-1 flags the banner (the grid is empty
                            // and the user needs the explanation).
                            hasMore = if (reset) false else current.hasMore,
                            items = if (reset) emptyList() else current.items,
                            failedSections = current.failedSections + MediaSection.GALLERY,
                        )
                    }
                    if (failure is CompanionApiException) notifyFailure(failure)
                }
        }
    }

    // ── Viewer ──

    /** Opens the in-tab viewer for [item]. */
    fun openViewer(item: MediaUiItem) {
        updateState { current ->
            current.copy(
                viewer = MediaViewerTarget(
                    id = item.id,
                    isVideo = item.isVideo,
                    url = item.mediaUrl,
                    downloadUrl = item.downloadUrl,
                    fileName = item.fileName,
                ),
            )
        }
    }

    /** Closes the in-tab viewer (the player surface releases with it). */
    fun closeViewer() {
        updateState { it.copy(viewer = null) }
    }

    // ── Multi-select ──

    /** Toggles one item's membership in the selection (long-press on a tile). */
    fun toggleSelect(itemId: String) {
        updateState { current ->
            current.copy(
                selection = if (itemId in current.selection) {
                    current.selection - itemId
                } else {
                    current.selection + itemId
                },
            )
        }
    }

    /** Clears the selection without deleting anything. */
    fun clearSelection() {
        updateState { it.copy(selection = emptySet()) }
    }

    // ── Delete ──

    /** Arms (or disarms, for null) the single-item delete-confirm dialog. */
    fun requestDelete(itemId: String?) {
        if (itemId == null) {
            updateState { it.copy(pendingDelete = null) }
            return
        }
        val item = (_state.value as? MediaUiState.Ready)?.items?.firstOrNull { it.id == itemId }
        if (item != null) updateState { it.copy(pendingDelete = MediaPendingDelete.Single(item.id, item.fileName)) }
    }

    /** Arms the batch delete-confirm dialog over the current selection. */
    fun requestBatchDelete() {
        val selection = (_state.value as? MediaUiState.Ready)?.selection.orEmpty()
        if (selection.isEmpty()) return
        updateState { it.copy(pendingDelete = MediaPendingDelete.Batch(selection.toList())) }
    }

    /** Closes the armed dialog without deleting. */
    fun cancelPendingDelete() {
        updateState { it.copy(pendingDelete = null) }
    }

    /** Executes the armed delete — the confirm button's action. */
    fun confirmPendingDelete() {
        val ready = _state.value as? MediaUiState.Ready ?: return
        val pending = ready.pendingDelete ?: return
        if (ready.busy) return
        updateState { it.copy(busy = true) }
        commandJob?.cancel()
        commandJob = scope.launch {
            val result = when (pending) {
                is MediaPendingDelete.Single -> attempt { api.deleteMedia(pending.id) }
                is MediaPendingDelete.Batch -> attempt { api.batchDeleteMedia(pending.ids) }
            }
            val count = when (pending) {
                is MediaPendingDelete.Single -> 1
                is MediaPendingDelete.Batch -> pending.ids.size
            }
            val deletedIds = when (pending) {
                is MediaPendingDelete.Single -> setOf(pending.id)
                is MediaPendingDelete.Batch -> pending.ids.toSet()
            }
            result.fold(
                onSuccess = {
                    updateState { current ->
                        current.copy(
                            items = current.items.filterNot { it.id in deletedIds },
                            total = (current.total - count).coerceAtLeast(0),
                            selection = current.selection - deletedIds,
                            pendingDelete = null,
                            viewer = current.viewer?.takeUnless { it.id in deletedIds },
                            busy = false,
                        )
                    }
                    _effects.trySend(MediaEffect.Deleted(count))
                },
                onFailure = { failure ->
                    updateState { it.copy(pendingDelete = null, busy = false) }
                    if (failure is CompanionApiException) notifyFailure(failure) else _effects.trySend(MediaEffect.ActionFailed)
                },
            )
        }
    }

    // ── Recordings card ──

    private fun loadRecordings() {
        recordingsJob?.cancel()
        recordingsJob = scope.launch {
            val status = attempt { api.recordingStatus() }
            val sessions = attempt { api.recordingsSessions() }
            updateState { current ->
                current.copy(
                    recording = status.getOrNull()?.let {
                        MediaRecordingUi(
                            isRecording = it.isRecording,
                            elapsedSeconds = it.elapsedSeconds,
                            isScheduled = it.isScheduled ?: false,
                        )
                    } ?: current.recording,
                    sessions = sessions.getOrNull()
                        ?.sessions
                        ?.sortedByDescending { it.startMs }
                        ?.map { MediaSessionUi(it.id, it.startMs, it.endMs, it.trigger, it.mediaId != null) }
                        ?: current.sessions,
                    failedSections = current.failedSections +
                        (if (status.isFailure) setOf(MediaSection.RECORDINGS) else emptySet()) +
                        (if (sessions.isFailure) setOf(MediaSection.RECORDINGS) else emptySet()),
                )
            }
            status.onFailure { f -> if (f is CompanionApiException) notifyFailure(f) }
        }
    }

    /** POST /api/recording/start, then re-polls the status so the card tells the truth. */
    fun startRecording() {
        runCommand { api.recordingStart() }.invokeOnCompletion { loadRecordings() }
    }

    /** POST /api/recording/stop, then re-polls the status. */
    fun stopRecording() {
        runCommand { api.recordingStop() }.invokeOnCompletion { loadRecordings() }
    }

    // ── Interval capture card ──

    private fun loadInterval() {
        intervalJob?.cancel()
        intervalJob = scope.launch {
            val result = attempt { api.intervalCaptureStatus() }
            updateState { current ->
                val status = result.getOrNull()
                current.copy(
                    interval = status?.let {
                        MediaIntervalUi(isRunning = it.isRunning, completedCaptures = it.completedCaptures)
                    } ?: current.interval,
                    failedSections = current.failedSections +
                        (if (result.isFailure) setOf(MediaSection.INTERVAL) else emptySet()),
                )
            }
            result.onFailure { f -> if (f is CompanionApiException) notifyFailure(f) }
        }
    }

    /**
     * The interval editor's draft: the raw text and its validity against the
     * device's accepted range ([MIN_INTERVAL_SECONDS]..[MAX_INTERVAL_SECONDS]
     * — the server's IntervalCapturePolicy bounds and the web console's input
     * range). The draft lives in the state tree (strict MVI); the field's
     * error copy and the Start button's enablement render from it.
     */
    fun setIntervalDraft(text: String) {
        val seconds = text.toIntOrNull()
        val valid = seconds != null && seconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS
        updateState { it.copy(intervalDraft = text, intervalDraftValid = valid) }
    }

    /**
     * POST /api/capture/interval/start at the editor's validated cadence
     * (an out-of-range draft is a silent no-dial — the field already shows
     * the error), then re-polls the status.
     */
    fun startInterval() {
        val ready = _state.value as? MediaUiState.Ready ?: return
        val seconds = ready.intervalDraft.toIntOrNull()
        if (seconds == null || seconds !in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) return
        runCommand { api.intervalCaptureStart(intervalSeconds = seconds) }.invokeOnCompletion { loadInterval() }
    }

    /** POST /api/capture/interval/stop, then re-polls the status. */
    fun stopInterval() {
        runCommand { api.intervalCaptureStop() }.invokeOnCompletion { loadInterval() }
    }

    // ── Internals ──

    /**
     * One write path for every state change: the transform sees only Ready
     * (Loading folds to a fresh empty Ready — pre-seeded with this session's
     * role gate — so the first arriving section publishes the tab), and the
     * whole update is one atomic swap.
     */
    private inline fun updateState(transform: (MediaUiState.Ready) -> MediaUiState.Ready) {
        _state.update { current ->
            when (current) {
                is MediaUiState.Ready -> transform(current)
                is MediaUiState.Loading -> transform(MediaUiState.Ready(canManage = canManage))
            }
        }
    }

    /** A guarded command: busy-gated, verdict-mapped, cancellable. */
    private fun runCommand(block: suspend () -> Any): Job {
        val ready = _state.value as? MediaUiState.Ready
        if (ready?.busy == true) return Job().apply { complete() }
        updateState { it.copy(busy = true) }
        commandJob?.cancel()
        val job = scope.launch {
            val result = attempt(block)
            updateState { it.copy(busy = false) }
            result.onFailure { failure ->
                if (failure is CompanionApiException) notifyFailure(failure) else _effects.trySend(MediaEffect.ActionFailed)
            }
        }
        commandJob = job
        return job
    }

    /** The failure → effect mapping every destructive/command path shares. */
    private fun notifyFailure(failure: CompanionApiException) {
        when (failure) {
            is CompanionApiException.Auth -> _effects.trySend(MediaEffect.AuthRequired)
            is CompanionApiException.Forbidden -> _effects.trySend(MediaEffect.Forbidden)
            else -> _effects.trySend(MediaEffect.ActionFailed)
        }
    }

    /**
     * Result capture that never swallows cancellation (the stdlib's
     * runCatching would turn a cancelled load into a verdict).
     */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * The factory: the tab resolves the device's roster snapshot (the role
     * gate) and builds the ONE client both lanes share, then hands both here
     * — mirroring the [com.raulshma.lenscast.companion.ui.DevicesViewModel]
     * factory idiom (app-scope-attached SupervisorJob on Main.immediate).
     */
    class Factory(
        private val api: LenscastApiClient,
        private val canManage: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MediaViewModel(
                api = api,
                canManage = canManage,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ) as T
        }
    }

    companion object {
        /** The gallery page size (the web console's 30-per-page grid). */
        const val PAGE_SIZE = 30

        /** The interval editor's default cadence (the v1 start button's value). */
        const val DEFAULT_INTERVAL_SECONDS = 60

        /**
         * The interval-capture cadence range the device accepts — the server
         * policy's own bounds (IntervalCapturePolicy.MIN/MAX_INTERVAL_SECONDS)
         * and the web console editor's input range.
         */
        const val MIN_INTERVAL_SECONDS = 1
        const val MAX_INTERVAL_SECONDS = 3600
    }
}

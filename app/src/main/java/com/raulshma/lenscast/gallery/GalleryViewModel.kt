package com.raulshma.lenscast.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.DecryptedPhotoCache
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class GalleryFilter {
    ALL, PHOTOS, VIDEOS, FAVORITES
}

class GalleryViewModel(
    private val captureHistoryStore: CaptureHistoryStore,
    /**
     * The decrypted-photo cache behind Coil when media encryption is on;
     * null (tests, or encryption never relevant) disables the warm loop.
     */
    private val photoCache: DecryptedPhotoCache? = null,
    /** The undo window length; injectable so tests can shrink it. */
    private val stagingWindowMs: Long = DeleteStagingPolicy.DEFAULT_WINDOW_MS,
    /**
     * Outlives this ViewModel: the onCleared force-commit must finish after
     * the screen (and its scope) is gone. The app's own scope in production.
     */
    private val appScope: CoroutineScope,
) : ViewModel() {

    init {
        captureHistoryStore.refreshFromMediaStore()
        // The decryption warm loop: encrypted photos become cache files the
        // grid/viewer can hand Coil; encrypted videos are collected into the
        // placeholder set (they play through the decrypting stream, but their
        // thumbnails cannot be framed). All sniff/decrypt IO is off-main.
        if (photoCache != null) {
            viewModelScope.launch(Dispatchers.IO) {
                captureHistoryStore.history.collect { items -> warmDecryption(items) }
            }
        }
    }

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    /** The search box's live query; blank matches everything. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val allItems: StateFlow<List<CaptureHistory>> = captureHistoryStore.history

    private val _decryptedPhotos = MutableStateFlow<Map<String, File>>(emptyMap())

    /**
     * History id → decrypted cache file, for photos that are encrypted at
     * rest. Absent ids load through their normal model.
     */
    val decryptedPhotos: StateFlow<Map<String, File>> = _decryptedPhotos.asStateFlow()

    private val _encryptedVideoIds = MutableStateFlow<Set<String>>(emptySet())

    /** Videos encrypted at rest: the grid/viewer show a placeholder thumbnail. */
    val encryptedVideoIds: StateFlow<Set<String>> = _encryptedVideoIds.asStateFlow()

    private suspend fun warmDecryption(items: List<CaptureHistory>) {
        val cache = photoCache ?: return
        val photos = mutableMapOf<String, File>()
        val videos = mutableSetOf<String>()
        for (item in items) {
            when (item.type) {
                CaptureType.PHOTO ->
                    cache.ensure(item.id, item.fileName, item.filePath)?.let { photos[item.id] = it }
                CaptureType.VIDEO ->
                    if (cache.sniffEncrypted(item.filePath)) videos.add(item.id)
            }
        }
        _decryptedPhotos.value = photos
        _encryptedVideoIds.value = videos
    }

    // ── Soft-delete staging ──
    // Deletes are staged (hidden from the list, media untouched) for the undo
    // window with an Undo snackbar; only the commit pass — after expiry or
    // screen exit — executes the real MediaStore delete. The verdicts are
    // [DeleteStagingPolicy]'s; this ViewModel owns the timer and the store
    // call, and mirrors the pending set into a StateFlow so the grid and the
    // viewer react to a stage/undo the moment it happens.

    /** Pure staging state: the pending set, expiry, undo, and commit verdicts. */
    private val staging = DeleteStagingPolicy(stagingWindowMs)

    private val _stagedIds = MutableStateFlow<Set<String>>(emptySet())

    /** The ids staged for deletion (hidden, not yet removed from the store). */
    val stagedIds: StateFlow<Set<String>> = _stagedIds.asStateFlow()

    private val _pendingUndoCount = MutableStateFlow(0)
    val pendingUndoCount: StateFlow<Int> = _pendingUndoCount.asStateFlow()

    /** The in-flight commit poller; one at a time, restarted per stage. */
    private var commitJob: Job? = null

    val galleryItems: StateFlow<List<CaptureHistory>> = combine(
        allItems,
        _filter,
        _searchQuery,
        _stagedIds,
    ) { history, filter, query, staged ->
        val filtered = when (filter) {
            GalleryFilter.ALL -> history
            GalleryFilter.PHOTOS -> history.filter { it.type == CaptureType.PHOTO }
            GalleryFilter.VIDEOS -> history.filter { it.type == CaptureType.VIDEO }
            GalleryFilter.FAVORITES -> history.filter { it.favorite }
        }
        // The staged (pending-undo) items stay in the store but read as gone —
        // the undo window is their last chance to reappear.
        GallerySearchPolicy.filter(filtered, query) { it.fileName }
            .filterNot { it.id in staged }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The viewer's list: everything except the staged (pending-undo) ids, so
     * a staged delete advances the pager exactly like the old immediate
     * delete did — and an undo brings the item back through the same resync.
     */
    val viewerItems: StateFlow<List<CaptureHistory>> = combine(
        allItems,
        _stagedIds,
    ) { history, staged ->
        history.filterNot { it.id in staged }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun setFilter(filter: GalleryFilter) {
        _filter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** One item's favorite flip — the store is the flag's only writer. */
    fun toggleFavorite(id: String) {
        val entry = allItems.value.find { it.id == id } ?: return
        captureHistoryStore.setFavorite(id, !entry.favorite)
    }

    fun refresh() {
        captureHistoryStore.refreshFromMediaStore()
    }

    fun setSelectMode(enabled: Boolean) {
        _selectMode.value = enabled
        if (!enabled) {
            _selectedIds.value = emptySet()
        }
    }

    fun toggleSelect(id: String) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) {
            current - id
        } else {
            current + id
        }
    }

    fun selectAll() {
        _selectedIds.value = galleryItems.value.mapTo(mutableSetOf()) { it.id }
    }

    fun selectNone() {
        _selectedIds.value = emptySet()
    }

    /** Stages one item's delete; the caller shows the Undo snackbar. */
    fun deleteItem(id: String) {
        stageAndArm(listOf(id))
    }

    /** Stages the whole selection; the caller shows the batch Undo snackbar. */
    fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        stageAndArm(ids)
        _selectedIds.value = emptySet()
    }

    /** The undo: unstages the ids — they reappear, nothing was ever deleted. */
    fun undoStaged(ids: List<String>) {
        staging.undo(ids)
        publishPendingCount()
    }

    private fun stageAndArm(ids: List<String>) {
        staging.stage(ids, System.currentTimeMillis())
        publishPendingCount()
        armCommitLoop()
    }

    private fun publishPendingCount() {
        val pending = staging.pendingIds()
        _stagedIds.value = pending.toSet()
        _pendingUndoCount.value = pending.size
    }

    /**
     * The one commit timer: polls the staging policy until nothing is pending,
     * executing each expired id exactly once through the store's batch delete.
     * Main-dispatcher confined — the policy is not thread-safe, and every
     * mutator (stage/undo) runs on the ViewModel's main thread anyway.
     */
    private fun armCommitLoop() {
        if (commitJob?.isActive == true) return
        commitJob = viewModelScope.launch {
            while (isActive) {
                val due = staging.dueForCommit(System.currentTimeMillis())
                if (due.isNotEmpty()) {
                    withContext(Dispatchers.IO) { captureHistoryStore.deleteAll(due) }
                    publishPendingCount()
                }
                if (staging.pendingIds().isEmpty()) return@launch
                delay(COMMIT_POLL_MS)
            }
        }
    }

    override fun onCleared() {
        // Screen exit force-commits whatever is still staged — the documented
        // "delete now" path; leaving the screen is not an undo. The deletes
        // are ContentResolver IO: they run on the app scope because this
        // ViewModel (and its Main confinement) end right here.
        val due = staging.pendingIds()
        staging.undoAll()
        if (due.isNotEmpty()) {
            appScope.launch {
                captureHistoryStore.deleteAll(due)
            }
        }
    }

    class Factory(
        private val captureHistoryStore: CaptureHistoryStore,
        private val photoCache: DecryptedPhotoCache? = null,
        private val appScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(captureHistoryStore, photoCache, appScope = appScope) as T
        }
    }

    companion object {
        private const val COMMIT_POLL_MS = 250L
    }
}

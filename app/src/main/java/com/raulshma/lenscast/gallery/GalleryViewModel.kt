package com.raulshma.lenscast.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class GalleryFilter {
    ALL, PHOTOS, VIDEOS, FAVORITES
}

/**
 * The selection bucket behind the grid's select mode: whether the mode is
 * [active] and which ids are checked. One atomic state so a mode flip and an
 * id edit can never tear (the old pair of flows could briefly disagree).
 *
 * Writers: [GalleryViewModel.setSelectMode], [GalleryViewModel.toggleSelect],
 * [GalleryViewModel.selectAll], [GalleryViewModel.selectNone],
 * [GalleryViewModel.deleteSelected]. Readers: the derived
 * [GalleryViewModel.selectMode]/[GalleryViewModel.selectedIds] maps, consumed
 * by GalleryScreen and on into GalleryChrome/GalleryGrid.
 */
data class SelectionState(
    val active: Boolean = false,
    val ids: Set<String> = emptySet(),
)

/**
 * One undo snackbar's snapshot of the staged set: taken when a stage armed
 * the snackbar, so the screen renders the count from it and routes Undo to
 * exactly [stagedIds] — never to the live set, which the session's commit
 * poller and later stages keep changing behind the open snackbar.
 */
data class UndoSnackbarState(
    /** The ids this snackbar's Undo unstages, in no particular order. */
    val stagedIds: Set<String>,
) {
    /** The "N moved to trash" count: this snapshot's size. */
    val count: Int get() = stagedIds.size
}

class GalleryViewModel(
    private val captureHistoryStore: CaptureHistoryStore,
    /**
     * The app-scoped gallery session: the one staging truth (pending deletes,
     * undo, commit poller) and the warm-decryption maps, shared by the
     * gallery and viewer routes' ViewModels so a stage in either is visible
     * in both the moment it happens. This ViewModel only routes calls to it
     * and derives what its screens render.
     */
    private val session: GallerySession,
) : ViewModel() {

    init {
        captureHistoryStore.refreshFromMediaStore()
    }

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    /** The search box's live query; blank matches everything. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val allItems: StateFlow<List<CaptureHistory>> = captureHistoryStore.history

    /**
     * History id → decrypted cache file, for photos that are encrypted at
     * rest. Absent ids load through their normal model. Lives on the shared
     * session; this is the passthrough both routes read.
     */
    val decryptedPhotos: StateFlow<Map<String, java.io.File>> = session.decryptedPhotos

    /** Videos encrypted at rest: the grid/viewer show a placeholder thumbnail. */
    val encryptedVideoIds: StateFlow<Set<String>> = session.encryptedVideoIds

    // ── Soft-delete staging ──
    // Deletes are staged (hidden from the list, media untouched) for the undo
    // window with an Undo snackbar; only the commit pass — after expiry or
    // leaving the gallery area — executes the real MediaStore delete. The
    // verdicts are [DeleteStagingPolicy]'s, and the policy, the timer, and
    // the pending-set publication live on the shared [session]; the derived
    // lists below keep combining the shared staged set so a stage/undo from
    // either route re-filters both.

    /** The ids staged for deletion (hidden, not yet removed from the store). */
    val stagedIds: StateFlow<Set<String>> = session.stagedIds

    val galleryItems: StateFlow<List<CaptureHistory>> = combine(
        allItems,
        _filter,
        _searchQuery,
        session.stagedIds,
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
        session.stagedIds,
    ) { history, staged ->
        history.filterNot { it.id in staged }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The selection bucket ([SelectionState]); the public single-purpose
     * flows below are derived maps so the screens keep reading two flows.
     */
    private val selection = MutableStateFlow(SelectionState())

    /** Whether select mode is on (the top bar / bottom bar swap). */
    val selectMode: StateFlow<Boolean> = selection
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The checked ids while select mode is on. */
    val selectedIds: StateFlow<Set<String>> = selection
        .map { it.ids }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * The undo-snackbar render state: each distinct staged set arms one
     * [UndoSnackbarState] snapshot (an equal set — e.g. a re-stage refreshing
     * an existing window — arms nothing new). Eagerly, not WhileSubscribed:
     * the session's commit poller keeps changing the staged set in the
     * background, and the value must be current whenever the screen (re)reads
     * it after being away longer than the subscription grace window.
     */
    val undoSnackbar: StateFlow<UndoSnackbarState?> = session.stagedIds
        .map { ids -> if (ids.isEmpty()) null else UndoSnackbarState(ids) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    /** Exiting select mode clears the ids; entering leaves them untouched. */
    fun setSelectMode(enabled: Boolean) {
        selection.update { current ->
            if (enabled) current.copy(active = true) else SelectionState(active = false)
        }
    }

    fun toggleSelect(id: String) {
        selection.update { current ->
            current.copy(ids = if (id in current.ids) current.ids - id else current.ids + id)
        }
    }

    /** Snapshots the current filtered list into the selection. */
    fun selectAll() {
        selection.update { current ->
            current.copy(ids = galleryItems.value.mapTo(mutableSetOf()) { it.id })
        }
    }

    /** Keeps select mode on with an empty selection (the known behavior). */
    fun selectNone() {
        selection.update { it.copy(ids = emptySet()) }
    }

    /** Stages one item's delete; the caller shows the Undo snackbar. */
    fun deleteItem(id: String) {
        session.stage(listOf(id))
    }

    /** Stages the whole selection; the caller shows the batch Undo snackbar. */
    fun deleteSelected() {
        val ids = selection.value.ids.toList()
        if (ids.isEmpty()) return
        session.stage(ids)
        // The selection clears but select mode stays on (the known behavior).
        selection.update { it.copy(ids = emptySet()) }
    }

    /** The undo: unstages exactly the given ids — they reappear, nothing was ever deleted. */
    fun undoStaged(ids: List<String>) {
        session.undoStaged(ids)
    }

    override fun onCleared() {
        // Screen exit force-commits whatever is still staged — the documented
        // "delete now" path; leaving the gallery area is not an undo. The
        // app-scoped session executes it, so the ContentResolver IO outlives
        // this ViewModel.
        //
        // Why this lives in onCleared rather than a DisposableEffect's
        // onDispose: Navigation Compose keeps only the top destination
        // composed — pushing the viewer disposes the gallery screen's
        // composition (the entry stays in the back stack; this ViewModel does
        // NOT clear), so an onDispose flush would commit items the user just
        // staged and can still unstage from the grid. onCleared fires exactly
        // when the gallery entry is destroyed — popped to the camera, or the
        // activity finishing — the same trigger the old per-ViewModel
        // force-commit had. Symmetrically, the viewer's ViewModel never
        // flushes on its clear (pop back to the grid): its staged ids stay
        // pending in the shared session with a live undo affordance in the
        // grid, and the session's expiry poller commits them when their
        // window lapses.
        session.flush()
    }

    class Factory(
        private val captureHistoryStore: CaptureHistoryStore,
        /** The app-scoped session both gallery-domain routes share. */
        private val session: GallerySession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(captureHistoryStore, session) as T
        }
    }
}

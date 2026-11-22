package com.raulshma.lenscast.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** The page walk's stall cadence (the init's empty-grid walker and the load-more stall watch). */
private const val PAGE_WALK_RETRY_MS = 2_000L

/**
 * The gallery's state owner — pure UI state (filter, search, selection,
 * derived lists) over the [GallerySource] seam, one interface over the
 * local store/session backend and the paired device's remote library. The
 * source owns everything that differs between backends (the library list,
 * paging, deletion semantics and their gates, the media render slots); this
 * ViewModel owns what both share: the filter/search/selection machinery and
 * the derived [galleryItems]/[viewerItems]/[undoSnackbar] projections, which
 * keep combining [GallerySource.stagedIds] so a stage/undo from either route
 * re-filters both. Deletion routing is verdict-driven, not backend-driven:
 * [deleteItem]/[deleteSelected] hand ids to the source (local: staged into
 * the shared session; remote: wire delete), and [undoSnackbar] arms only
 * from the staged set — which the remote backend keeps empty, so the undo
 * snackbar never shows there.
 */
class GalleryViewModel(
    private val source: GallerySource,
    /**
     * Whether clearing this ViewModel commits the shared session's staged
     * deletes (the grid route's commit-on-exit). Only the VIEWER route's
     * instance passes false: its pop returns to the still-alive grid, whose
     * undo affordance must survive — see the onCleared comment.
     */
    private val flushOnCleared: Boolean = true,
) : ViewModel() {

    // Deliberately NO init refresh here: the grid route triggers it (once per
    // ViewModel, via [refreshOnEntry] from GalleryScreen), while the viewer
    // route must page the list as-is — a reset-on-open would wipe the
    // accumulated remote pages under the opening pager and strand its initial
    // index on a deep item.

    /** The library, newest-first — the source's list, one truth for both routes. */
    val allItems: StateFlow<List<CaptureHistory>> = source.items

    /** Whether another page exists behind [allItems] (remote paging; false locally). */
    val hasMore: StateFlow<Boolean> = source.hasMore

    /** The quota bar, or null when the backend has no quota story (row hidden). */
    val storageBar: StateFlow<com.raulshma.lenscast.data.CaptureHistoryStore.StorageBar?> = source.storageBar

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    /** The search box's live query; blank matches everything. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** The ids staged for deletion (hidden, not yet removed). Always empty on the remote backend. */
    val stagedIds: StateFlow<Set<String>> = source.stagedIds

    val galleryItems: StateFlow<List<CaptureHistory>> = combine(
        allItems,
        _filter,
        _searchQuery,
        source.stagedIds,
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
        source.stagedIds,
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
     * it after being away longer than the subscription grace window. Null on
     * the remote backend by construction (nothing stages).
     */
    val undoSnackbar: StateFlow<UndoSnackbarState?> = source.stagedIds
        .map { ids -> if (ids.isEmpty()) null else UndoSnackbarState(ids) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // The selection stays honest against the library, through two
        // authoritative verdicts:
        //
        // - CONFIRMED DELETES — the source's [GallerySource.confirmedDeletedIds]:
        //   the backend dropped the id because the delete round trip succeeded
        //   (the remote wire delete's verdict). Always prunes, paging state
        //   aside — a delete the device did NOT confirm publishes nothing, so
        //   its submitted ids stay selected and the delete is retryable.
        // - ABSENCE — meaningful only when the loaded list is the WHOLE
        //   library ([GallerySource.prunesSelectionOnAbsence]): locally
        //   always — hasMore pins false and the store IS the library — so a
        //   selected id missing from a complete list is gone. Remotely the
        //   flag is false: the list is a filter-scoped paging window whose
        //   hasMore=false verdict describes only that filter (a complete
        //   PHOTO window says nothing about a selected VIDEO), and the two
        //   flows update in separate emissions — a torn combine could prune
        //   against a stale window. The wire publishes nothing but confirmed
        //   deletions, so confirmed evidence is complete there; a refresh()'s
        //   page-0 replacement never discards a selection made on deeper
        //   pages, which absence-only pruning used to.
        // The local backend is otherwise unaffected: [deleteSelected] clears
        // its selection the moment staging succeeds, and staged items stay
        // listed until commit.
        viewModelScope.launch {
            combine(allItems, hasMore, source.confirmedDeletedIds) { items, more, confirmed ->
                Triple(items, more, confirmed)
            }.collect { (items, more, confirmed) ->
                val absencePrunes = source.prunesSelectionOnAbsence
                selection.update { current ->
                    if (current.ids.isEmpty()) {
                        current
                    } else {
                        // The presence index is only built where absence can
                        // prune at all ([GallerySource.prunesSelectionOnAbsence])
                        // and only when there is a selection to prune — the
                        // common no-selection pass must not index every
                        // emission's whole list. Where absence cannot prune,
                        // the verdict reads the confirmed set directly.
                        val present = if (absencePrunes) items.mapTo(mutableSetOf()) { it.id } else emptySet()
                        current.copy(
                            ids = current.ids.filterTo(mutableSetOf()) { id ->
                                id in present || (id !in confirmed && (!absencePrunes || more))
                            },
                        )
                    }
                }
            }
        }

        // The empty grid can still page forward: FAVORITES and search narrow
        // the loaded pages client-side (the wire has no filter for them), and
        // a failed page-0 or filter-switch fetch parks the remote pager with
        // an empty list and hasMore true. The grid's scroll-end trigger only
        // exists while tiles render, so this collector keeps requesting the
        // next page while the filtered view is empty and the source reports
        // more — search and Favorites can reach matches beyond page 0, and a
        // stranded empty gallery keeps walking until page 0 retries. The
        // library list is an input (not just the filtered projection) because
        // a page of client-side non-matches leaves the projection unchanged:
        // the growing library is what re-fires the walk. It ends at
        // hasMore=false or once a match renders and the grid's scroll-end
        // trigger takes over; the source re-entrancy-guards, so a call against
        // an in-flight fetch costs nothing. Locally inert (hasMore is false).
        //
        // collectLatest + the [PAGE_WALK_RETRY_MS] cadence close the two gaps
        // a one-shot collect leaves open. First, the in-flight guard race: a
        // page can publish (re-firing this walk) BEFORE the source's fetch job
        // clears its guard, so the re-fired loadMore() is dropped and — no
        // further emission coming — the walk would stall with page 1 never
        // requested. Second, the failed fetch: a parked failure changes no
        // state, so no emission ever re-fires the walk; the cadence keeps
        // retrying through the source's retryable-parked state until
        // connectivity recovers — no filter or query change required. Every
        // state change restarts this block with an immediate request, the
        // delay is only the stall cadence, and a walk=false emission (a match
        // rendered, or hasMore went false) cancels the loop.
        viewModelScope.launch {
            combine(allItems, hasMore, _filter, _searchQuery, galleryItems) { _, more, _, _, filtered ->
                more && filtered.isEmpty()
            }.collectLatest { walk ->
                if (!walk) return@collectLatest
                while (true) {
                    source.loadMore()
                    delay(PAGE_WALK_RETRY_MS)
                }
            }
        }
    }

    fun setFilter(filter: GalleryFilter) {
        _filter.value = filter
        // The PHOTO/VIDEO chips ride the source's paging filter (a no-op
        // locally, a wire `type` restart remotely); FAVORITES has no wire
        // filter and keeps narrowing client-side over the loaded pages.
        source.setMediaTypeFilter(
            when (filter) {
                GalleryFilter.PHOTOS -> CaptureType.PHOTO
                GalleryFilter.VIDEOS -> CaptureType.VIDEO
                GalleryFilter.ALL, GalleryFilter.FAVORITES -> null
            },
        )
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** One item's favorite flip — the source's gate decides where that's possible. */
    fun toggleFavorite(id: String) {
        source.toggleFavorite(id)
    }

    fun refresh() {
        source.refresh()
    }

    /**
     * The grid route's one-shot entry refresh; the guard lives HERE, next to
     * the library it refreshed — not in the screen's saveable state. A
     * saveable flag outlives the ViewModel it guarded (a process-death restore
     * re-creates the ViewModel with an empty library but replays
     * `refreshed = true`, stranding the grid without its refresh), while a
     * plain remember resets on rotation. Owned here, one flag covers every
     * re-entry shape: a NEW ViewModel refreshes once; a rotation and a viewer
     * push/pop re-compose the screen against the SAME instance and skip.
     *
     * This is also the re-entry consistency point for the CACHED remote
     * source (CompanionGraph keeps one instance per device): that instance
     * still carries the previous grid's wire `type` filter — and possibly its
     * in-flight page fetch — while this ViewModel starts at ALL. Re-anchoring
     * the filter before the refresh resets paging to an unfiltered page 0
     * (cancelling the stale in-flight fetch with it), so the new grid cannot
     * show All while loading only the prior filter's matches. Locally both
     * calls are no-ops.
     */
    private var refreshedOnEntry = false

    fun refreshOnEntry() {
        if (refreshedOnEntry) return
        refreshedOnEntry = true
        setFilter(GalleryFilter.ALL)
        refresh()
    }

    /** The next page, when [hasMore] allows (the grid's scroll-end trigger). */
    fun loadMore() {
        source.loadMore()
        armLoadMoreStallWatch()
    }

    /**
     * The near-end load-more's stall watchdog. A FAILED page fetch with tiles
     * already on screen changes no flow state (the source parks the failure by
     * re-pinning hasMore, which never re-emits) — so unlike the empty-grid
     * walk in the init, nothing re-fires the request and the gallery sits
     * stalled at its end until the user happens to scroll again. Armed on
     * every funnel call above, it re-issues [GallerySource.loadMore] on the
     * [PAGE_WALK_RETRY_MS] cadence while the library has not grown and
     * [hasMore] still holds — connectivity returning (or the device answering)
     * lands the page without any scroll, layout, or filter event. A landing
     * page grows the list and disarms the watch, handing paging back to the
     * grid's scroll-end trigger, so successful paging behavior is untouched;
     * the source's re-entrancy guard absorbs the overlapping calls. Locally
     * inert (hasMore pins false; the loop never starts).
     */
    private var loadMoreStallWatch: Job? = null

    private fun armLoadMoreStallWatch() {
        loadMoreStallWatch?.cancel()
        val sizeBefore = allItems.value.size
        loadMoreStallWatch = viewModelScope.launch {
            while (hasMore.value) {
                delay(PAGE_WALK_RETRY_MS)
                // A page landed (or the window closed) since the request — the
                // scroll-driven flow is healthy again; nothing to retry.
                if (allItems.value.size != sizeBefore || !hasMore.value) return@launch
                source.loadMore()
            }
        }
    }

    /**
     * The viewer route's deep-id walk. A viewer opened onto a routed media id
     * has NO grid composing beneath it to drive [refreshOnEntry] or the
     * scroll-end trigger, and on a process-death restore its fresh source
     * holds only page 0 — a routed id sitting on a later page would never be
     * fetched, leaving the pager stranded on page 0's first tile with the
     * viewer showing the wrong media. This walk pages forward (loadMore
     * only — never a reset, which would wipe the accumulated pages and move
     * the pager's index space) while the routed id is not in the loaded list
     * and [hasMore] holds, on the same [PAGE_WALK_RETRY_MS] cadence and
     * re-entrancy guards as the init's walks. It ends once the id is found
     * (the route's resync lands the pager on it), hasMore goes false (the
     * stale-arg fallback: the pager stays where initialIndexFor put it), or
     * the route leaves. Locally inert (hasMore pins false; the list is the
     * whole library, so the id resolves on the first emission).
     */
    private var deepIdWalk: Job? = null

    fun pageUntilFound(mediaId: String) {
        deepIdWalk?.cancel()
        deepIdWalk = viewModelScope.launch {
            combine(allItems, hasMore) { items, more ->
                more && items.none { it.id == mediaId }
            }.collectLatest { walk ->
                if (!walk) return@collectLatest
                while (true) {
                    source.loadMore()
                    delay(PAGE_WALK_RETRY_MS)
                }
            }
        }
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

    /** Deletes one item through the source (staged locally, wire-deleted remotely). */
    fun deleteItem(id: String) {
        source.delete(listOf(id))
    }

    /**
     * Deletes the whole selection through the source; the caller routed the
     * confirm dialog. Local staging is synchronous — the ids read as gone the
     * moment the session stages them, so the selection clears immediately and
     * select mode stays on (the known behavior). The remote wire delete
     * confirms asynchronously: the submitted ids stay selected until the
     * source's list drops them (the device's confirmation — the init watcher),
     * so a failed call retains the ids and the delete is retryable.
     */
    fun deleteSelected() {
        val ids = selection.value.ids.toList()
        if (ids.isEmpty()) return
        source.delete(ids)
        if (source.deleteIsUndoable) {
            // The selection clears but select mode stays on (the known behavior).
            selection.update { it.copy(ids = emptySet()) }
        }
    }

    /** The undo: unstages exactly the given ids — they reappear, nothing was ever deleted. */
    fun undoStaged(ids: List<String>) {
        source.undoStaged(ids)
    }

    /** The storage row's free-space action (local quota enforcement; no-op where the row is hidden). */
    fun enforceQuota() {
        source.enforceQuota()
    }

    override fun onCleared() {
        // The viewer's instance (flushOnCleared = false) never flushes: its
        // clear is the pop back to the STILL-ALIVE grid, and both routes share
        // one source/session — a flush here would commit the staged ids the
        // grid's undo snackbar is still offering, killing the undo window the
        // commit-on-exit contract deliberately keeps open. When the whole
        // gallery area exits, the grid's own instance clears and flushes below.
        if (!flushOnCleared) return
        // Screen exit force-commits whatever is still staged — the documented
        // "delete now" path; leaving the gallery area is not an undo. The
        // source executes it (locally through the app-scoped session, so the
        // ContentResolver IO outlives this ViewModel; remotely a no-op).
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
        source.flush()
    }

    class Factory(
        /** The backend for this route's device argument (local when absent). */
        private val source: GallerySource,
        /** Through to [GalleryViewModel.flushOnCleared] — only the viewer route passes false. */
        private val flushOnCleared: Boolean = true,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(source, flushOnCleared) as T
        }
    }
}

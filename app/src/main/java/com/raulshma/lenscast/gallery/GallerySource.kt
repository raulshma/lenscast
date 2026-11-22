package com.raulshma.lenscast.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.flow.StateFlow

/**
 * The gallery's one seam — the exact surface [GalleryViewModel],
 * GalleryScreen, GalleryMediaGrid, and MediaViewerScreen consume, as a plain
 * interface. The app is "one interface, two backends" (the
 * [com.raulshma.lenscast.camera.CameraConsole] pattern): the gallery renders
 * against either
 *
 * - the **local** backend — [LocalGallerySource], today's graph singletons
 *   (`CaptureHistoryStore` + the app-scoped `GallerySession`) unchanged; or
 * - the **remote** backend — [com.raulshma.lenscast.companion.console.RemoteGallerySource],
 *   paging the paired device's `/api/gallery` over its `LenscastApiClient`.
 *
 * The UI binds to this interface and reads the boolean verdicts below to
 * hide what the current backend can't honor — the source owns the
 * divergence, so no screen carries an `if (remote)` branch.
 *
 * Mutation gates (the [com.raulshma.lenscast.streaming.web.RoleGatePolicy]
 * spirit applied to this screen's writes):
 *
 * - [canDelete] — media deletion is a device-state write: an admin pairing
 *   may delete remotely, a viewer-role pairing reads only (the device would
 *   403 the call; the flag hides the affordance instead). Local is always
 *   true.
 * - [canFavorite] — the favorite star is a write into the owning store. The
 *   local store has one (`CaptureHistoryStore.setFavorite`); the wire
 *   carries `favorite` on the gallery response READ-ONLY — no toggle
 *   endpoint exists — so the remote backend pins this false and the star
 *   toggles hide (a remote item starred on the device itself still shows its
 *   badge, as an indicator).
 * - [canShareExternally] — the share / open-externally affordances hand a
 *   local file URI to other apps. Remote items have no local bytes; the
 *   remote backend pins this false and hides them.
 * - [deleteIsUndoable] — the local backend deletes through the soft-delete
 *   staging window (an Undo snackbar accompanies every stage); the remote
 *   DELETE is permanent the moment the device confirms, so the confirm
 *   dialogs render the permanent copy and the undo snackbar never arms.
 *
 * Deletion semantics are the source's, wholly: the local impl stages into
 * the shared session (list hides immediately, undo restores, the commit
 * pass executes the MediaStore delete); the remote impl round-trips the
 * wire call and only then removes the items. [stagedIds] is the one truth
 * both screens' derived lists already filter on — empty on the remote
 * backend, which is what keeps the undo surface inert there.
 *
 * The three `@Composable` media slots own the render divergence: local
 * media loads through Coil over this phone's files/URIs (encrypted-at-rest
 * handling included); remote media renders through the authed bitmap loader
 * and the authed video player over the device's media routes. The screens
 * frame and overlay them; they never decode media themselves.
 */
interface GallerySource {

    // ── Reads ──

    /**
     * The loaded library, newest-first — the store's order locally, the
     * accumulated `/api/gallery` pages remotely (page order preserved, which
     * the server emits newest-first; a page replay is deduped by id).
     */
    val items: StateFlow<List<CaptureHistory>>

    /**
     * Whether another page exists behind [items]. Always false locally (the
     * store loads the whole library); the remote backend flips it off once
     * the server answers `hasMore=false`.
     */
    val hasMore: StateFlow<Boolean>

    /**
     * The ids the backend has CONFIRMED deleted this session — dropped from
     * [items] for that reason (the remote wire delete's verdict; locally
     * pinned empty, where the list is the whole library and a synchronous
     * stage already clears the selection). The selection-prune signal that
     * does not infer anything from [items]' absence: remotely the list is a
     * paging window, and a refresh's page-0 replacement would otherwise read
     * as mass deletion.
     */
    val confirmedDeletedIds: StateFlow<Set<String>>

    /**
     * Whether absence from [items] counts as deletion evidence for the
     * gallery's selection pruning once [hasMore] is false. True for
     * whole-library backends (the local store IS the whole library — a
     * selected id missing from a complete list is gone). False for windowed
     * backends (the remote list is a filter-scoped paging window: a complete
     * PHOTO window says nothing about a selected VIDEO, and a torn
     * hasMore/items pair could prune against a stale window) — those prune
     * on [confirmedDeletedIds] alone, which is complete there: the wire
     * publishes nothing but confirmed deletions.
     */
    val prunesSelectionOnAbsence: Boolean get() = true

    /** The ids staged for deletion (hidden from the lists, not yet gone). Empty on the remote backend. */
    val stagedIds: StateFlow<Set<String>>

    /**
     * The quota bar model, or null when the backend has no quota story — the
     * quota is THIS phone's storage setting; the remote device's storage is
     * a system-surface concern, not the gallery's, so the remote backend
     * holds null and the row stays hidden.
     */
    val storageBar: StateFlow<CaptureHistoryStore.StorageBar?>

    // ── Mutation gates (see the KDoc's verdict table) ──

    val canDelete: Boolean
    val canFavorite: Boolean
    val canShareExternally: Boolean
    val deleteIsUndoable: Boolean

    // ── Operations ──

    /**
     * Re-reads the library: the MediaStore reconciliation locally, a paging
     * restart at page 0 remotely.
     */
    fun refresh()

    /** Fetches the next page when the grid nears its end. No-op locally ([hasMore] is false). */
    fun loadMore()

    /**
     * Narrows paging to one capture type (null = all). The local backend is
     * a no-op — it holds the whole library, and the ViewModel's filter chips
     * already narrow client-side; the remote backend restarts paging with
     * the wire `type` param so a filtered grid can actually reach every
     * match. The ViewModel maps FAVORITES to null: the wire has no favorite
     * filter, so that chip keeps filtering the loaded items client-side.
     */
    fun setMediaTypeFilter(type: CaptureType?)

    /** One item's favorite flip. Caller-gated by [canFavorite]; a no-op where the gate is false. */
    fun toggleFavorite(id: String)

    /**
     * Deletes [ids] — stage-into-session locally (undoable while staged),
     * wire DELETE / batch-delete remotely (list drops them once the device
     * confirms; a failed call leaves the list untouched). Caller-gated by
     * [canDelete].
     */
    fun delete(ids: List<String>)

    /** The undo: unstages the ids (they reappear, nothing was deleted). Empty-set remote: a no-op. */
    fun undoStaged(ids: List<String>)

    /** The storage row's free-space action; only meaningful while [storageBar] is non-null. */
    fun enforceQuota()

    /**
     * Commit-on-exit ("leaving the gallery area is not an undo") — the
     * gallery ViewModel's `onCleared` calls this. Executes staged deletes
     * locally; a no-op remotely (nothing is ever staged).
     */
    fun flush()

    // ── Media render slots ──
    // The backend's media decoders, as composables the screens frame. Each
    // owns its placeholder/unavailable state; the caller supplies the frame
    // modifier (size, shared-element, zoom transform).

    /** A grid tile's media (thumbnail decode; the placeholder shows until it lands or forever on failure). */
    @Composable
    fun thumbnail(item: CaptureHistory, modifier: Modifier)

    /** The viewer's full-size photo. */
    @Composable
    fun photo(item: CaptureHistory, modifier: Modifier)

    /** The viewer's video playback surface, bound and playable on composition. */
    @Composable
    fun video(item: CaptureHistory, modifier: Modifier)
}

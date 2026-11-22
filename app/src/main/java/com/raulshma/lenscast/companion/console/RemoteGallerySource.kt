package com.raulshma.lenscast.companion.console

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.GalleryItem
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.gallery.GallerySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The gallery's REMOTE backend: [GallerySource] over a paired device's
 * `/api/gallery` surface — the companion media tab's proven data path
 * (paging accumulate + dedupe, wire delete / batch-delete) re-homed into the
 * main gallery's seam. Built by [com.raulshma.lenscast.companion.di.CompanionGraph.gallerySource],
 * which caches one instance per device so the gallery grid and the viewer
 * route (two ViewModels over the same seam) read one item list and one
 * pager index space.
 *
 * Lifecycle/visibility contract: the source rides PLAIN REST reads — it
 * never starts or stops the device session (the console screen owns that
 * lifecycle per its setActive/dispose contract), and a deep-linked gallery
 * works with no live link at all: the client dials from the roster snapshot
 * + live credentials exactly like the JSON lane always has. When a session
 * IS live beside it (console in the stack), both share the same engine and
 * trust decision. A device that left the roster builds a settled empty
 * source (no client, no deletes).
 *
 * Deletions are permanent the moment the device answers 2xx — the item
 * drops from the list then, and nothing was ever staged, so [stagedIds]
 * stays empty and the undo surface stays inert ([deleteIsUndoable] = false;
 * the confirm dialogs render the permanent copy). A failed delete leaves
 * the list untouched (the next refresh shows the device's truth) — the
 * local gallery's surface has no error snackbar, and this backend adds no
 * UI of its own.
 *
 * Role gate: [canDelete] is the roster role's verdict (a viewer pairing
 * would 403 the write; the flag hides the affordance instead — the
 * [com.raulshma.lenscast.streaming.web.RoleGatePolicy] shape). [canFavorite]
 * is pinned false unconditionally: the wire carries `favorite` READ-ONLY on
 * the gallery response and defines no toggle endpoint, so the remote star
 * is an indicator only (a device-side star shows; the app cannot flip it).
 * [canShareExternally] is false: remote items have no local bytes to hand
 * other apps.
 *
 * Favorites and search stay CLIENT-side over the loaded pages (the wire has
 * no favorite filter and the ViewModel already narrows by name); the
 * PHOTO/VIDEO chips ride [setMediaTypeFilter] into the wire `type` param so
 * a filtered grid can reach every match, not just the loaded pages'.
 */
class RemoteGallerySource(
    /**
     * The device's JSON client; null when the roster lost the device — a
     * settled no-dial state (empty library, every mutation a no-op).
     */
    private val api: LenscastApiClient?,
    /** The roster role's deletion verdict (admin pairings only). */
    override val canDelete: Boolean,
    /** The scope paging and delete round trips run on (the graph's app scope). */
    private val scope: CoroutineScope,
) : GallerySource {

    override val canFavorite: Boolean = false // the wire defines no favorite-toggle endpoint
    override val canShareExternally: Boolean = false // no local bytes to hand other apps
    override val deleteIsUndoable: Boolean = false // the DELETE confirms permanently
    // Absence from the paging window proves nothing (a filter-scoped
    // window's completeness says nothing about selections outside it, and a
    // torn hasMore/items pair could prune against a stale window) — the
    // selection prunes on confirmed deletions alone, which the wire verdict
    // makes complete.
    override val prunesSelectionOnAbsence: Boolean = false

    private val _items = MutableStateFlow<List<CaptureHistory>>(emptyList())
    override val items: StateFlow<List<CaptureHistory>> = _items.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    override val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /**
     * The ids the device confirmed deleted (the wire delete's 2xx) — the
     * gallery selection's prune signal. The list's absence is NOT the
     * deletion verdict here: [items] is a paging window, and a refresh's
     * page-0 replacement drops deeper pages without anything being deleted.
     */
    private val _confirmedDeletedIds = MutableStateFlow<Set<String>>(emptySet())
    override val confirmedDeletedIds: StateFlow<Set<String>> = _confirmedDeletedIds.asStateFlow()

    override val stagedIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    // The quota bar is THIS phone's storage story; the device's storage is a
    // system-surface concern. Null keeps the gallery's storage row hidden.
    override val storageBar: StateFlow<CaptureHistoryStore.StorageBar?> = MutableStateFlow(null)

    private val mediaTypeFilter = MutableStateFlow<CaptureType?>(null)

    /** Pages already folded into [items]; the `page` param's next value (0-based wire index). */
    private var loadedPages = 0

    private var fetchJob: Job? = null

    /** Bumped on every [refresh]; the media slots' failed-load retry signal. */
    private val refreshTick = MutableStateFlow(0)

    init {
        refresh()
    }

    override fun refresh() {
        refreshTick.value += 1
        fetchPage(reset = true)
    }

    override fun loadMore() {
        if (!_hasMore.value || fetchJob?.isActive == true) return
        fetchPage(reset = false)
    }

    override fun setMediaTypeFilter(type: CaptureType?) {
        if (mediaTypeFilter.value == type) return
        mediaTypeFilter.value = type
        fetchPage(reset = true)
    }

    private fun fetchPage(reset: Boolean) {
        val client = api ?: return
        fetchJob?.cancel()
        // The wire page index is 0-based (the media tab's correction of the
        // web client's slip: sending 1 as the FIRST page asked for the
        // second and rendered "no captures" under two captures).
        val page = if (reset) 0 else loadedPages
        fetchJob = scope.launch {
            try {
                val response = client.getGallery(
                    page = page,
                    pageSize = PAGE_SIZE,
                    type = mediaTypeFilter.value?.name,
                )
                loadedPages = page + 1
                val mapped = response.items.map { it.toCaptureHistory(client.mediaUrl(it.id)) }
                // The pair publishes hasMore first; the ordering is inert for
                // the selection — this backend prunes on confirmed deletions
                // alone (prunesSelectionOnAbsence = false), so no torn
                // hasMore/items window can prune a valid selection.
                _hasMore.value = response.hasMore
                _items.update { current ->
                    // A page-0 answer replaces the pages (a reset, or the
                    // load-more retry of a failed reset); a next page appends,
                    // deduped by id — a server replay or an overlapping page
                    // must never duplicate tiles (the media tab's guard,
                    // verbatim).
                    if (reset || page == 0) {
                        mapped
                    } else {
                        val seen = current.mapTo(mutableSetOf()) { it.id }
                        current + mapped.filter { seen.add(it.id) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (reset) {
                    // A failed page 0 (first load or a filter switch) must not
                    // keep the previous filter's cursor or paging state: park
                    // both so the next loadMore() retries page 0 for the new
                    // filter instead of a stale page index.
                    loadedPages = 0
                    _hasMore.value = true
                } else {
                    _hasMore.value = true // a failed load-more is retryable
                }
            }
        }
    }

    override fun toggleFavorite(id: String) = Unit // gated off at the source: no wire endpoint

    override fun delete(ids: List<String>) {
        val client = api ?: return
        if (ids.isEmpty()) return
        // The role gate: a viewer pairing's affordance is hidden, and this
        // return is the belt — the device's own role check stays the real
        // authority (a stale flag cannot turn into a 403-driven silent drop).
        if (!canDelete) return
        scope.launch {
            // The confirmed ids — the single delete is all-or-error (a 2xx
            // names the one id), while the batch is PARTIAL-capable: the
            // device skips missing ids and entries whose media it failed to
            // remove, and the response names what actually went. Only the
            // confirmed ids leave the list.
            val confirmedIds: List<String> = try {
                if (ids.size == 1) {
                    client.deleteMedia(ids.single())
                    listOf(ids.single())
                } else {
                    client.batchDeleteMedia(ids).deleted
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // Drop only once the device confirmed — a failed call leaves the
            // list as-is so the next refresh reconciles it. The confirmed ids
            // publish BEFORE the list drops them: the selection prunes on the
            // verdict itself, not on absence (a paging window's absence means
            // nothing).
            if (confirmedIds.isNotEmpty()) {
                val removed = confirmedIds.toSet()
                _confirmedDeletedIds.update { it + removed }
                _items.update { list -> list.filterNot { it.id in removed } }
                // Invalidate any fetch started before the deletion (its page
                // was read while the items still existed and would restore
                // the deleted tiles over this drop), then reload the window
                // from page 0: the device's ordering shifted left, so the
                // old loadedPages cursor would skip an item across the page
                // boundary on the next loadMore. The fresh response publishes
                // through the ordinary replacement/dedupe path.
                fetchPage(reset = true)
            }
        }
    }

    override fun undoStaged(ids: List<String>) = Unit // nothing is ever staged remotely

    override fun enforceQuota() = Unit // the quota row is hidden (storageBar stays null)

    override fun flush() = Unit // nothing is ever staged; exit commits nothing

    // ── Media render slots ──
    // The authed fetch paths the media tab proved: bitmap decode through the
    // session's shared engine + live auth headers (the token/basic/cookie
    // ladder — evaluated per fetch, so a credential refresh applies to the
    // next thumbnail/video request like it does to the JSON lane), video
    // through the long-lived OkHttp datasource player. One loader per source
    // (not per composition — the tab's compose-scoped idiom predates cached
    // sources): the fetch semaphore still bounds in-flight bytes against the
    // device's single-threaded NanoHTTPD lane.

    private val loader = api?.let { client ->
        AuthedBitmapLoader(client.sharedEngine()) { client.sharedAuthHeaders() }
    }
    private val playbackEngine = api?.sharedEngine()

    @Composable
    override fun thumbnail(item: CaptureHistory, modifier: Modifier) {
        AuthedMediaImage(
            // The dedicated downscaled-thumbnail route: tile-sized bytes off
            // the device instead of full-size originals (the tab decoded the
            // full route down at decode time; the server's /thumbnail serves
            // it pre-downscaled).
            url = api?.mediaThumbnailUrl(item.id),
            loader = loader,
            isVideo = item.type == CaptureType.VIDEO,
            contentDescription = item.fileName,
            modifier = modifier,
            thumbnail = true,
            refresh = refreshTick.collectAsState().value,
        )
    }

    @Composable
    override fun photo(item: CaptureHistory, modifier: Modifier) {
        AuthedMediaImage(
            // [CaptureHistory.filePath] carries the full-size media URL for
            // remote items — see the mapper.
            url = item.filePath.takeIf { it.isNotEmpty() },
            loader = loader,
            isVideo = false,
            contentDescription = null,
            modifier = modifier,
            thumbnail = false,
            contentScale = ContentScale.Fit,
            refresh = refreshTick.collectAsState().value,
        )
    }

    @Composable
    override fun video(item: CaptureHistory, modifier: Modifier) {
        val engine = playbackEngine
        val url = item.filePath.takeIf { it.isNotEmpty() }
        if (engine == null || url == null) {
            // Roster-lost settled state: nothing to dial, nothing to play.
            com.raulshma.lenscast.gallery.ViewerUnavailable(
                icon = Icons.Default.Videocam,
                label = stringResource(R.string.gallery_video_unavailable),
            )
        } else {
            RemoteVideoPlayer(
                url = url,
                engine = engine,
                // Evaluated per composition (the sharedAuthHeaders ladder):
                // a credential refresh lands the next time the slot builds
                // its player.
                authHeaders = api?.sharedAuthHeaders().orEmpty(),
                modifier = modifier,
            )
        }
    }

    companion object {
        /** The gallery page size (the web console's 30-per-page grid). */
        const val PAGE_SIZE = 30
    }
}

/**
 * The pure wire→domain projection: one device gallery item onto the local
 * gallery model, so the screens keep consuming [CaptureHistory] against
 * both backends. JVM-pure (both ends are Moshi data classes — no MappedLens
 * projection/assembly split needed). [mediaUrl] is the caller-resolved
 * absolute full-size route (the client's URL builder owns IPv6 bracketing
 * and scheme; the wire's `url`/`thumbnailUrl` fields are server-relative
 * paths) and lands in [CaptureHistory.filePath], the slot seam's one media
 * address. An unknown `type` reads as a photo — the wire vocabulary is
 * exactly `PHOTO|VIDEO` (the server's GalleryPage filter), so anything else
 * is forward-compat noise, and a photo renders a still where a guessed
 * video would render a broken player.
 */
internal fun GalleryItem.toCaptureHistory(mediaUrl: String): CaptureHistory = CaptureHistory(
    id = id,
    type = CaptureType.entries.firstOrNull { it.name == type } ?: CaptureType.PHOTO,
    fileName = fileName,
    filePath = mediaUrl,
    timestamp = timestamp,
    fileSizeBytes = fileSizeBytes,
    durationMs = durationMs,
    trigger = null, // provenance never crossed the wire; the timeline reconstructs it from events
    favorite = favorite, // read-only here: this backend cannot flip it (no wire endpoint)
)

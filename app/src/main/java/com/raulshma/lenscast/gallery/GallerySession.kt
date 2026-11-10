package com.raulshma.lenscast.gallery

import com.raulshma.lenscast.capture.DecryptedPhotoCache
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app-scoped owner of the gallery's soft-delete staging and warm
 * decryption — the state the gallery and viewer routes used to keep in two
 * separate NavBackStackEntry-scoped [GalleryViewModel]s.
 *
 * What it owns:
 * - the [DeleteStagingPolicy] instance, the pending-staged-id flow, the undo
 *   ([undoStaged]), and the one commit poller ([armCommitLoop]) that executes
 *   each expired id through the store exactly once;
 * - the warm-decryption maps ([decryptedPhotos], [encryptedVideoIds]) and the
 *   loop that fills them, so the sniff/decrypt pass over the history runs
 *   once per process instead of once per gallery route.
 *
 * Why app-scoped: staging done in the viewer must be visible in the grid —
 * with two per-route copies, a stage in the viewer stayed invisible to the
 * grid until commit (its items read as present during the undo window) and
 * the warm loop ran twice. One session shared by both routes' ViewModels is
 * the single staging truth; the ViewModels delegate to it and keep only
 * their own per-route UI state (selection, filter, search).
 *
 * Commit-on-exit contract: leaving the GALLERY AREA is not an undo — the
 * gallery ViewModel's `onCleared` calls [flush], committing whatever is
 * still staged immediately. Leaving the VIEWER (pop back to the grid)
 * deliberately does not flush: its staged ids stay pending in the shared
 * set with a live undo affordance in the grid, and the expiry poller
 * commits them when their window lapses. The poller is the safety net for
 * every staged id either way.
 *
 * Threading: the mutators run on the UI thread (via the ViewModels) while
 * the poller runs on [appScope] (a Default dispatcher) — every policy access
 * is serialized under [lock] and the pending set is republished inside it,
 * replacing the old ViewModel's Main-thread confinement.
 */
class GallerySession(
    private val captureHistoryStore: CaptureHistoryStore,
    /**
     * The decrypted-photo cache behind Coil when media encryption is on;
     * null (tests, or encryption never relevant) disables the warm loop.
     */
    private val photoCache: DecryptedPhotoCache? = null,
    /** The scope the poller, warm loop, and exit flush deletes run on. */
    private val appScope: CoroutineScope,
    /** The undo window length; injectable so tests can shrink it. */
    private val stagingWindowMs: Long = DeleteStagingPolicy.DEFAULT_WINDOW_MS,
) {

    init {
        // The decryption warm loop: encrypted photos become cache files the
        // grid/viewer can hand Coil; encrypted videos are collected into the
        // placeholder set (they play through the decrypting stream, but their
        // thumbnails cannot be framed). All sniff/decrypt IO is off-main.
        if (photoCache != null) {
            appScope.launch(Dispatchers.IO) {
                captureHistoryStore.history.collect { items -> warmDecryption(items) }
            }
        }
    }

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
    // leaving the gallery area — executes the real MediaStore delete. The
    // verdicts are [DeleteStagingPolicy]'s; this session owns the timer and
    // the store call, and publishes the pending set so the grid and the
    // viewer react to a stage/undo the moment it happens.

    /** Serializes every [staging] access (UI thread mutators vs the poller). */
    private val lock = Any()

    /** Pure staging state: the pending set, expiry, undo, and commit verdicts. */
    private val staging = DeleteStagingPolicy(stagingWindowMs)

    private val _stagedIds = MutableStateFlow<Set<String>>(emptySet())

    /** The ids staged for deletion (hidden, not yet removed from the store). */
    val stagedIds: StateFlow<Set<String>> = _stagedIds.asStateFlow()

    /** The in-flight commit poller; one at a time, restarted per stage. */
    private var commitJob: Job? = null

    /** Stages [ids] (refreshing any window already open on them); the caller shows the Undo snackbar. */
    fun stage(ids: List<String>) {
        synchronized(lock) {
            staging.stage(ids, System.currentTimeMillis())
            publishLocked()
            armCommitLoopLocked()
        }
    }

    /** The undo: unstages the ids — they reappear, nothing was ever deleted. */
    fun undoStaged(ids: List<String>) {
        synchronized(lock) {
            staging.undo(ids)
            publishLocked()
        }
    }

    /**
     * The commit-on-exit path ("leaving the gallery area is not an undo"):
     * force-commits whatever is still staged, unstaging it up front. The
     * deletes are ContentResolver IO, so they run on the app scope and
     * outlive whichever screen asked for the flush.
     */
    fun flush() {
        val due: List<String>
        synchronized(lock) {
            due = staging.pendingIds()
            staging.undoAll()
            publishLocked()
        }
        if (due.isNotEmpty()) {
            appScope.launch {
                withContext(Dispatchers.IO) { captureHistoryStore.deleteAll(due) }
            }
        }
    }

    private fun publishLocked() {
        _stagedIds.value = staging.pendingIds().toSet()
    }

    /**
     * The one commit timer: polls the staging policy until nothing is pending,
     * executing each expired id exactly once through the store's batch delete.
     * Not UI-confined like the old per-ViewModel loop — the [lock] guards the
     * policy, and the pending-set publication order (after the store delete
     * lands) keeps a committed item hidden until its media is really gone.
     */
    private fun armCommitLoopLocked() {
        if (commitJob?.isActive == true) return
        commitJob = appScope.launch {
            while (isActive) {
                val due = synchronized(lock) { staging.dueForCommit(System.currentTimeMillis()) }
                if (due.isNotEmpty()) {
                    withContext(Dispatchers.IO) { captureHistoryStore.deleteAll(due) }
                    synchronized(lock) { publishLocked() }
                }
                val pending = synchronized(lock) { staging.pendingIds() }
                if (pending.isEmpty()) return@launch
                delay(COMMIT_POLL_MS)
            }
        }
    }

    companion object {
        private const val COMMIT_POLL_MS = 250L
    }
}

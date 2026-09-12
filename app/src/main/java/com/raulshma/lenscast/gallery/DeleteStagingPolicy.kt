package com.raulshma.lenscast.gallery

/**
 * The pure soft-delete staging model behind the gallery's trash/undo: user
 * deletes are *staged* (hidden from the list, media untouched) for a short
 * window with an undo affordance; only the commit pass — after expiry or a
 * screen-level confirm — actually deletes the backing media.
 *
 * Pure state machine over caller-supplied clocks and ids, so the staging
 * window, expiry, undo, and the "what is due for commit" verdict are
 * JVM-tested. The caller (GalleryViewModel) owns the timer that calls
 * [dueForCommit] and executes the returned ids through the capture history
 * store's delete; the ids are opaque strings here.
 *
 * Semantics:
 * - [stage] adds/refreshes ids at the staged moment (re-deleting an
 *   already-staged id restarts its window — the user just acted on it).
 * - [undo] removes ids from the pending set (they reappear; nothing was
 *   ever deleted).
 * - [dueForCommit] returns the ids whose window lapsed and removes them
 *   from the pending set — the caller deletes exactly those, once.
 * - [undoAll] empties the pending set; the ViewModel pairs it with a direct
 *   store delete for the force-commit path (screen exit commits whatever is
 *   still staged — leaving the screen is not an undo).
 */
class DeleteStagingPolicy(
    /** How long a staged delete waits for an undo before it commits. */
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    private val staged = LinkedHashMap<String, Long>()

    /** The ids currently staged (hidden, not yet deleted), in staging order. */
    fun pendingIds(): List<String> = staged.keys.toList()

    /** Whether [id] is staged (the list layer hides staged ids). */
    fun isStaged(id: String): Boolean = staged.containsKey(id)

    /** Stages [ids] at [nowMs]; re-staging refreshes the window. */
    fun stage(ids: List<String>, nowMs: Long) {
        ids.forEach { staged[it] = nowMs }
    }

    /** Un-stages [ids] — the undo. Unknown ids are a no-op. */
    fun undo(ids: List<String>) {
        ids.forEach { staged.remove(it) }
    }

    /** Un-stages everything (e.g. the staging owner is going away without deleting). */
    fun undoAll() = staged.clear()

    /**
     * The ids whose window has lapsed at [nowMs], removed from the pending
     * set — the one commit verdict, so the caller executes each id exactly
     * once even if it polls eagerly.
     */
    fun dueForCommit(nowMs: Long): List<String> {
        val due = staged.filterValues { nowMs - it >= windowMs }.keys.toList()
        due.forEach { staged.remove(it) }
        return due
    }

    companion object {
        /** The undo window: ~15 s, the gallery trash convention. */
        const val DEFAULT_WINDOW_MS = 15_000L
    }
}

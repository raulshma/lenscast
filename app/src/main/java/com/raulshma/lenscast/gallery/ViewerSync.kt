package com.raulshma.lenscast.gallery

import com.raulshma.lenscast.capture.model.CaptureHistory

/**
 * What the viewer's pager should do next, as pure data: [JumpTo] for the
 * initial placement, [AnimateTo] for a later resync, [Pop] to leave the
 * viewer. The one effect vocabulary NavigationGraph executes.
 */
sealed interface ViewerSyncEffect {
    data class JumpTo(val index: Int) : ViewerSyncEffect
    data class AnimateTo(val index: Int) : ViewerSyncEffect
    data object Pop : ViewerSyncEffect
}

/**
 * The media viewer's resync state machine — the choreography behind the
 * pager/list/placed triple, as plain Kotlin. It owns the one resync verdict
 * (pin to the routed id, clamp on a shrunken list, or land on the
 * delete-fallback neighbor once the routed id left the list — the
 * [viewerResyncTarget] math) plus the jump-vs-animate rule: the first resync
 * of a freshly opened viewer is the initial placement and jumps instantly
 * (the pager may have been created before the list loaded); later resyncs —
 * list changes and the post-delete landing — animate. It also owns the
 * delete-time pop verdict: the pager lands on the delete-fallback neighbor
 * through the resync effect; pop only when the gallery is now empty.
 */
class ViewerSync(initialPlaced: Boolean = false) {

    /**
     * Whether the viewer has had its first placement. Saveable across
     * process death so a restored viewer keeps animating instead of jumping.
     */
    var placed: Boolean = initialPlaced
        private set

    /**
     * The resync verdict for a newly observed list, or null when the pager
     * already sits on its target. Any non-empty observation places the
     * viewer, so the jump one-shot fires exactly once — an empty observation
     * places nothing (the viewer pops back instead).
     */
    fun reduce(allItems: List<CaptureHistory>, mediaId: String, currentPage: Int): ViewerSyncEffect? {
        val effect = viewerResyncTarget(allItems, mediaId, currentPage)?.let { target ->
            if (placed) ViewerSyncEffect.AnimateTo(target) else ViewerSyncEffect.JumpTo(target)
        }
        if (allItems.isNotEmpty()) placed = true
        return effect
    }

    /**
     * The verdict at delete time, taken from the pre-delete size before the
     * store confirms: [ViewerSyncEffect.Pop] when the gallery is now empty,
     * null when the resync effect lands the pager on the neighbor.
     */
    fun onDelete(currentIndex: Int, sizeBeforeDelete: Int): ViewerSyncEffect? {
        if (indexAfterDelete(currentIndex, sizeBeforeDelete - 1) != null) return null
        return ViewerSyncEffect.Pop
    }
}

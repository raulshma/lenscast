package com.raulshma.lenscast.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The soft-delete staging state machine behind the gallery's trash/undo:
 * stage → (undo | expire) → commit-exactly-once, over a caller-supplied clock.
 */
class DeleteStagingPolicyTest {

    private val window = 15_000L

    @Test
    fun `staged ids are pending and not yet due`() {
        val staging = DeleteStagingPolicy(windowMs = window)
        staging.stage(listOf("a", "b"), nowMs = 1_000)
        assertEquals(listOf("a", "b"), staging.pendingIds())
        assertTrue(staging.isStaged("a"))
        assertTrue(staging.dueForCommit(nowMs = 1_000 + window - 1).isEmpty())
    }

    @Test
    fun `expiry commits exactly once and removes from pending`() {
        val staging = DeleteStagingPolicy(windowMs = window)
        staging.stage(listOf("a"), nowMs = 0)
        assertEquals(listOf("a"), staging.dueForCommit(nowMs = window))
        // An eager second poll must not re-commit the same id.
        assertTrue(staging.dueForCommit(nowMs = window + 1).isEmpty())
        assertFalse(staging.isStaged("a"))
    }

    @Test
    fun `undo unstages only the given ids`() {
        val staging = DeleteStagingPolicy(windowMs = window)
        staging.stage(listOf("a", "b"), nowMs = 0)
        staging.undo(listOf("a"))
        assertEquals(listOf("b"), staging.pendingIds())
        // Undo of an unknown id is a no-op.
        staging.undo(listOf("zzz"))
        assertEquals(listOf("b"), staging.pendingIds())
    }

    @Test
    fun `re-staging refreshes the window`() {
        val staging = DeleteStagingPolicy(windowMs = window)
        staging.stage(listOf("a"), nowMs = 0)
        // The user re-deletes the same item mid-window: the clock restarts.
        staging.stage(listOf("a"), nowMs = 10_000)
        assertTrue(staging.dueForCommit(nowMs = 10_000 + window - 1).isEmpty())
        assertEquals(listOf("a"), staging.dueForCommit(nowMs = 10_000 + window))
    }

    @Test
    fun `different windows expire independently`() {
        val staging = DeleteStagingPolicy(windowMs = window)
        staging.stage(listOf("early"), nowMs = 0)
        staging.stage(listOf("late"), nowMs = 10_000)
        assertEquals(listOf("early"), staging.dueForCommit(nowMs = window))
        assertEquals(listOf("late"), staging.dueForCommit(nowMs = 10_000 + window))
    }

    @Test
    fun `undoAll clears everything`() {
        val staging = DeleteStagingPolicy(windowMs = window)
        staging.stage(listOf("a", "b"), nowMs = 0)
        staging.undoAll()
        assertTrue(staging.pendingIds().isEmpty())
    }

    @Test
    fun `default window is the 15s gallery trash convention`() {
        assertEquals(15_000L, DeleteStagingPolicy.DEFAULT_WINDOW_MS)
    }
}

/** The gallery search verdict shared by the app grid and the web `q` filter. */
class GallerySearchPolicyTest {

    private data class Item(val name: String)

    @Test
    fun `blank query matches everything and preserves order`() {
        val items = listOf(Item("VID_1.mp4"), Item("IMG_2.jpg"))
        assertEquals(items, GallerySearchPolicy.filter(items, "  ") { it.name })
    }

    @Test
    fun `match is case insensitive contains on the trimmed query`() {
        val items = listOf(Item("VID_20260912_101530.mp4"), Item("IMG_20260912_101531.jpg"))
        assertEquals(
            listOf(Item("VID_20260912_101530.mp4")),
            GallerySearchPolicy.filter(items, "  vid_2026 ") { it.name },
        )
    }

    @Test
    fun `non matching queries answer an empty list`() {
        val items = listOf(Item("a.jpg"), Item("b.jpg"))
        assertTrue(GallerySearchPolicy.filter(items, "zzz") { it.name }.isEmpty())
    }

    @Test
    fun `matches is total for a blank query even on blank names`() {
        assertTrue(GallerySearchPolicy.matches("", ""))
        assertFalse(GallerySearchPolicy.matches("x", ""))
    }
}

package com.raulshma.lenscast.capture

import com.raulshma.lenscast.capture.model.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture screen's schedule row is a pure function of the controller's RecordingState. */
class ScheduledRecordingUiTest {

    @Test
    fun `idle maps to no schedule UI`() {
        assertNull(scheduledUiModel(RecordingState.Idle))
    }

    @Test
    fun `a scheduled start exposes its time as cancellable and arming`() {
        val startAtMs = 1_700_000_000_000L
        val ui = scheduledUiModel(
            RecordingState.Scheduled(startAtMs = startAtMs, config = RecordingConfig())
        )
        requireNotNull(ui)
        assertEquals(startAtMs, ui.startAtMs)
        assertTrue(ui.canCancel)
        assertTrue(ui.isScheduled)
    }

    @Test
    fun `a live recording maps to no schedule UI - the recording section replaces the row`() {
        assertNull(
            scheduledUiModel(
                RecordingState.Recording(startedAtMs = 1_700_000_000_000L, config = RecordingConfig())
            )
        )
        assertNull(
            scheduledUiModel(
                RecordingState.Recording(startedAtMs = 1L, config = null, finalizing = true)
            )
        )
    }

    @Test
    fun `the mapping never invents a cancel for a state without an armed job`() {
        // Every non-Scheduled state must be null so the trash button can only
        // appear from the controller's armed schedule or the screen's own pick.
        listOf<RecordingState>(
            RecordingState.Idle,
            RecordingState.Recording(startedAtMs = 0L, config = null),
        ).forEach { state ->
            val ui = scheduledUiModel(state)
            if (ui != null) {
                assertFalse(ui.canCancel)
            }
        }
    }
}

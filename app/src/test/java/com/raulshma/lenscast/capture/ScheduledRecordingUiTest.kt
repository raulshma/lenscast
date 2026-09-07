package com.raulshma.lenscast.capture

import com.raulshma.lenscast.capture.model.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The capture screen's schedule row is a pure function of the controller's RecordingState. */
class ScheduledRecordingUiTest {

    private fun scheduledAt(startAtMs: Long) =
        RecordingState.Scheduled(startAtMs = startAtMs, config = RecordingConfig())

    @Test
    fun `idle maps to no schedule UI`() {
        assertNull(scheduledUiModel(RecordingState.Idle))
    }

    @Test
    fun `a scheduled start exposes its time as cancellable and arming`() {
        val startAtMs = 1_700_000_000_000L
        val ui = scheduledUiModel(scheduledAt(startAtMs))
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

    // ── The widened schedule row ──

    private fun noonInstant(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 13)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** The label is device-locale "HH:mm" — compute the expectation with the same contract. */
    private fun expectedStartLabel(timeMs: Long): String {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "Start: ${format.format(Date(timeMs))}"
    }

    @Test
    fun `the row keeps the exact legacy strings`() {
        assertEquals("Start: ", SCHEDULED_START_PREFIX)
        assertEquals("Set Start Time", SCHEDULED_NO_START_LABEL)
        assertEquals("Schedule", SCHEDULED_BUTTON_LABEL)
        assertEquals("Start Now", SCHEDULED_BUTTON_START_NOW_LABEL)
    }

    @Test
    fun `a draft-only pick labels formats and cancels without arming`() {
        val timeMs = noonInstant()
        val row = scheduleRowUi(RecordingState.Idle, draftStartMs = timeMs)
        assertEquals(expectedStartLabel(timeMs), row.label)
        assertEquals(timeMs, row.pendingStartMs)
        assertTrue(row.canCancel)
        assertEquals("Schedule", row.buttonLabel)
    }

    @Test
    fun `an armed-only schedule labels formats and cancels`() {
        val timeMs = noonInstant()
        val row = scheduleRowUi(scheduledAt(timeMs), draftStartMs = null)
        assertEquals(expectedStartLabel(timeMs), row.label)
        assertEquals(timeMs, row.pendingStartMs)
        assertTrue(row.canCancel)
        assertEquals("Schedule", row.buttonLabel)
    }

    @Test
    fun `armed and draft together - the armed start wins the merge`() {
        val armedMs = noonInstant()
        val draftMs = armedMs + 60_000
        val row = scheduleRowUi(scheduledAt(armedMs), draftStartMs = draftMs)
        assertEquals(expectedStartLabel(armedMs), row.label)
        assertEquals(armedMs, row.pendingStartMs)
        assertTrue(row.canCancel)
        assertEquals("Schedule", row.buttonLabel)
    }

    @Test
    fun `nothing pending - no start label, no trash, immediate start`() {
        val row = scheduleRowUi(RecordingState.Idle, draftStartMs = null)
        assertEquals("Set Start Time", row.label)
        assertNull(row.pendingStartMs)
        assertFalse(row.canCancel)
        assertEquals("Start Now", row.buttonLabel)
    }

    @Test
    fun `a live recording with no draft renders the immediate row`() {
        val row = scheduleRowUi(
            RecordingState.Recording(startedAtMs = 1L, config = null),
            draftStartMs = null,
        )
        assertEquals("Set Start Time", row.label)
        assertNull(row.pendingStartMs)
        assertFalse(row.canCancel)
        assertEquals("Start Now", row.buttonLabel)
    }

    @Test
    fun `canCancel truth table - armed or draft, never bare idle or recording`() {
        val timeMs = noonInstant()
        val states = mapOf(
            "idle" to RecordingState.Idle,
            "scheduled" to scheduledAt(timeMs),
            "recording" to RecordingState.Recording(startedAtMs = 1L, config = null),
        )
        val draftOnly = mapOf("no draft" to null, "draft" to timeMs)
        val expected = mapOf(
            "idle" to false,
            "scheduled" to true,
            "recording" to false,
        )
        states.forEach { (stateName, state) ->
            draftOnly.forEach { (draftName, draft) ->
                val row = scheduleRowUi(state, draftStartMs = draft)
                assertEquals(
                    "canCancel($stateName, $draftName)",
                    expected.getValue(stateName) || draft != null,
                    row.canCancel,
                )
            }
        }
    }

    @Test
    fun `button verdict truth table - Schedule whenever armed or drafted, else Start Now`() {
        val timeMs = noonInstant()
        val states = mapOf(
            "idle" to RecordingState.Idle,
            "scheduled" to scheduledAt(timeMs),
            "recording" to RecordingState.Recording(startedAtMs = 1L, config = null),
        )
        val draftOnly = mapOf("no draft" to null, "draft" to timeMs)
        states.forEach { (stateName, state) ->
            draftOnly.forEach { (draftName, draft) ->
                val row = scheduleRowUi(state, draftStartMs = draft)
                val expectedLabel =
                    if (stateName == "scheduled" || draft != null) "Schedule" else "Start Now"
                assertEquals(
                    "buttonLabel($stateName, $draftName)",
                    expectedLabel,
                    row.buttonLabel,
                )
            }
        }
    }

    @Test
    fun `a stale already-past draft still renders its time - the row has no clock`() {
        val pastMs = System.currentTimeMillis() - 60 * 60_000
        val row = scheduleRowUi(RecordingState.Idle, draftStartMs = pastMs)
        assertEquals(expectedStartLabel(pastMs), row.label)
        assertTrue(row.canCancel)
        assertEquals("Schedule", row.buttonLabel)
    }
}

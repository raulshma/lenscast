package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.IntervalCaptureState
import com.raulshma.lenscast.companion.client.dto.IntervalCaptureStatus
import com.raulshma.lenscast.companion.client.dto.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote capture backend's pure wire→state projections (JVM-pure — both
 * ends are Moshi data classes): the recording status onto the controller
 * state vocabulary the schedule-row model renders, and the interval status
 * onto the tri-state the interval section renders. The recording mapping is
 * the console readout's, pinned here so the capture screen and the dashboard
 * can never disagree by a drifted copy.
 */
class RemoteCaptureMappersTest {

    // ── RecordingStatus → RecordingState ──

    @Test
    fun `a live recording maps to Recording with the start back-computed from elapsed seconds`() {
        val now = 1_700_000_050_000L
        val state = RecordingStatus(
            isRecording = true,
            elapsedSeconds = 42,
        ).toRecordingState(nowMs = now)
        assertTrue(state is RecordingState.Recording)
        assertEquals(now - 42_000L, (state as RecordingState.Recording).startedAtMs)
        assertNull(state.config)
    }

    @Test
    fun `an armed schedule maps to Scheduled at the wire start instant`() {
        val start = 1_788_825_600_000L
        val state = RecordingStatus(
            isRecording = false,
            elapsedSeconds = 0,
            isScheduled = true,
            scheduledStartTimeMs = start,
        ).toRecordingState(nowMs = 0L)
        assertTrue(state is RecordingState.Scheduled)
        assertEquals(start, (state as RecordingState.Scheduled).startAtMs)
    }

    @Test
    fun `an armed schedule without an instant lands at zero rather than crashing`() {
        val state = RecordingStatus(
            isRecording = false,
            elapsedSeconds = 0,
            isScheduled = true,
            scheduledStartTimeMs = null,
        ).toRecordingState(nowMs = 0L)
        assertEquals(0L, (state as RecordingState.Scheduled).startAtMs)
    }

    @Test
    fun `an idle device maps to Idle even with a stale scheduled flag absent`() {
        val state = RecordingStatus(isRecording = false, elapsedSeconds = 0)
            .toRecordingState(nowMs = 1L)
        assertEquals(RecordingState.Idle, state)
    }

    @Test
    fun `a live recording wins over a stale scheduled flag`() {
        // The wire's when-order is isRecording first — the device controller
        // can never hold both states, but the mapper stays defensive.
        val state = RecordingStatus(
            isRecording = true,
            elapsedSeconds = 3,
            isScheduled = true,
            scheduledStartTimeMs = 5L,
        ).toRecordingState(nowMs = 3_000L)
        assertTrue(state is RecordingState.Recording)
    }

    // ── IntervalCaptureStatus → IntervalCaptureState ──

    @Test
    fun `a running series maps to Running with the completed count riding along`() {
        val state = IntervalCaptureStatus(isRunning = true, completedCaptures = 12).toIntervalState()
        val running = state as IntervalCaptureState.Running
        assertEquals(12, running.snapshot.completedCaptures)
        assertTrue(running.snapshot.isRunning)
    }

    @Test
    fun `a stopped series maps to Stopped`() {
        val state = IntervalCaptureStatus(isRunning = false, completedCaptures = 100).toIntervalState()
        assertEquals(IntervalCaptureState.Stopped, state)
    }
}

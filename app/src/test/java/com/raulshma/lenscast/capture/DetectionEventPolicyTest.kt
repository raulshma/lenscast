package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEventPolicyTest {

    @Test
    fun `recording starts only when enabled armed and idle`() {
        assertEquals(
            DetectionEventPolicy.RecordingAction.START,
            DetectionEventPolicy.recordingAction(motionRecordingEnabled = true, armed = true, recordingActive = false),
        )
        assertEquals(
            DetectionEventPolicy.RecordingAction.KEEP_ROLLING,
            DetectionEventPolicy.recordingAction(motionRecordingEnabled = true, armed = true, recordingActive = true),
        )
        assertEquals(
            DetectionEventPolicy.RecordingAction.NONE,
            DetectionEventPolicy.recordingAction(motionRecordingEnabled = true, armed = false, recordingActive = false),
        )
        assertEquals(
            DetectionEventPolicy.RecordingAction.NONE,
            DetectionEventPolicy.recordingAction(motionRecordingEnabled = false, armed = true, recordingActive = false),
        )
    }

    @Test
    fun `auto photo only in legacy mode`() {
        assertTrue(DetectionEventPolicy.shouldAutoPhoto(motionRecordingEnabled = false, armed = true))
        assertFalse(DetectionEventPolicy.shouldAutoPhoto(motionRecordingEnabled = false, armed = false))
        assertFalse(DetectionEventPolicy.shouldAutoPhoto(motionRecordingEnabled = true, armed = true))
    }

    @Test
    fun `sound recording only when the sound toggle is on`() {
        assertEquals(
            DetectionEventPolicy.RecordingAction.NONE,
            DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = true,
                armed = true,
                recordingActive = false,
                soundRecordingEnabled = false,
                isSound = true,
            ),
        )
        assertEquals(
            DetectionEventPolicy.RecordingAction.START,
            DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = false,
                armed = true,
                recordingActive = false,
                soundRecordingEnabled = true,
                isSound = true,
            ),
        )
    }

    @Test
    fun `sound events follow the same armed and live-recording gates as motion`() {
        assertEquals(
            DetectionEventPolicy.RecordingAction.NONE,
            DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = false,
                armed = false,
                recordingActive = false,
                soundRecordingEnabled = true,
                isSound = true,
            ),
        )
        assertEquals(
            DetectionEventPolicy.RecordingAction.KEEP_ROLLING,
            DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = false,
                armed = true,
                recordingActive = true,
                soundRecordingEnabled = true,
                isSound = true,
            ),
        )
    }

    @Test
    fun `sound toggle does not leak into motion events and vice versa`() {
        // Motion ignores the sound toggle entirely.
        assertEquals(
            DetectionEventPolicy.RecordingAction.NONE,
            DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = false,
                armed = true,
                recordingActive = false,
                soundRecordingEnabled = true,
                isSound = false,
            ),
        )
        // Sound ignores the motion toggle entirely.
        assertEquals(
            DetectionEventPolicy.RecordingAction.NONE,
            DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = true,
                armed = true,
                recordingActive = false,
                soundRecordingEnabled = false,
                isSound = true,
            ),
        )
    }

    @Test
    fun `auto photo never fires for sound events`() {
        assertFalse(
            DetectionEventPolicy.shouldAutoPhoto(
                motionRecordingEnabled = false,
                armed = true,
                isSound = true,
            ),
        )
        assertTrue(
            DetectionEventPolicy.shouldAutoPhoto(
                motionRecordingEnabled = false,
                armed = true,
                isSound = false,
            ),
        )
    }
}

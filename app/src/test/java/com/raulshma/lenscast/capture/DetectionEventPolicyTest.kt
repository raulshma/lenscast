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
}

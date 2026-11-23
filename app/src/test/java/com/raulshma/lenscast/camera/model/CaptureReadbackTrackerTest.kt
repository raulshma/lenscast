package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Expected
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Level
import com.raulshma.lenscast.camera.model.CaptureReadbackPolicy.Reported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the read-back's sampling state machine: armed by a submit, at most
 * one sample a second, concluded by exactly one line on the first match or
 * at the timeout. The tracker never reads a clock — every call takes the
 * time in ms — so these tests drive it frame by frame.
 */
class CaptureReadbackTrackerTest {

    private val interval = CaptureReadbackPolicy.SAMPLE_INTERVAL_MS
    private val timeout = CaptureReadbackPolicy.TIMEOUT_MS

    private val manualNight = Expected(
        aeMode = CaptureRequest.CONTROL_AE_MODE_OFF,
        sensorSensitivity = 3200,
        sensorExposureTimeNs = 100_000_000L,
        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
    )

    private val automatic = Expected(
        aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
        sensorSensitivity = null,
        sensorExposureTimeNs = null,
        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
    )

    /** What a camera still running its defaults reports. */
    private val stillAutomatic = Reported(
        aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
        sensorSensitivity = 100,
        sensorExposureTimeNs = 41_666_666L,
        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
    )

    private fun landed(iso: Int = 3200) = Reported(
        aeMode = CaptureRequest.CONTROL_AE_MODE_OFF,
        sensorSensitivity = iso,
        sensorExposureTimeNs = 100_000_000L,
        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
    )

    private val tracker = CaptureReadbackTracker()

    private fun armManualNight(atMs: Long = 0L) = tracker.arm(manualNight, atMs)

    @Test
    fun `a match on the first sample concludes with one info line and disarms`() {
        armManualNight()

        val line = tracker.onFrame(nowMs = 40, reported = landed())

        assertEquals("Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=NIGHT)", line?.message)
        assertEquals(Level.Info, line?.level)
        assertFalse(tracker.isArmed)
        // Disarmed: the frames behind the confirmation conclude nothing.
        assertNull(tracker.onFrame(nowMs = 80, reported = landed()))
        assertNull(tracker.onFrame(nowMs = timeout * 2, reported = stillAutomatic))
    }

    @Test
    fun `a mismatch keeps sampling until the device matches`() {
        // The first results after a submit can still carry the previous
        // request: one stale frame must not be reported as a failure.
        armManualNight()
        assertNull(tracker.onFrame(nowMs = 40, reported = stillAutomatic))
        assertTrue(tracker.isArmed)
        // Inside the sampling interval: not even looked at.
        assertNull(tracker.onFrame(nowMs = interval - 1, reported = landed()))
        assertTrue(tracker.isArmed)

        assertEquals(
            "Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=NIGHT)",
            tracker.onFrame(nowMs = interval + 40, reported = landed())?.message,
        )
        assertFalse(tracker.isArmed)
    }

    @Test
    fun `a mismatch that never matches concludes once at the timeout with the last device state`() {
        armManualNight()
        assertNull(tracker.onFrame(nowMs = 40, reported = stillAutomatic))

        // The device keeps reporting something else, with a changing ISO so
        // the final line can be traced to its last in-window sample.
        var t = interval + 40
        var iso = 200
        while (t < timeout) {
            assertNull(tracker.onFrame(nowMs = t, reported = stillAutomatic.copy(sensorSensitivity = iso)))
            assertTrue(tracker.isArmed)
            t += interval
            iso += 100
        }

        // The first frame past the window concludes it — the frame itself is
        // outside the window and is not sampled: the final state is the last
        // one the window saw.
        val line = tracker.onFrame(nowMs = t, reported = landed())
        assertEquals(Level.Warning, line?.level)
        assertEquals(
            "Camera controls readback: MISMATCH requested AE=OFF ISO=3200 exp=100000000ns scene=NIGHT " +
                "| device AE=ON ISO=${iso - 100} exp=41666666ns scene=DISABLED",
            line?.message,
        )
        assertFalse(tracker.isArmed)
        // Exactly one line per armed apply.
        assertNull(tracker.onFrame(nowMs = t + interval, reported = stillAutomatic))
    }

    @Test
    fun `at most one sample per second`() {
        // Frames arrive every 100 ms. A mismatch at 40 ms starts the clock;
        // a match at 1039 ms is inside the interval and ignored; at 1040 ms
        // — exactly one interval later — it is sampled.
        armManualNight()
        assertNull(tracker.onFrame(nowMs = 40, reported = stillAutomatic))
        assertNull(tracker.onFrame(nowMs = 1039, reported = landed()))
        assertTrue(tracker.isArmed)

        assertEquals(Level.Info, tracker.onFrame(nowMs = 1040, reported = landed())?.level)
    }

    @Test
    fun `the window closes exactly at the timeout`() {
        armManualNight()
        assertNull(tracker.onFrame(nowMs = 40, reported = stillAutomatic))
        // The last sample before the window closes is still in it …
        assertNull(tracker.onFrame(nowMs = timeout - 10, reported = stillAutomatic))

        // … and the first frame at or past the timeout concludes, even
        // though the last sample is less than an interval old.
        assertEquals(Level.Warning, tracker.onFrame(nowMs = timeout, reported = landed())?.level)
        assertFalse(tracker.isArmed)
    }

    @Test
    fun `expiry without any capture result is one unknown line`() {
        // A stalled stream delivers nothing inside the window: the tracker
        // owns no thread, so the conclusion waits for the next frame —
        // however late — and says that nothing arrived.
        armManualNight(atMs = 1_000)

        val line = tracker.onFrame(nowMs = 60_000, reported = landed())

        assertEquals(Level.Warning, line?.level)
        assertEquals(
            "Camera controls readback: UNKNOWN requested AE=OFF ISO=3200 exp=100000000ns scene=NIGHT " +
                "| device reported no capture result within 10000ms",
            line?.message,
        )
        assertFalse(tracker.isArmed)
    }

    @Test
    fun `a result missing a needed value keeps sampling and concludes unknown if it never fills in`() {
        armManualNight()
        val halfReported = Reported(
            aeMode = CaptureRequest.CONTROL_AE_MODE_OFF,
            sensorSensitivity = null,
            sensorExposureTimeNs = null,
            sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
        )

        // Unknown is not a match: the window stays open …
        assertNull(tracker.onFrame(nowMs = 40, reported = halfReported))
        assertTrue(tracker.isArmed)
        // … and a later complete result still concludes it.
        assertEquals(
            Level.Info,
            tracker.onFrame(nowMs = interval + 40, reported = landed())?.level,
        )

        // With the value missing to the end, the conclusion is unknown.
        tracker.arm(manualNight, nowMs = 10_000)
        assertNull(tracker.onFrame(nowMs = 10_040, reported = halfReported))
        val line = tracker.onFrame(nowMs = 20_000, reported = halfReported)
        assertEquals(Level.Warning, line?.level)
        assertTrue(line?.message?.contains("| device AE=OFF ISO=null exp=nullns scene=NIGHT") == true)
        assertFalse(tracker.isArmed)
    }

    @Test
    fun `AE on skips the sensor compare`() {
        tracker.arm(automatic, nowMs = 0)

        // The device's own ISO and shutter differ from anything and are not
        // looked at; null ones are not "missing" either.
        assertEquals(Level.Info, tracker.onFrame(nowMs = 40, reported = stillAutomatic)?.level)
        tracker.arm(automatic, nowMs = 1_000)
        assertEquals(
            Level.Info,
            tracker.onFrame(nowMs = 1_040, reported = Reported(CaptureRequest.CONTROL_AE_MODE_ON, null, null, CaptureRequest.CONTROL_SCENE_MODE_DISABLED))?.level,
        )
    }

    @Test
    fun `an unarmed tracker is a no-op`() {
        assertNull(tracker.onFrame(nowMs = 40, reported = landed()))
        assertNull(tracker.onFrame(nowMs = timeout * 3, reported = stillAutomatic))
        assertFalse(tracker.isArmed)
    }

    @Test
    fun `a new submit re-arms with its own values and restarts the window`() {
        armManualNight()
        assertNull(tracker.onFrame(nowMs = 40, reported = stillAutomatic))

        // Back to defaults: the plan changes hands mid-verification.
        tracker.arm(automatic, nowMs = 9_500)

        // Had the window not restarted, this frame — past the first arm's
        // timeout — would have concluded a mismatch. It belongs to the new
        // apply's window and confirms it.
        val line = tracker.onFrame(nowMs = 10_040, reported = stillAutomatic)
        assertEquals("Camera controls readback: ok (AE=ON scene=DISABLED)", line?.message)
        assertFalse(tracker.isArmed)
    }

    @Test
    fun `a superseded apply never logs`() {
        // The settings path applies twice within milliseconds (editor and
        // Applier). The first epoch is replaced before it was ever sampled:
        // it owes no line, and certainly not a mismatch it never observed.
        armManualNight()
        tracker.arm(manualNight, nowMs = 5)

        assertEquals(
            "Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=NIGHT)",
            tracker.onFrame(nowMs = 1_045, reported = landed())?.message,
        )
        // The replacement's window, not the first one's, governs the expiry.
        tracker.arm(manualNight, nowMs = 10_000)
        assertNull(tracker.onFrame(nowMs = 10_040, reported = stillAutomatic))
        assertNull(tracker.onFrame(nowMs = 19_990, reported = stillAutomatic))
        assertTrue(tracker.isArmed)
    }
}

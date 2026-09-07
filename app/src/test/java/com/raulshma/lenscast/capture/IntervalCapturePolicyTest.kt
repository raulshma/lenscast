package com.raulshma.lenscast.capture

import androidx.camera.core.ImageCapture
import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class IntervalCapturePolicyTest {

    @Test
    fun `clamp bounds the interval`() {
        assertEquals(1L, IntervalCapturePolicy.clamp(0, 10, "OFF").intervalSeconds)
        assertEquals(1L, IntervalCapturePolicy.clamp(-30, 10, "OFF").intervalSeconds)
        assertEquals(3600L, IntervalCapturePolicy.clamp(99999, 10, "OFF").intervalSeconds)
        assertEquals(30L, IntervalCapturePolicy.clamp(30, 10, "OFF").intervalSeconds)
    }

    @Test
    fun `clamp floors totals and progress at zero`() {
        val tick = IntervalCapturePolicy.clamp(5, -4, "OFF", completedCaptures = -2)
        assertEquals(0, tick.totalCaptures)
        assertEquals(0, tick.completedCaptures)
        assertEquals("OFF", tick.flashMode)
    }

    @Test
    fun `clamp ceilings totals so every entry path is bounded`() {
        assertEquals(
            IntervalCapturePolicy.TOTAL_CAPTURES_MAX,
            IntervalCapturePolicy.clamp(5, 100_000, "OFF").totalCaptures,
        )
        assertEquals(
            IntervalCapturePolicy.TOTAL_CAPTURES_MAX,
            IntervalCapturePolicy.clamp(5, IntervalCapturePolicy.TOTAL_CAPTURES_MAX, "OFF").totalCaptures,
        )
        assertEquals(1000, IntervalCapturePolicy.TOTAL_CAPTURES_MAX)
        // The zero sentinel (run until stopped) survives the clamp.
        assertEquals(0, IntervalCapturePolicy.clamp(5, 0, "OFF").totalCaptures)
    }

    @Test
    fun `worker-read totals are ceiling-bounded too`() {
        val data = Data.Builder()
            .putInt(IntervalCapturePolicy.KEY_TOTAL_CAPTURES, 100_000)
            .build()
        assertEquals(
            IntervalCapturePolicy.TOTAL_CAPTURES_MAX,
            IntervalCapturePolicy.readTick(data).totalCaptures,
        )
    }

    @Test
    fun `first tick fires immediately, continuations wait`() {
        assertEquals(0L, IntervalCapturePolicy.firstDelaySeconds())
        val tick = IntervalCapturePolicy.clamp(25, 10, "OFF")
        assertEquals(25L, IntervalCapturePolicy.nextDelaySeconds(tick))
    }

    @Test
    fun `zero total means run until stopped`() {
        assertFalse(IntervalCapturePolicy.isComplete(IntervalCapturePolicy.clamp(5, 0, "OFF", 9999)))
    }

    @Test
    fun `completion trips at the total`() {
        assertFalse(IntervalCapturePolicy.isComplete(IntervalCapturePolicy.clamp(5, 3, "OFF", 2)))
        assertTrue(IntervalCapturePolicy.isComplete(IntervalCapturePolicy.clamp(5, 3, "OFF", 3)))
        assertTrue(IntervalCapturePolicy.isComplete(IntervalCapturePolicy.clamp(5, 3, "OFF", 9)))
    }

    @Test
    fun `counting a capture increments progress`() {
        val tick = IntervalCapturePolicy.clamp(5, 3, "ON", 1)
        val advanced = IntervalCapturePolicy.countCapture(tick)
        assertEquals(2, advanced.completedCaptures)
        assertEquals(5L, advanced.intervalSeconds)
        assertEquals("ON", advanced.flashMode)
    }

    // ── retryVerdict ──

    @Test
    fun `attempts below the bound retry`() {
        for (attempt in 0 until IntervalCapturePolicy.MAX_CAPTURE_ATTEMPTS) {
            assertEquals(
                IntervalCapturePolicy.RetryVerdict.RETRY,
                IntervalCapturePolicy.retryVerdict(attempt),
            )
        }
        assertEquals(3, IntervalCapturePolicy.MAX_CAPTURE_ATTEMPTS)
    }

    @Test
    fun `at the bound and beyond the tick gives up`() {
        for (attempt in IntervalCapturePolicy.MAX_CAPTURE_ATTEMPTS..IntervalCapturePolicy.MAX_CAPTURE_ATTEMPTS + 5) {
            assertEquals(
                IntervalCapturePolicy.RetryVerdict.GIVE_UP,
                IntervalCapturePolicy.retryVerdict(attempt),
            )
        }
    }

    @Test
    fun `flash mapping is case-insensitive with off fallback`() {
        assertEquals(ImageCapture.FLASH_MODE_ON, IntervalCapturePolicy.resolveFlashMode("on"))
        assertEquals(ImageCapture.FLASH_MODE_ON, IntervalCapturePolicy.resolveFlashMode("ON"))
        assertEquals(ImageCapture.FLASH_MODE_AUTO, IntervalCapturePolicy.resolveFlashMode("auto"))
        assertEquals(ImageCapture.FLASH_MODE_OFF, IntervalCapturePolicy.resolveFlashMode("OFF"))
        assertEquals(ImageCapture.FLASH_MODE_OFF, IntervalCapturePolicy.resolveFlashMode("torch"))
        assertEquals(ImageCapture.FLASH_MODE_OFF, IntervalCapturePolicy.resolveFlashMode(""))
    }

    @Test
    fun `tick survives an input data round-trip`() {
        val tick = IntervalCapturePolicy.clamp(45, 7, "AUTO", 3)
        val read = IntervalCapturePolicy.readTick(IntervalCapturePolicy.inputData(tick))
        assertEquals(tick, read)
    }

    @Test
    fun `worker-read ticks are upper-bounded too`() {
        val data = Data.Builder()
            .putLong(IntervalCapturePolicy.KEY_INTERVAL_SECONDS, 99999L)
            .build()
        assertEquals(3600L, IntervalCapturePolicy.readTick(data).intervalSeconds)
    }

    @Test
    fun `empty input data falls back to a one-second tick`() {
        val tick = IntervalCapturePolicy.readTick(Data.EMPTY)
        assertEquals(1L, tick.intervalSeconds)
        assertEquals(0, tick.totalCaptures)
        assertEquals("OFF", tick.flashMode)
        assertEquals(0, tick.completedCaptures)
    }

    @Test
    fun `progress data carries the count`() {
        val data = IntervalCapturePolicy.progressData(4)
        assertEquals(
            4,
            data.getInt(IntervalCapturePolicy.KEY_COMPLETED_CAPTURES, 0),
        )
    }

    @Test
    fun `no work means idle at zero`() {
        val snapshot = IntervalCapturePolicy.snapshotOf(emptyList())
        assertFalse(snapshot.isRunning)
        assertEquals(0, snapshot.completedCaptures)
    }

    @Test
    fun `unfinished work means running`() {
        val snapshot = IntervalCapturePolicy.snapshotOf(
            listOf(workInfo(WorkInfo.State.RUNNING, progressCompleted = 2)),
        )
        assertTrue(snapshot.isRunning)
        assertEquals(2, snapshot.completedCaptures)
    }

    @Test
    fun `finished work reports its output count`() {
        val snapshot = IntervalCapturePolicy.snapshotOf(
            listOf(workInfo(WorkInfo.State.SUCCEEDED, outputCompleted = 7)),
        )
        assertFalse(snapshot.isRunning)
        assertEquals(7, snapshot.completedCaptures)
    }

    @Test
    fun `completed count is the best seen across entries`() {
        val snapshot = IntervalCapturePolicy.snapshotOf(
            listOf(
                workInfo(WorkInfo.State.SUCCEEDED, outputCompleted = 3),
                workInfo(WorkInfo.State.SUCCEEDED, outputCompleted = 9),
            ),
        )
        assertEquals(9, snapshot.completedCaptures)
    }

    private fun workInfo(
        state: WorkInfo.State,
        progressCompleted: Int = 0,
        outputCompleted: Int = 0,
    ): WorkInfo = WorkInfo(
        UUID.randomUUID(),
        state,
        emptySet(),
        Data.Builder()
            .putInt(IntervalCapturePolicy.KEY_COMPLETED_CAPTURES, progressCompleted)
            .build(),
        Data.Builder()
            .putInt(IntervalCapturePolicy.KEY_COMPLETED_CAPTURES, outputCompleted)
            .build(),
    )
}

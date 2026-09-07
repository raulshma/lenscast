package com.raulshma.lenscast.capture

import androidx.camera.core.ImageCapture
import androidx.work.Data
import androidx.work.WorkInfo
import java.util.Locale

/**
 * The interval-capture policy: bounds, tick sequencing, flash mapping, and
 * status snapshots. Pure except for the WorkManager `Data`/`WorkInfo`
 * values it reads and writes — both constructible without a device, so the
 * whole module is testable through this one interface. The scheduler owns
 * enqueueing, the worker owns tick execution; neither holds policy
 * literals anymore.
 */
object IntervalCapturePolicy {

    const val MIN_INTERVAL_SECONDS = 1L
    const val MAX_INTERVAL_SECONDS = 3600L

    /** The total-captures ceiling both entry paths (screen slider and Web API) clamp to. */
    const val TOTAL_CAPTURES_MAX = 1000

    const val KEY_INTERVAL_SECONDS = "interval_seconds"
    const val KEY_TOTAL_CAPTURES = "total_captures"
    const val KEY_FLASH_MODE = "flash_mode"
    const val KEY_COMPLETED_CAPTURES = "completed_captures"

    /** One validated tick: what the scheduler enqueues and the worker reads. */
    data class Tick(
        val intervalSeconds: Long,
        val totalCaptures: Int,
        val flashMode: String,
        val completedCaptures: Int,
    )

    data class StatusSnapshot(
        val isRunning: Boolean,
        val completedCaptures: Int,
    )

    /** Clamp raw inputs to the valid tick (0 totals mean "run until stopped"). */
    fun clamp(
        intervalSeconds: Long,
        totalCaptures: Int,
        flashMode: String,
        completedCaptures: Int = 0,
    ): Tick = Tick(
        intervalSeconds = intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
        totalCaptures = totalCaptures.coerceIn(0, TOTAL_CAPTURES_MAX),
        flashMode = flashMode,
        completedCaptures = completedCaptures.coerceAtLeast(0),
    )

    /**
     * The worker's input with the historical defaults and the same bounds:
     * a crafted or stale queue entry can't smuggle an unbounded interval or
     * total in.
     */
    fun readTick(input: Data): Tick = Tick(
        intervalSeconds = input.getLong(KEY_INTERVAL_SECONDS, 1L)
            .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
        totalCaptures = input.getInt(KEY_TOTAL_CAPTURES, 0).coerceIn(0, TOTAL_CAPTURES_MAX),
        flashMode = input.getString(KEY_FLASH_MODE) ?: "OFF",
        completedCaptures = input.getInt(KEY_COMPLETED_CAPTURES, 0).coerceAtLeast(0),
    )

    fun inputData(tick: Tick): Data = Data.Builder()
        .putLong(
            KEY_INTERVAL_SECONDS,
            tick.intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
        )
        .putInt(KEY_TOTAL_CAPTURES, tick.totalCaptures.coerceIn(0, TOTAL_CAPTURES_MAX))
        .putString(KEY_FLASH_MODE, tick.flashMode)
        .putInt(KEY_COMPLETED_CAPTURES, tick.completedCaptures.coerceAtLeast(0))
        .build()

    /** First tick fires immediately; continuations wait out the interval. */
    fun firstDelaySeconds(): Long = 0L

    fun nextDelaySeconds(tick: Tick): Long = tick.intervalSeconds.coerceAtLeast(1L)

    /** A zero total means "run until stopped". */
    fun isComplete(tick: Tick): Boolean =
        tick.totalCaptures > 0 && tick.completedCaptures >= tick.totalCaptures

    fun countCapture(tick: Tick): Tick =
        tick.copy(completedCaptures = tick.completedCaptures + 1)

    fun resolveFlashMode(flashMode: String): Int =
        when (flashMode.uppercase(Locale.US)) {
            "ON" -> ImageCapture.FLASH_MODE_ON
            "AUTO" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

    fun progressData(completedCaptures: Int): Data = Data.Builder()
        .putInt(KEY_COMPLETED_CAPTURES, completedCaptures.coerceAtLeast(0))
        .build()

    /**
     * WorkManager's view of the unique work, summarized: running while any
     * entry is unfinished, completed as the best progress/output seen.
     */
    fun snapshotOf(workInfos: List<WorkInfo>): StatusSnapshot {
        val completedCaptures = workInfos.maxOfOrNull(::extractCompletedCaptures) ?: 0
        val isRunning = workInfos.any { !it.state.isFinished }
        return StatusSnapshot(
            isRunning = isRunning,
            completedCaptures = completedCaptures,
        )
    }

    private fun extractCompletedCaptures(workInfo: WorkInfo): Int = maxOf(
        workInfo.progress.getInt(KEY_COMPLETED_CAPTURES, 0),
        workInfo.outputData.getInt(KEY_COMPLETED_CAPTURES, 0),
    )
}

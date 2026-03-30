package com.raulshma.lenscast.capture

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object IntervalCaptureScheduler {

    fun start(
        context: Context,
        intervalSeconds: Long,
        totalCaptures: Int,
        imageQuality: Int,
        flashMode: String = "OFF",
        completedCaptures: Int = 0,
    ) {
        enqueue(
            context = context,
            policy = ExistingWorkPolicy.REPLACE,
            intervalSeconds = intervalSeconds,
            totalCaptures = totalCaptures,
            imageQuality = imageQuality,
            flashMode = flashMode,
            completedCaptures = completedCaptures,
            initialDelaySeconds = 0L,
        )
    }

    fun scheduleNext(
        context: Context,
        intervalSeconds: Long,
        totalCaptures: Int,
        imageQuality: Int,
        flashMode: String = "OFF",
        completedCaptures: Int,
    ) {
        enqueue(
            context = context,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            intervalSeconds = intervalSeconds,
            totalCaptures = totalCaptures,
            imageQuality = imageQuality,
            flashMode = flashMode,
            completedCaptures = completedCaptures,
            initialDelaySeconds = intervalSeconds.coerceAtLeast(1L),
        )
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun getStatus(context: Context): IntervalCaptureStatusSnapshot {
        val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
        val completedCaptures = workInfos.maxOfOrNull(::extractCompletedCaptures) ?: 0
        val isRunning = workInfos.any { !it.state.isFinished }
        return IntervalCaptureStatusSnapshot(
            isRunning = isRunning,
            completedCaptures = completedCaptures,
        )
    }

    private fun enqueue(
        context: Context,
        policy: ExistingWorkPolicy,
        intervalSeconds: Long,
        totalCaptures: Int,
        imageQuality: Int,
        flashMode: String,
        completedCaptures: Int,
        initialDelaySeconds: Long,
    ) {
        val requestBuilder = OneTimeWorkRequestBuilder<IntervalCaptureWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(IntervalCaptureWorker.KEY_INTERVAL_SECONDS, intervalSeconds.coerceAtLeast(1L))
                    .putInt(IntervalCaptureWorker.KEY_TOTAL_CAPTURES, totalCaptures.coerceAtLeast(0))
                    .putInt(IntervalCaptureWorker.KEY_IMAGE_QUALITY, imageQuality.coerceIn(10, 100))
                    .putString(IntervalCaptureWorker.KEY_FLASH_MODE, flashMode)
                    .putInt(
                        IntervalCaptureWorker.KEY_COMPLETED_CAPTURES,
                        completedCaptures.coerceAtLeast(0)
                    )
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )

        if (initialDelaySeconds > 0) {
            requestBuilder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            policy,
            requestBuilder.build(),
        )
    }

    private fun extractCompletedCaptures(workInfo: WorkInfo): Int {
        return maxOf(
            workInfo.progress.getInt(IntervalCaptureWorker.KEY_COMPLETED_CAPTURES, 0),
            workInfo.outputData.getInt(IntervalCaptureWorker.KEY_COMPLETED_CAPTURES, 0),
        )
    }

    data class IntervalCaptureStatusSnapshot(
        val isRunning: Boolean,
        val completedCaptures: Int,
    )

    const val WORK_NAME = "interval_capture"
}

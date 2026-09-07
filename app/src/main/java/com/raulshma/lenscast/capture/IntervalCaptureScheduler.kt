package com.raulshma.lenscast.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object IntervalCaptureScheduler {

    fun start(
        context: Context,
        intervalSeconds: Long,
        totalCaptures: Int,
        flashMode: String = "OFF",
        completedCaptures: Int = 0,
    ) {
        val tick = IntervalCapturePolicy.clamp(
            intervalSeconds, totalCaptures, flashMode, completedCaptures,
        )
        enqueue(
            context = context,
            policy = ExistingWorkPolicy.REPLACE,
            tick = tick,
            initialDelaySeconds = IntervalCapturePolicy.firstDelaySeconds(),
        )
    }

    fun scheduleNext(context: Context, tick: IntervalCapturePolicy.Tick) {
        enqueue(
            context = context,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            tick = tick,
            initialDelaySeconds = IntervalCapturePolicy.nextDelaySeconds(tick),
        )
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Observable truth: WorkManager's own view of the unique work, as a cold
     * flow. Screen ViewModels observe this instead of keeping optimistic
     * copies of "is it running / how many captured".
     */
    fun observeStatus(context: Context): Flow<IntervalCaptureStatusSnapshot> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map(IntervalCapturePolicy::snapshotOf)
    }

    /** One-shot variant for request/response callers (Web API status route). */
    fun getStatus(context: Context): IntervalCaptureStatusSnapshot {
        return IntervalCapturePolicy.snapshotOf(
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
        )
    }

    private fun enqueue(
        context: Context,
        policy: ExistingWorkPolicy,
        tick: IntervalCapturePolicy.Tick,
        initialDelaySeconds: Long,
    ) {
        val requestBuilder = OneTimeWorkRequestBuilder<IntervalCaptureWorker>()
            .setInputData(IntervalCapturePolicy.inputData(tick))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            // The worker's retry verdict rides on this: short and linear so a
            // contended camera session clears within the interval, not after it.
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)

        if (initialDelaySeconds > 0) {
            requestBuilder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            policy,
            requestBuilder.build(),
        )
    }

    const val WORK_NAME = "interval_capture"

    /** Retry backoff for a tick whose capture failed (the policy's RETRY verdict). */
    const val BACKOFF_SECONDS = 10L
}

typealias IntervalCaptureStatusSnapshot = IntervalCapturePolicy.StatusSnapshot

package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.ForegroundNotifications
import com.raulshma.lenscast.capture.model.TimelapseCompletionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One tick of interval capture. The photo choreography itself (acquire →
 * take → history → release) is [PhotoCaptureManager]'s; this worker only
 * sequences ticks, shows the progress notification, and schedules the next
 * tick. Failed attempts consult [IntervalCapturePolicy.retryVerdict]: early
 * attempts retry with WorkManager backoff, past the bound the tick is
 * skipped and the series continues.
 */
class IntervalCaptureWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MainApplication

        if (isStopped) return Result.success()

        val tick = IntervalCapturePolicy.readTick(inputData)

        ForegroundNotifications.createChannel(applicationContext, CHANNEL_ID, "Interval Capture")
        setForeground(createForegroundInfo(tick.completedCaptures, tick.totalCaptures))

        if (IntervalCapturePolicy.isComplete(tick)) {
            Log.d(TAG, "Interval capture complete: ${tick.completedCaptures}/${tick.totalCaptures}")
            maybeAssembleTimelapse(app, tick.completedCaptures)
            return Result.success(IntervalCapturePolicy.progressData(tick.completedCaptures))
        }

        return try {
            val captured = captureImage(app, tick.flashMode)
            if (captured) {
                val advanced = IntervalCapturePolicy.countCapture(tick)
                setProgress(IntervalCapturePolicy.progressData(advanced.completedCaptures))
                Log.d(TAG, "Interval capture: ${advanced.completedCaptures}/${advanced.totalCaptures}")

                if (IntervalCapturePolicy.isComplete(advanced)) {
                    Log.d(TAG, "All captures complete")
                    maybeAssembleTimelapse(app, advanced.completedCaptures)
                    return Result.success(IntervalCapturePolicy.progressData(advanced.completedCaptures))
                }

                if (!isStopped) {
                    IntervalCaptureScheduler.scheduleNext(
                        context = applicationContext,
                        tick = advanced,
                    )
                }
                Result.success(IntervalCapturePolicy.progressData(advanced.completedCaptures))
            } else {
                failedTick(tick, error = null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interval capture failed", e)
            failedTick(tick, error = e)
        }
    }

    /**
     * A failed capture attempt. The series lives in the worker's own
     * scheduling — each successful run enqueues the next tick — so a
     * [IntervalCapturePolicy.RetryVerdict.GIVE_UP] must keep that chain alive:
     * the tick is skipped (progress unchanged), the next tick is scheduled,
     * and this run ends successfully, which also tears down its foreground
     * notification. Retrying is WorkManager's job, backed by the scheduler's
     * linear backoff criteria.
     */
    private suspend fun failedTick(
        tick: IntervalCapturePolicy.Tick,
        error: Exception?,
    ): Result {
        when (IntervalCapturePolicy.retryVerdict(runAttemptCount)) {
            IntervalCapturePolicy.RetryVerdict.RETRY -> {
                Log.w(TAG, "Capture attempt ${runAttemptCount + 1} failed; retrying with backoff", error)
                return Result.retry()
            }
            IntervalCapturePolicy.RetryVerdict.GIVE_UP -> {
                Log.w(TAG, "Capture still failing after ${runAttemptCount + 1} attempts; skipping this tick", error)
                setProgress(IntervalCapturePolicy.progressData(tick.completedCaptures))
                if (!isStopped) {
                    IntervalCaptureScheduler.scheduleNext(
                        context = applicationContext,
                        tick = tick,
                    )
                }
                return Result.success()
            }
        }
    }

    /**
     * The completion→assemble handoff: the pure [TimelapseCompletionPolicy]
     * decides (persisted toggle on, at least the assembler's floor of interval
     * photos available), and the shared [TimelapseComposer] runs the assembly
     * — its MediaStore + encryption publish seam, never the legacy plaintext
     * folder. [seriesCaptures] is THIS series' successful capture count — the
     * history store cannot distinguish interval photos from manual ones, so
     * the worker's own tick counter is the only honest source. Runs on the
     * worker's own background thread (MediaCodec work); a failure is logged
     * and swallowed — the photos are safe either way.
     */
    private suspend fun maybeAssembleTimelapse(app: MainApplication, seriesCaptures: Int) {
        val verdict = TimelapseCompletionPolicy.onSeriesComplete(
            autoTimelapseEnabled = app.settingsDataStore.autoTimelapseEnabled.value,
            availablePhotos = seriesCaptures,
        )
        when (verdict) {
            is TimelapseCompletionPolicy.Verdict.Assemble -> {
                Log.d(TAG, "Auto-timelapse: assembling from ${verdict.photoCount} photos")
                withContext(Dispatchers.IO) {
                    app.timelapseComposer.assembleLatest(verdict.photoCount)
                }.let { result ->
                    when (result) {
                        is TimelapseComposer.Result.Saved ->
                            Log.d(TAG, "Auto-timelapse saved: ${result.fileName} (${result.frameCount} frames)")
                        is TimelapseComposer.Result.NotSaved ->
                            Log.w(TAG, "Auto-timelapse skipped/failed: ${result.reason} ${result.detail}")
                    }
                }
            }
            TimelapseCompletionPolicy.Verdict.Skip -> Unit
        }
    }

    private suspend fun captureImage(app: MainApplication, flashMode: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val fileName = app.photoCaptureManager.captureToGallery(
                    flashMode = IntervalCapturePolicy.resolveFlashMode(flashMode),
                    onSaved = { filePath, _ ->
                        Log.d(TAG, "Photo saved: $filePath")
                        continuation.resume(true)
                    },
                    onError = { exception ->
                        Log.e(TAG, "Photo capture failed", exception)
                        continuation.resume(false)
                    },
                )
                if (fileName == null) {
                    // Use case could not be acquired; no callback will fire.
                    continuation.resume(false)
                }
            }
        }

    private fun createForegroundInfo(completedCaptures: Int, totalCaptures: Int): ForegroundInfo {
        val notification = ForegroundNotifications.build(
            applicationContext,
            CHANNEL_ID,
            "LensCast Interval Capture",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures, totalCaptures),
            ongoing = true,
        )

        return ForegroundNotifications.buildCameraForegroundInfo(
            ForegroundNotifications.INTERVAL_CAPTURE_NOTIFICATION_ID,
            notification,
        )
    }

    companion object {
        private const val TAG = "IntervalCapture"
        private const val CHANNEL_ID = "interval_capture_channel"
    }
}

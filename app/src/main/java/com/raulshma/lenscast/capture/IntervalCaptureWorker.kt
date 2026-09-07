package com.raulshma.lenscast.capture

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.ForegroundNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One tick of interval capture. The photo choreography itself (acquire →
 * take → history → release) is [PhotoCaptureManager]'s; this worker only
 * sequences ticks, shows the progress notification, and schedules the next
 * tick.
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
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interval capture failed", e)
            Result.retry()
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
        val contentText = if (totalCaptures > 0) {
            "Capturing photo ${completedCaptures + 1} of $totalCaptures"
        } else {
            "Capturing interval photo"
        }
        val notification = ForegroundNotifications.build(
            applicationContext,
            CHANNEL_ID,
            "LensCast Interval Capture",
            contentText,
            ongoing = true,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "IntervalCapture"
        private const val CHANNEL_ID = "interval_capture_channel"
        private const val NOTIFICATION_ID = 1002
    }
}

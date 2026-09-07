package com.raulshma.lenscast.capture

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.ForegroundNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
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

        val intervalSeconds = inputData.getLong(KEY_INTERVAL_SECONDS, 1L).coerceAtLeast(1L)
        val totalCaptures = inputData.getInt(KEY_TOTAL_CAPTURES, 0)
        val completed = inputData.getInt(KEY_COMPLETED_CAPTURES, 0)
        val flashMode = inputData.getString(KEY_FLASH_MODE) ?: "OFF"

        ForegroundNotifications.createChannel(applicationContext, CHANNEL_ID, "Interval Capture")
        setForeground(createForegroundInfo(completed, totalCaptures))

        if (totalCaptures > 0 && completed >= totalCaptures) {
            Log.d(TAG, "Interval capture complete: $completed/$totalCaptures")
            return Result.success(progressData(completed))
        }

        return try {
            val captured = captureImage(app, flashMode)
            if (captured) {
                val newCompleted = completed + 1
                setProgress(progressData(newCompleted))
                Log.d(TAG, "Interval capture: $newCompleted/$totalCaptures")

                if (totalCaptures > 0 && newCompleted >= totalCaptures) {
                    Log.d(TAG, "All captures complete")
                    return Result.success(progressData(newCompleted))
                }

                if (!isStopped) {
                    IntervalCaptureScheduler.scheduleNext(
                        context = applicationContext,
                        intervalSeconds = intervalSeconds,
                        totalCaptures = totalCaptures,
                        flashMode = flashMode,
                        completedCaptures = newCompleted,
                    )
                }
                Result.success(progressData(newCompleted))
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
                    flashMode = resolveFlashMode(flashMode),
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
        const val KEY_INTERVAL_SECONDS = "interval_seconds"
        const val KEY_TOTAL_CAPTURES = "total_captures"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_COMPLETED_CAPTURES = "completed_captures"

        private const val TAG = "IntervalCapture"
        private const val CHANNEL_ID = "interval_capture_channel"
        private const val NOTIFICATION_ID = 1002

        private fun resolveFlashMode(flashMode: String): Int =
            when (flashMode.uppercase(Locale.US)) {
                "ON" -> ImageCapture.FLASH_MODE_ON
                "AUTO" -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

        private fun progressData(completedCaptures: Int): Data {
            return Data.Builder()
                .putInt(KEY_COMPLETED_CAPTURES, completedCaptures.coerceAtLeast(0))
                .build()
        }
    }
}

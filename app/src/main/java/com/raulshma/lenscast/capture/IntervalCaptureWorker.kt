package com.raulshma.lenscast.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.raulshma.lenscast.MainActivity
import com.raulshma.lenscast.MainApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class IntervalCaptureWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MainApplication
        val cameraService = app.cameraService

        if (isStopped) return Result.success()

        val intervalSeconds = inputData.getLong(KEY_INTERVAL_SECONDS, 1L).coerceAtLeast(1L)
        val totalCaptures = inputData.getInt(KEY_TOTAL_CAPTURES, 0)
        val completed = inputData.getInt(KEY_COMPLETED_CAPTURES, 0)
        val imageQuality = inputData.getInt(KEY_IMAGE_QUALITY, 90)
        val flashMode = inputData.getString(KEY_FLASH_MODE) ?: "OFF"

        createNotificationChannel()
        setForeground(createForegroundInfo(completed, totalCaptures))

        if (totalCaptures > 0 && completed >= totalCaptures) {
            Log.d(TAG, "Interval capture complete: $completed/$totalCaptures")
            return Result.success(progressData(completed))
        }

        return try {
            val captured = captureImage(
                app = app,
                cameraService = cameraService,
                imageQuality = imageQuality,
                flashMode = flashMode,
            )
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
                        imageQuality = imageQuality,
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

    private suspend fun captureImage(
        app: MainApplication,
        cameraService: com.raulshma.lenscast.camera.CameraService,
        imageQuality: Int,
        flashMode: String,
    ): Boolean = withContext(Dispatchers.Main) {
        val imageCapture = cameraService.acquirePhotoCapture() ?: return@withContext false
        imageCapture.flashMode = when (flashMode.uppercase(Locale.US)) {
            "ON" -> ImageCapture.FLASH_MODE_ON
            "AUTO" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        Log.d(TAG, "Capturing interval photo with quality=$imageQuality flash=$flashMode")

        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "IMG_${dateFormat.format(Date())}.jpg"

        suspendCancellableCoroutine { continuation ->
            val handleSaved: (String, Long) -> Unit = { filePath, fileSizeBytes ->
                val entry = app.captureHistoryStore.createPhotoEntry(
                    fileName = fileName,
                    filePath = filePath,
                    fileSizeBytes = fileSizeBytes,
                )
                app.captureHistoryStore.add(entry)
                cameraService.releasePhotoCapture()
                Log.d(TAG, "Photo saved: $filePath")
                continuation.resume(true)
            }

            val handleError: (ImageCaptureException) -> Unit = { exception ->
                cameraService.releasePhotoCapture()
                Log.e(TAG, "Photo capture failed", exception)
                continuation.resume(false)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LensCast")
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    app.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(app),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val uriString = output.savedUri?.toString().orEmpty()
                            handleSaved(uriString, 0L)
                        }

                        override fun onError(exception: ImageCaptureException) = handleError(exception)
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "LensCast"
                )
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, fileName)
                val outputOptions = ImageCapture.OutputFileOptions
                    .Builder(outputFile)
                    .build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(app),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            handleSaved(outputFile.absolutePath, outputFile.length())
                        }

                        override fun onError(exception: ImageCaptureException) = handleError(exception)
                    },
                )
            }
        }
    }

    private fun createForegroundInfo(completedCaptures: Int, totalCaptures: Int): ForegroundInfo {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = if (totalCaptures > 0) {
            "Capturing photo ${completedCaptures + 1} of $totalCaptures"
        } else {
            "Capturing interval photo"
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("LensCast Interval Capture")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
                .setContentTitle("LensCast Interval Capture")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Interval Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_INTERVAL_SECONDS = "interval_seconds"
        const val KEY_TOTAL_CAPTURES = "total_captures"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_COMPLETED_CAPTURES = "completed_captures"

        private const val TAG = "IntervalCapture"
        private const val CHANNEL_ID = "interval_capture_channel"
        private const val NOTIFICATION_ID = 1002

        private fun progressData(completedCaptures: Int): Data {
            return Data.Builder()
                .putInt(KEY_COMPLETED_CAPTURES, completedCaptures.coerceAtLeast(0))
                .build()
        }
    }
}

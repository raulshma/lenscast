package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
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

        val totalCaptures = inputData.getInt(KEY_TOTAL_CAPTURES, 0)
        val completed = inputData.getInt(KEY_COMPLETED_CAPTURES, 0)

        if (totalCaptures > 0 && completed >= totalCaptures) {
            Log.d(TAG, "Interval capture complete: $completed/$totalCaptures")
            return Result.success()
        }

        return try {
            val captured = captureImage(app, cameraService)
            if (captured) {
                val newCompleted = completed + 1
                Log.d(TAG, "Interval capture: $newCompleted/$totalCaptures")

                if (totalCaptures > 0 && newCompleted >= totalCaptures) {
                    Log.d(TAG, "All captures complete")
                    return Result.success()
                }
                Result.success()
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
    ): Boolean = withContext(Dispatchers.Main) {
        val imageCapture = cameraService.acquirePhotoCapture() ?: return@withContext false

        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "IMG_${dateFormat.format(Date())}.jpg"
        val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: app.filesDir
        val outputFile = File(dir, fileName)

        suspendCancellableCoroutine { continuation ->
            val outputOptions = ImageCapture.OutputFileOptions
                .Builder(outputFile)
                .build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(
                        output: ImageCapture.OutputFileResults
                    ) {
                        val entry = app.captureHistoryStore.createPhotoEntry(
                            fileName = fileName,
                            filePath = outputFile.absolutePath,
                            fileSizeBytes = outputFile.length(),
                        )
                        app.captureHistoryStore.add(entry)
                        cameraService.releasePhotoCapture()
                        Log.d(TAG, "Photo saved: ${outputFile.absolutePath}")
                        continuation.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cameraService.releasePhotoCapture()
                        Log.e(TAG, "Photo capture failed", exception)
                        continuation.resume(false)
                    }
                },
            )
        }
    }

    companion object {
        const val KEY_INTERVAL_SECONDS = "interval_seconds"
        const val KEY_TOTAL_CAPTURES = "total_captures"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_COMPLETED_CAPTURES = "completed_captures"

        private const val TAG = "IntervalCapture"
    }
}

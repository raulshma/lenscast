package com.raulshma.lenscast.capture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared utility for photo capture and save logic.
 * Eliminates duplication across CameraViewModel, CaptureViewModel,
 * WebApiController, and IntervalCaptureWorker.
 */
object PhotoCaptureHelper {

    private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private const val PHOTO_DIR_NAME = "LensCast"

    fun generateFileName(): String = "IMG_${DATE_FORMAT.format(Date())}.jpg"

    fun takePhoto(
        context: Context,
        imageCapture: ImageCapture,
        fileName: String,
        onSaved: (filePath: String, fileSizeBytes: Long) -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            captureWithMediaStore(context, imageCapture, fileName, onSaved, onError)
        } else {
            captureWithFile(context, imageCapture, fileName, onSaved, onError)
        }
    }

    private fun captureWithMediaStore(
        context: Context,
        imageCapture: ImageCapture,
        fileName: String,
        onSaved: (filePath: String, fileSizeBytes: Long) -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$PHOTO_DIR_NAME")
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(output.savedUri?.toString().orEmpty(), 0L)
                }

                override fun onError(exception: ImageCaptureException) = onError(exception)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun captureWithFile(
        context: Context,
        imageCapture: ImageCapture,
        fileName: String,
        onSaved: (filePath: String, fileSizeBytes: Long) -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            PHOTO_DIR_NAME,
        )
        if (!dir.exists()) dir.mkdirs()
        val outputFile = File(dir, fileName)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(outputFile.absolutePath, outputFile.length())
                }

                override fun onError(exception: ImageCaptureException) = onError(exception)
            },
        )
    }
}

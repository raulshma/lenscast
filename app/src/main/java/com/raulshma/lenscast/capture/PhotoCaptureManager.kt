package com.raulshma.lenscast.capture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the photo-capture choreography: acquire the use case, take the photo,
 * record it in the capture history, release the use case. The camera screen,
 * the capture screen, and the Web API all go through this one interface.
 */
class PhotoCaptureManager(
    private val context: Context,
    private val cameraService: com.raulshma.lenscast.camera.CameraService,
    private val captureHistoryStore: CaptureHistoryStore,
) {

    /**
     * Capture a photo into the gallery (MediaStore / Pictures/LensCast) and
     * record it in history. Returns the generated file name, or null when the
     * camera use case could not be acquired. [onSaved]/[onError] fire
     * asynchronously. [flashMode] is an [ImageCapture.FLASH_MODE_*] value.
     */
    fun captureToGallery(
        flashMode: Int = ImageCapture.FLASH_MODE_OFF,
        onSaved: (filePath: String, fileSizeBytes: Long) -> Unit = { _, _ -> },
        onError: (ImageCaptureException) -> Unit = {},
    ): String? {
        // CameraX use-case work must run on Main. UI callers are already
        // there — hop directly (a Dispatchers.Main dispatch from a blocked
        // Main looper would deadlock). Off-Main callers (server threads) go
        // through the same bounded Main hop as snapshots, via runBlocking.
        val imageCapture = if (android.os.Looper.getMainLooper().isCurrentThread) {
            cameraService.acquirePhotoCapture()
        } else {
            runBlocking { acquireUseCase() }
        }
        if (imageCapture == null) {
            return null
        }
        imageCapture.flashMode = flashMode
        val fileName = generateFileName()
        val destination = destinationFor(fileName)
        imageCapture.takePicture(
            destination.outputOptions,
            ContextCompat.getMainExecutor(context),
            takePictureCallback(
                destination,
                onSaved = { filePath, fileSizeBytes ->
                    recordInHistory(fileName, filePath, fileSizeBytes)
                    cameraService.releasePhotoCapture()
                    onSaved(filePath, fileSizeBytes)
                },
                onError = { exception ->
                    cameraService.releasePhotoCapture()
                    onError(exception)
                },
            ),
        )
        return fileName
    }

    sealed class SnapshotResult {
        data class Success(val data: ByteArray, val savedPath: String? = null) : SnapshotResult()
        data class Error(val message: String) : SnapshotResult()
    }

    /**
     * High-resolution snapshot returning the JPEG bytes. With
     * [saveToDisk] the photo also lands in the gallery and history.
     */
    suspend fun captureSnapshot(saveToDisk: Boolean): SnapshotResult {
        return try {
            val imageCapture = acquireUseCase()
                ?: return SnapshotResult.Error("Camera not available")

            try {
                if (saveToDisk) {
                    captureSnapshotToDisk(imageCapture)
                } else {
                    captureSnapshotToMemory(imageCapture)
                }
            } finally {
                withContext(Dispatchers.Main) { cameraService.releasePhotoCapture() }
            }
        } catch (e: ImageCaptureException) {
            SnapshotResult.Error("Snapshot failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "High-res snapshot failed", e)
            SnapshotResult.Error("Snapshot error: ${e.message}")
        }
    }

    /**
     * The single acquire seam: a bounded hop onto Main. Both the gallery
     * path (off-Main callers) and the snapshot path funnel through here.
     */
    private suspend fun acquireUseCase(): ImageCapture? =
        withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { cameraService.acquirePhotoCapture() }
        }

    private suspend fun captureSnapshotToDisk(imageCapture: ImageCapture): SnapshotResult {
        val fileName = generateFileName()
        val (filePath, fileSizeBytes) = takePictureAwait(imageCapture, destinationFor(fileName))
            ?: return SnapshotResult.Error("Snapshot timed out")

        recordInHistory(fileName, filePath, fileSizeBytes)
        val bytes = withContext(Dispatchers.IO) { loadCapturedBytes(filePath) }
            ?: return SnapshotResult.Error("No image data returned")
        return SnapshotResult.Success(bytes, filePath)
    }

    private suspend fun captureSnapshotToMemory(imageCapture: ImageCapture): SnapshotResult {
        val tempFile = File.createTempFile("lenscast_snapshot_", ".jpg", context.cacheDir)
        return try {
            val saved = takePictureAwait(imageCapture, FileDestination(tempFile))
                ?: return SnapshotResult.Error("Snapshot timed out")
            val bytes = withContext(Dispatchers.IO) { tempFile.readBytes() }
            SnapshotResult.Success(bytes)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * The single picture-taking primitive: one callback shape, one timeout,
     * serving both disk and memory destinations.
     */
    private suspend fun takePictureAwait(
        imageCapture: ImageCapture,
        destination: PhotoDestination,
    ): Pair<String, Long>? =
        withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                imageCapture.takePicture(
                    destination.outputOptions,
                    ContextCompat.getMainExecutor(context),
                    takePictureCallback(
                        destination,
                        onSaved = { filePath, fileSizeBytes ->
                            cont.resume(filePath to fileSizeBytes)
                        },
                        onError = { cont.resumeWithException(it) },
                    ),
                )
            }
        }

    private fun takePictureCallback(
        destination: PhotoDestination,
        onSaved: (filePath: String, fileSizeBytes: Long) -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) = object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            onSaved(destination.savedPath(output), destination.savedSize(output))
        }

        override fun onError(exception: ImageCaptureException) = onError(exception)
    }

    /** Where a photo lands, and how a saved result maps back to path + size. */
    private sealed interface PhotoDestination {
        val outputOptions: ImageCapture.OutputFileOptions
        fun savedPath(output: ImageCapture.OutputFileResults): String
        fun savedSize(output: ImageCapture.OutputFileResults): Long
    }

    private inner class MediaStoreDestination(fileName: String) : PhotoDestination {
        override val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/$PHOTO_DIR_NAME",
                )
            },
        ).build()

        override fun savedPath(output: ImageCapture.OutputFileResults): String =
            output.savedUri?.toString().orEmpty()

        // MediaStore reports no size at save time — history records 0, as before.
        override fun savedSize(output: ImageCapture.OutputFileResults): Long = 0L
    }

    private class FileDestination(val file: File) : PhotoDestination {
        override val outputOptions: ImageCapture.OutputFileOptions =
            ImageCapture.OutputFileOptions.Builder(file).build()

        override fun savedPath(output: ImageCapture.OutputFileResults): String =
            file.absolutePath

        override fun savedSize(output: ImageCapture.OutputFileResults): Long =
            file.length()
    }

    private fun destinationFor(fileName: String): PhotoDestination =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreDestination(fileName)
        } else {
            legacyFileDestination(fileName)
        }

    @Suppress("DEPRECATION")
    private fun legacyFileDestination(fileName: String): PhotoDestination {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            PHOTO_DIR_NAME,
        )
        if (!dir.exists()) dir.mkdirs()
        return FileDestination(File(dir, fileName))
    }

    private fun recordInHistory(fileName: String, filePath: String, fileSizeBytes: Long) {
        val entry = captureHistoryStore.createPhotoEntry(
            fileName = fileName,
            filePath = filePath,
            fileSizeBytes = fileSizeBytes,
        )
        captureHistoryStore.add(entry)
    }

    private fun loadCapturedBytes(filePath: String): ByteArray? {
        return try {
            if (filePath.startsWith("content://")) {
                val uri = android.net.Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } else {
                File(filePath).takeIf { it.exists() }?.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load captured bytes", e)
            null
        }
    }

    companion object {
        private const val TAG = "PhotoCaptureManager"
        private const val ACQUIRE_TIMEOUT_MS = 2_000L
        private const val SNAPSHOT_TIMEOUT_MS = 5_000L
        private const val PHOTO_DIR_NAME = "LensCast"

        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        internal fun generateFileName(): String = "IMG_${DATE_FORMAT.format(Date())}.jpg"
    }
}

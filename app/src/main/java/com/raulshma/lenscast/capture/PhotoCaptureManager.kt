package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
        // through a bounded Main hop.
        val imageCapture = if (android.os.Looper.getMainLooper().isCurrentThread) {
            cameraService.acquirePhotoCapture()
        } else {
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { cameraService.acquirePhotoCapture() }
                }
            }
        }
        if (imageCapture == null) {
            return null
        }
        imageCapture.flashMode = flashMode
        val fileName = PhotoCaptureHelper.generateFileName()
        PhotoCaptureHelper.takePhoto(
            context, imageCapture, fileName,
            onSaved = { filePath, fileSizeBytes ->
                recordInHistory(fileName, filePath, fileSizeBytes)
                cameraService.releasePhotoCapture()
                onSaved(filePath, fileSizeBytes)
            },
            onError = { exception ->
                cameraService.releasePhotoCapture()
                onError(exception)
            },
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
            val imageCapture = withTimeoutOrNull(ACQUIRE_TIMEOUT_MS) {
                withContext(Dispatchers.Main) { cameraService.acquirePhotoCapture() }
            } ?: return SnapshotResult.Error("Camera not available")

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

    private suspend fun captureSnapshotToDisk(imageCapture: ImageCapture): SnapshotResult {
        val fileName = PhotoCaptureHelper.generateFileName()
        val saved = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                PhotoCaptureHelper.takePhoto(
                    context, imageCapture, fileName,
                    onSaved = { filePath, fileSizeBytes ->
                        cont.resume(Triple(filePath, fileSizeBytes, null))
                    },
                    onError = { cont.resumeWithException(it) },
                )
            }
        }
        val (filePath, fileSizeBytes, _) = saved
            ?: return SnapshotResult.Error("Snapshot timed out")

        recordInHistory(fileName, filePath, fileSizeBytes)
        val bytes = withContext(Dispatchers.IO) { loadCapturedBytes(filePath) }
            ?: return SnapshotResult.Error("No image data returned")
        return SnapshotResult.Success(bytes, filePath)
    }

    private suspend fun captureSnapshotToMemory(imageCapture: ImageCapture): SnapshotResult {
        val tempFile = File.createTempFile("lenscast_snapshot_", ".jpg", context.cacheDir)
        return try {
            val saved = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
                    imageCapture.takePicture(
                        outputOptions,
                        androidx.core.content.ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                cont.resume(output)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                cont.resumeWithException(exception)
                            }
                        },
                    )
                }
            }
            if (saved == null) {
                SnapshotResult.Error("Snapshot timed out")
            } else {
                val bytes = withContext(Dispatchers.IO) { tempFile.readBytes() }
                SnapshotResult.Success(bytes)
            }
        } finally {
            tempFile.delete()
        }
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
    }
}

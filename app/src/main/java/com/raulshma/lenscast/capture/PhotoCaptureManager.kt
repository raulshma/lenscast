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
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the photo-capture choreography: acquire the use case, take the photo,
 * record it in the capture history, release the use case. The camera screen,
 * the capture screen, and the Web API all go through this one interface.
 *
 * The write seam: with media encryption off, photos land in MediaStore
 * directly (CameraX writes the plaintext). With [encryptionEnabled] on, the
 * capture lands in a cacheDir temp file and [EncryptedMediaSink] streams it
 * into the same MediaStore row encrypted — one seam per state, both served
 * transparently by [CaptureMediaResolver] afterwards. Pre-Q devices keep the
 * legacy public-folder file path in both modes (ciphertext in the shared
 * Pictures folder would be unreadable noise to other apps).
 */
class PhotoCaptureManager(
    private val context: Context,
    private val cameraService: com.raulshma.lenscast.camera.CameraService,
    private val captureHistoryStore: CaptureHistoryStore,
    /** Live media-encryption gate, read per capture so a toggle needs no restart. */
    private val encryptionEnabled: () -> Boolean = { false },
    /** The key seam for the encrypted destination; required only while encrypting. */
    private val mediaKeyProvider: com.raulshma.lenscast.core.MediaCrypto.KeyProvider? = null,
) {

    // Read-back goes through the same transparent decrypt as every other
    // consumer, so a snapshot saved while encryption is on still returns
    // plaintext JPEG bytes to the /snapshot route.
    private val mediaResolver = CaptureMediaResolver(context.contentResolver, mediaKeyProvider)

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
            try {
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
            } finally {
                // The encrypted destination's temp file (and any later temp
                // shape) dies with the attempt; a success already consumed it.
                destination.cleanup()
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

        override fun onError(exception: ImageCaptureException) {
            // Drop the encrypted path's temp file (no-op elsewhere) before
            // the caller's error handler runs.
            destination.cleanup()
            onError(exception)
        }
    }

    /** Where a photo lands, and how a saved result maps back to path + size. */
    private sealed interface PhotoDestination {
        val outputOptions: ImageCapture.OutputFileOptions
        fun savedPath(output: ImageCapture.OutputFileResults): String
        fun savedSize(output: ImageCapture.OutputFileResults): Long

        /** Frees any intermediate artifact (the encrypted path's temp file). */
        fun cleanup() {}
    }

    private inner class MediaStoreDestination(fileName: String) : PhotoDestination {
        override val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, CaptureMediaFormat.MIME_PHOTO)
                put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureMediaFormat.PHOTOS_WRITE_RELATIVE_PATH)
            },
        ).build()

        override fun savedPath(output: ImageCapture.OutputFileResults): String =
            output.savedUri?.toString().orEmpty()

        // Query MediaStore SIZE post-save instead of recording 0 ("Unknown size").
        override fun savedSize(output: ImageCapture.OutputFileResults): Long {
            val uri = output.savedUri ?: return 0L
            return try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
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
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> legacyFileDestination(fileName)
            encryptionEnabled() && mediaKeyProvider != null ->
                EncryptedMediaStoreDestination(fileName)
            else -> MediaStoreDestination(fileName)
        }

    /**
     * The encrypted variant of the MediaStore destination: CameraX writes the
     * photo into a cacheDir temp file, and [savedPath] — the one post-save
     * hook in the callback ladder — promotes it into MediaStore through the
     * [EncryptedMediaSink]. A failed promotion returns a blank path (the
     * history merge tolerates it, exactly like a provider rejection today).
     */
    private inner class EncryptedMediaStoreDestination(fileName: String) : PhotoDestination {
        private val tempFile = File.createTempFile("lenscast_enc_", ".jpg", context.cacheDir)
        private val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, CaptureMediaFormat.MIME_PHOTO)
            put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureMediaFormat.PHOTOS_WRITE_RELATIVE_PATH)
        }

        @Volatile
        private var promoted: EncryptedMediaSink.SavedMedia? = null

        override val outputOptions: ImageCapture.OutputFileOptions =
            ImageCapture.OutputFileOptions.Builder(tempFile).build()

        override fun savedPath(output: ImageCapture.OutputFileResults): String {
            promote()
            return promoted?.uriString.orEmpty()
        }

        // The at-rest (ciphertext) size the row reports post-write.
        override fun savedSize(output: ImageCapture.OutputFileResults): Long =
            promoted?.storedSizeBytes ?: 0L

        override fun cleanup() {
            tempFile.delete()
        }

        private fun promote() {
            if (promoted != null) return
            val provider = mediaKeyProvider ?: return
            val sink = EncryptedMediaSink(context.contentResolver, provider)
            promoted = runCatching {
                sink.writeFileToMediaStore(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                    tempFile,
                )
            }.getOrNull()
            tempFile.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFileDestination(fileName: String): PhotoDestination {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            CaptureMediaFormat.PHOTO_DIR_NAME,
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
        enqueueBackup(filePath)
    }

    /** WebDAV backup, when enabled: one WorkManager request per capture. */
    private fun enqueueBackup(filePath: String) {
        runCatching { BackupWorker.enqueue(context, filePath) }
    }

    private fun loadCapturedBytes(filePath: String): ByteArray? {
        return try {
            // One scheme ladder: content URIs open through the resolver,
            // file URIs and plain paths from disk.
            mediaResolver.openStream(filePath)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load captured bytes", e)
            null
        }
    }

    companion object {
        private const val TAG = "PhotoCaptureManager"
        private const val ACQUIRE_TIMEOUT_MS = 2_000L
        private const val SNAPSHOT_TIMEOUT_MS = 5_000L

        internal fun generateFileName(): String = MediaFileNaming.photoName(Date())
    }
}

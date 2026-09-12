package com.raulshma.lenscast.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.capture.model.RecordingTrigger
import com.raulshma.lenscast.data.CaptureHistoryStore
import java.io.File
import java.util.Date

/**
 * The one timelapse assembly pipeline, shared by the manual capture-screen
 * path and the interval worker's on-completion auto-assembly: select sources
 * (pure [TimelapseAssembler.selectSources]), decrypt them into cacheDir
 * frames through the capture-media resolver (so encrypted-at-rest interval
 * photos work like any other read), assemble to a temp MP4, then publish
 * through the same MediaStore + encryption seam every capture uses —
 * [EncryptedMediaSink] while media encryption is on, a plain resolver insert
 * otherwise. The one documented plaintext exception is pre-Q: below
 * Android 10 the output is a plain file in the public Movies folder (the
 * OS has no writable MediaStore row for third-party writes there). The
 * history entry and the backup enqueue ride the same write.
 *
 * All Android/IO work; the decisions (source count, floors) stay in the pure
 * assembler and [com.raulshma.lenscast.capture.model.TimelapseCompletionPolicy].
 */
class TimelapseComposer(
    private val context: Context,
    private val captureHistoryStore: CaptureHistoryStore,
    /** Live media-encryption gate + key seam, read per assembly. */
    private val encryptionEnabled: () -> Boolean = { false },
    private val mediaKeyProvider: com.raulshma.lenscast.core.MediaCrypto.KeyProvider? = null,
) {

    sealed interface Result {
        /** Saved: the output's file name and the frame count it was built from. */
        data class Saved(val fileName: String, val frameCount: Int) : Result

        /** Skipped/failed with a human-readable reason (already localized by the caller). */
        data class NotSaved(val reason: Reason, val detail: String = "") : Result
    }

    enum class Reason { NOT_ENOUGH_SOURCES, UNREADABLE_SOURCES, ASSEMBLY_FAILED, PUBLISH_FAILED }

    /**
     * Assembles the newest [lastN] photos at [fps]. Runs on the caller's
     * worker thread (long MediaCodec work — never Main). Serialized: the
     * manual path and the interval worker share this instance and the
     * decrypted-frames cache dir, so concurrent assemblies would clear each
     * other's frames. A failure is logged and swallowed — the photos are
     * safe either way.
     */
    fun assembleLatest(lastN: Int, fps: Int = 30): Result = synchronized(this) {
        val photos = TimelapseAssembler.selectSources(captureHistoryStore.history.value, lastN)
        if (photos.size < TimelapseAssembler.MIN_SOURCES) {
            return Result.NotSaved(Reason.NOT_ENOUGH_SOURCES, "have ${photos.size}")
        }

        // Keyed with the app's media key so encrypted-at-rest interval photos
        // decrypt into timelapse frames like any other read.
        val resolver = CaptureMediaResolver(
            context.contentResolver,
            mediaKeyProvider,
        )
        val tmpDir = File(context.cacheDir, "timelapse_frames").apply { mkdirs() }
        val result: Result = try {
            tmpDir.listFiles()?.forEach { it.delete() }
            var index = 0
            for (entry in photos) {
                val bytes = try {
                    resolver.openStream(entry.filePath)?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                } ?: continue
                val frame = File(tmpDir, MediaFileNaming.timelapseFrameName(index))
                try {
                    frame.writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Timelapse frame write failed for ${entry.fileName}", e)
                    continue
                }
                index++
            }
            if (index < TimelapseAssembler.MIN_SOURCES) {
                Result.NotSaved(Reason.UNREADABLE_SOURCES, "read $index")
            } else {
                assembleFrom(tmpDir, index, fps)
            }
        } finally {
            // Decrypted plaintext frames never outlive the assembly attempt.
            tmpDir.listFiles()?.forEach { it.delete() }
        }
        result
    }

    /** Muxes the decrypted frames in [tmpDir] and publishes the result. */
    private fun assembleFrom(tmpDir: File, frameCount: Int, fps: Int): Result {
        val outName = MediaFileNaming.timelapseName(Date())
        val outFile = File.createTempFile("lenscast_timelapse_", ".mp4", context.cacheDir)
        return try {
            val ok = TimelapseAssembler.assemble(tmpDir, outFile, fps)
            if (!ok) {
                return Result.NotSaved(Reason.ASSEMBLY_FAILED)
            }
            val published = publish(outName, outFile)
            if (published == null) {
                Result.NotSaved(Reason.PUBLISH_FAILED)
            } else {
                captureHistoryStore.add(
                    captureHistoryStore.createVideoEntry(
                        fileName = outName,
                        filePath = published.uriString,
                        fileSizeBytes = published.sizeBytes,
                        durationMs = frameCount * 1000L / fps,
                        // An assembled timelapse is interval-capture output;
                        // the timeline attributes it accordingly.
                        trigger = RecordingTrigger.INTERVAL,
                    )
                )
                BackupWorker.enqueue(context, published.uriString)
                Result.Saved(outName, frameCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Timelapse assembly failed", e)
            Result.NotSaved(Reason.ASSEMBLY_FAILED, e.message ?: "")
        } finally {
            outFile.delete()
        }
    }

    /** A landed publish: the MediaStore URI string and the at-rest size. */
    private data class Published(val uriString: String, val sizeBytes: Long)

    /**
     * The publish seam: MediaStore Movies/LensCast row, encrypted through
     * [EncryptedMediaSink] when the toggle is on (and a key exists), plain
     * bytes otherwise — mirroring RecordingService's write ladder. Pre-Q
     * devices fall back to the legacy public folder file path.
     */
    private fun publish(fileName: String, assembled: File): Published? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = CaptureMediaFormat.videoDir(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES,
                )
            ).apply { mkdirs() }
            val target = File(dir, fileName)
            if (!assembled.renameTo(target) && !assembled.copyTo(target, overwrite = true).exists()) {
                return null
            }
            return Published(target.absolutePath, target.length())
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, CaptureMediaFormat.MIME_VIDEO)
            put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureMediaFormat.VIDEOS_WRITE_RELATIVE_PATH)
        }
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        if (encryptionEnabled() && mediaKeyProvider != null) {
            val saved = EncryptedMediaSink(context.contentResolver, mediaKeyProvider)
                .writeFileToMediaStore(collection, values, assembled)
                ?: return null
            return Published(saved.uriString, saved.storedSizeBytes)
        }
        // Hoisted so a failed copy can still remove the half-written row (the
        // EncryptedMediaSink contract: MediaStore never keeps a partial capture).
        var insertedUri: Uri? = null
        return runCatching {
            val uri: Uri = context.contentResolver.insert(collection, values) ?: return null
            insertedUri = uri
            context.contentResolver.openOutputStream(uri)?.use { out ->
                assembled.inputStream().use { it.copyTo(out) }
            } ?: error("MediaStore output stream unavailable")
            val size = context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
            Published(uri.toString(), size)
        }.onFailure { cause ->
            Log.e(TAG, "Timelapse publish failed", cause)
            insertedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "TimelapseComposer"
    }
}

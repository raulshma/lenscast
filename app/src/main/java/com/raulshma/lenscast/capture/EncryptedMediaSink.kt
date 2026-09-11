package com.raulshma.lenscast.capture

import android.content.ContentValues
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.raulshma.lenscast.core.MediaCrypto
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * The one encrypt-on-write seam for capture media: insert the MediaStore row,
 * then stream-encrypt the source bytes into it through
 * [MediaCrypto.encryptTo] — the ciphertext (magic + nonce + GCM body) is the
 * only thing ever at rest. Both producers funnel here: PhotoCaptureManager
 * (photos) writes its CameraX temp file through [writeFileToMediaStore], and
 * RecordingService (videos) streams its finalized temp recording the same
 * way, so the file keeps its honest `.jpg`/`.mp4` extension while the bytes
 * read as ciphertext until the resolver's transparent decrypt opens them.
 *
 * Failure is cleanup: any I/O or crypto error after the insert deletes the
 * row, so MediaStore never holds a half-tagged or zero-byte "encrypted"
 * capture. The plaintext source (a CameraX output temp file in cacheDir)
 * lives only for the duration of the write and is deleted by the caller.
 */
class EncryptedMediaSink(
    private val contentResolver: ContentResolver,
    private val keyProvider: MediaCrypto.KeyProvider,
) {

    /** A landed capture: the MediaStore URI string and the at-rest (ciphertext) size. */
    data class SavedMedia(val uriString: String, val storedSizeBytes: Long)

    /**
     * Inserts into [collection] with [values] and encrypts [file] into the new
     * row. Null (with the row removed) when the key, the insert, or the
     * write failed — the caller reports a failed capture, never a broken one.
     */
    fun writeFileToMediaStore(collection: Uri, values: ContentValues, file: File): SavedMedia? =
        writeToMediaStore(collection, values) {
            if (file.exists()) FileInputStream(file) else null
        }

    /**
     * The stream-shaped core: [openSource] is called once after a successful
     * insert; returning null aborts (row deleted) as a failed write.
     */
    fun writeToMediaStore(
        collection: Uri,
        values: ContentValues,
        openSource: () -> InputStream?,
    ): SavedMedia? {
        val key = runCatching { keyProvider.getOrCreateKey() }
            .onFailure { Log.e(TAG, "Media key unavailable; capture not encrypted", it) }
            .getOrNull() ?: return null
        val uri = runCatching { contentResolver.insert(collection, values) }
            .onFailure { Log.e(TAG, "MediaStore insert failed", it) }
            .getOrNull() ?: return null

        val saved = runCatching {
            val source = openSource() ?: error("Capture source disappeared before encryption")
            source.use { input ->
                contentResolver.openOutputStream(uri)?.use { out ->
                    MediaCrypto.encryptTo(key, out).use { encrypted -> input.copyTo(encrypted) }
                } ?: error("MediaStore output stream unavailable")
            }
            val row = contentResolver.query(uri, arrayOf(SIZE_COLUMN), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
            SavedMedia(uri.toString(), row)
        }.onFailure { cause ->
            Log.e(TAG, "Encrypted capture write failed; removing $uri", cause)
            runCatching { contentResolver.delete(uri, null, null) }
        }.getOrNull()
        return saved
    }

    companion object {
        private const val TAG = "EncryptedMediaSink"

        /** Works for both the Images and Video collections (same MediaColumns value). */
        private const val SIZE_COLUMN = "_size"
    }
}

package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.capture.model.CaptureHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The decrypted-photo cache behind the gallery's Coil loading. Decryption is
 * transparent for stream consumers ([CaptureMediaResolver.openStream]), but
 * Coil decodes from files/URIs — it cannot wrap a cipher stream — so encrypted
 * photos are decrypted once into cacheDir (`cacheDir/media_decrypto/`,
 * `<id>_<name>`) and the cache file becomes Coil's model. Plaintext photos
 * never enter the cache: [ensure] answers null and the caller keeps the
 * original model.
 *
 * Deliberately photos-only. Videos are the wrong shape for this: an mp4's
 * moov atom (and ExoPlayer's random access) wants the whole decrypted file at
 * once, and a 100 MB+ clip per cache entry is not a "small byte cache" —
 * videos instead play decrypted through the resolver's stream
 * ([DecryptingDataSourceFactory] feeds ExoPlayer) and their thumbnails show a
 * placeholder while encryption is on. The documented trade-off: photos fully
 * work, videos play but preview as an icon.
 *
 * Entries are deleted with their capture (the history store's delete hook
 * calls [deleteAll]); the directory itself is OS-clearable cache, so an
 * eviction here costs a re-decrypt, never data.
 */
class DecryptedPhotoCache(
    private val mediaResolver: CaptureMediaResolver,
    /** Injectable for JVM tests; production resolves cacheDir/media_decrypto. */
    private val dir: File,
) {

    /** The production constructor: the app's cache directory. */
    constructor(context: Context, mediaResolver: CaptureMediaResolver) : this(
        mediaResolver,
        File(context.cacheDir, DIR_NAME),
    )

    fun sniffEncrypted(filePath: String): Boolean = mediaResolver.isEncryptedAtRest(filePath)

    /** The cache file for one capture (present or not). */
    fun cacheFile(id: String, fileName: String): File =
        File(dir.apply { mkdirs() }, "${id}_${sanitize(fileName)}")

    /** An existing cache entry only — the synchronous model for a composition. */
    fun peek(id: String, fileName: String): File? = cacheFile(id, fileName).takeIf { it.exists() }

    /**
     * Decrypts [filePath] into the cache when it is encrypted at rest; a
     * plaintext file answers null (nothing to override). Writes through a
     * `.part` file + atomic rename, so a killed mid-decrypt never leaves a
     * truncated image cached as complete.
     */
    suspend fun ensure(id: String, fileName: String, filePath: String): File? =
        withContext(Dispatchers.IO) {
            try {
                // A warm entry answers first — the sniff's 4-byte read only
                // runs for captures not yet cached.
                val target = cacheFile(id, fileName)
                if (target.exists()) return@withContext target
                if (!mediaResolver.isEncryptedAtRest(filePath)) return@withContext null
                val part = File(target.parentFile, target.name + PART_SUFFIX)
                val ok = mediaResolver.openDecryptedStream(filePath)?.use { input ->
                    part.outputStream().use { output -> input.copyTo(output) }
                    part.renameTo(target)
                } ?: false
                if (ok) target else {
                    part.delete()
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Decrypted-photo cache write failed for $fileName", e)
                null
            }
        }

    fun delete(id: String, fileName: String) {
        cacheFile(id, fileName).delete()
    }

    /** The history store's delete hook: every removed capture's cache file goes with it. */
    fun deleteAll(items: List<CaptureHistory>) {
        items.forEach { delete(it.id, it.fileName) }
    }

    private fun sanitize(fileName: String): String =
        fileName.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }
            .joinToString("")
            .take(MAX_NAME_BYTES)

    companion object {
        private const val TAG = "DecryptedPhotoCache"
        private const val DIR_NAME = "media_decrypto"
        private const val PART_SUFFIX = ".part"
        private const val MAX_NAME_BYTES = 96
    }
}

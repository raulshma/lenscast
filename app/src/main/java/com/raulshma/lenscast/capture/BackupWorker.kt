package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.WebDavUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads one captured media file to the user's WebDAV collection. One
 * WorkManager request per file (enqueued by the capture producers), so the
 * app can die, the network can be slow, or the server can be down without
 * losing the upload — WorkManager retries with its backoff policy.
 */
class BackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.applicationContext as? MainApplication
            ?: return@withContext Result.failure()
        val store = app.settingsDataStore

        val filePath = inputData.getString(KEY_FILE_PATH)
        if (filePath.isNullOrBlank()) return@withContext Result.failure()
        if (!store.backupEnabled.value) return@withContext Result.success()
        val url = store.backupWebdavUrl.value.trim()
        if (!url.startsWith("http")) return@withContext Result.failure()

        if (store.backupWifiOnly.value && !app.connectivityMonitor.isWifiConnected.value) {
            Log.d(TAG, "Deferring backup of ${File(filePath).name}: not on Wi-Fi")
            return@withContext Result.retry()
        }

        val uploader = WebDavUploader(
            baseUrl = url,
            username = store.backupWebdavUsername.value,
            password = store.backupWebdavPassword.value,
        )
        val file = File(filePath)
        if (file.exists()) {
            val uploaded = uploader.upload(file)
            return@withContext if (uploaded) {
                Log.d(TAG, "Backed up ${file.name}")
                Result.success()
            } else {
                Result.retry()
            }
        }

        // MediaStore content URIs (recordings) are not files; upload the stream.
        if (filePath.startsWith("content://")) {
            return@withContext try {
                val resolver = applicationContext.contentResolver
                val size = resolver.openAssetFileDescriptor(android.net.Uri.parse(filePath), "r")
                    ?.use { it.length } ?: -1L
                val input = resolver.openInputStream(android.net.Uri.parse(filePath))
                if (input != null && uploader.upload(file.name, size, input)) {
                    Log.d(TAG, "Backed up ${file.name} (content uri)")
                    Result.success()
                } else {
                    input?.close()
                    Result.retry()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Content-uri backup failed: ${e.message}")
                Result.retry()
            }
        }

        // Neither a file nor a content URI: the capture is gone for good, and
        // reporting success would let WorkManager drop it silently.
        Result.failure()
    }

    companion object {
        private const val TAG = "BackupWorker"
        const val KEY_FILE_PATH = "filePath"

        /** The one enqueue seam capture producers call after a successful save. */
        fun enqueue(context: Context, filePath: String) {
            val request = androidx.work.OneTimeWorkRequest.Builder(BackupWorker::class.java)
                .setInputData(androidx.work.Data.Builder().putString(KEY_FILE_PATH, filePath).build())
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }
}

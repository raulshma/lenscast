package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.BackupTarget
import com.raulshma.lenscast.core.BackupTargetPolicy
import com.raulshma.lenscast.core.BackupTargetUploader
import com.raulshma.lenscast.core.BackupUploadSource
import com.raulshma.lenscast.core.TelegramUploader
import com.raulshma.lenscast.core.WebDavBackupTarget
import com.raulshma.lenscast.core.WebDavUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads one captured media file to the configured backup target — WebDAV
 * or Telegram, routed by the pure [BackupTargetPolicy] verdict over the
 * persisted `backupTarget` and each target's credentials. One WorkManager
 * request per file (enqueued by the capture producers), so the app can die,
 * the network can be slow, or the target can be down without losing the
 * upload — WorkManager retries with its backoff policy. Constraints,
 * retries, and the Wi-Fi-only gate are identical across targets.
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

        // The routing verdict: the selected target only when its credentials
        // are configured; null fails this attempt exactly like a missing
        // WebDAV URL always did — no silent cross-target fallback.
        val webdavUrl = store.backupWebdavUrl.value.trim()
        val target = BackupTargetPolicy.resolve(
            target = BackupTargetPolicy.parse(store.backupTarget.value),
            webdavConfigured = webdavUrl.startsWith("http"),
            telegramConfigured = store.telegramBotToken.value.isNotBlank() &&
                store.telegramChatId.value.isNotBlank(),
        ) ?: return@withContext Result.failure()

        if (store.backupWifiOnly.value && !app.connectivityMonitor.isWifiConnected.value) {
            Log.d(TAG, "Deferring backup of ${File(filePath).name}: not on Wi-Fi")
            return@withContext Result.retry()
        }

        val uploader: BackupTargetUploader = when (target) {
            BackupTarget.WEBDAV -> WebDavBackupTarget(
                WebDavUploader(
                    baseUrl = webdavUrl,
                    username = store.backupWebdavUsername.value,
                    password = store.backupWebdavPassword.value,
                ),
            )
            BackupTarget.TELEGRAM -> TelegramUploader(
                botToken = store.telegramBotToken.value.trim(),
                chatId = store.telegramChatId.value.trim(),
            )
        }

        val file = File(filePath)
        val source = when {
            file.exists() -> BackupUploadSource.FileSource(file)
            // MediaStore content URIs (recordings) are not files; the adapter
            // uploads the resolver stream.
            filePath.startsWith("content://") -> BackupUploadSource.ContentSource(
                android.net.Uri.parse(filePath),
                applicationContext.contentResolver,
            )
            // Neither a file nor a content URI: the capture is gone for good, and
            // reporting success would let WorkManager drop it silently.
            else -> return@withContext Result.failure()
        }

        if (uploader.upload(source, file.name)) {
            Log.d(TAG, "Backed up ${file.name}")
            Result.success()
        } else {
            Result.retry()
        }
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

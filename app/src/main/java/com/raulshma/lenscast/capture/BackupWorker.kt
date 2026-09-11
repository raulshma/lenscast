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
import com.raulshma.lenscast.core.MediaCrypto
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
 *
 * Two upload-time behaviors beyond the plain file case: media encrypted at
 * rest is uploaded *decrypted* (a [BackupUploadSource.StreamSource] over the
 * resolver's transparent stream, sized from the plaintext math — a backup
 * that only the device's Keystore could read would not be a backup), and the
 * tamper-response path enqueues [enqueueExpedited] requests that skip the
 * Wi-Fi-only gate and retry on an aggressive linear backoff, because a
 * yanked camera is not coming back to the network.
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

        // The tamper response's bypass: a power-cut expedite uploads over
        // whatever network exists — waiting for Wi-Fi means waiting forever.
        val bypassWifiOnly = inputData.getBoolean(KEY_BYPASS_WIFI_ONLY, false)
        if (!bypassWifiOnly && store.backupWifiOnly.value && !app.connectivityMonitor.isWifiConnected.value) {
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

        // The upload source: plaintext files ride as-is; encrypted-at-rest
        // media (file or content URI) rides the resolver's decrypted stream
        // with the plaintext size, so the remote copy is the real capture.
        val resolver = com.raulshma.lenscast.capture.CaptureMediaResolver(
            applicationContext.contentResolver,
            app.mediaKeyProvider,
        )
        val file = File(filePath)
        val encrypted = resolver.isEncryptedAtRest(filePath)
        val source: BackupUploadSource = when {
            encrypted -> BackupUploadSource.StreamSource(
                open = { resolver.openDecryptedStream(filePath) },
                sizeBytes = MediaCrypto.plaintextSize(
                    if (file.exists()) file.length() else probeContentSize(filePath) ?: -1L,
                ),
            )
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

    private fun probeContentSize(filePath: String): Long? = runCatching {
        applicationContext.contentResolver
            .openAssetFileDescriptor(android.net.Uri.parse(filePath), "r")
            ?.use { it.length }
    }.getOrNull()

    companion object {
        private const val TAG = "BackupWorker"
        const val KEY_FILE_PATH = "filePath"
        private const val KEY_BYPASS_WIFI_ONLY = "bypassWifiOnly"

        /** The tag every request carries, plus a per-file tag for the tamper sweep. */
        private const val TAG_BACKUP = "lenscast-backup"
        private fun fileTag(filePath: String) = "lenscast-backup-file:$filePath"

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
                .addTag(TAG_BACKUP)
                .addTag(fileTag(filePath))
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * The tamper response's urgent variant: expedited (degrading to a
         * regular request where the OS refuses), aggressive linear backoff,
         * and the Wi-Fi-only gate lifted for this request. The capture's
         * normal request — if one is still queued — is cancelled first, so
         * the same file cannot upload twice.
         */
        fun enqueueExpedited(context: Context, filePath: String) {
            val request = androidx.work.OneTimeWorkRequest.Builder(BackupWorker::class.java)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(KEY_FILE_PATH, filePath)
                        .putBoolean(KEY_BYPASS_WIFI_ONLY, true)
                        .build(),
                )
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .addTag(TAG_BACKUP)
                .addTag(fileTag(filePath))
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueue(request)
        }

        /**
         * The tamper sweep: for every history path whose backup request is
         * still unfinished in WorkManager (the honest definition of "pending
         * un-uploaded"), cancel the waiting request and re-enqueue it
         * expedited. Returns the paths that were re-queued.
         */
        fun expeditePending(context: Context, historyPaths: List<String>): List<String> {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val expedited = mutableListOf<String>()
            for (path in historyPaths) {
                val pending = runCatching {
                    workManager.getWorkInfosByTag(fileTag(path)).get()
                }.getOrNull()
                    ?.any { !it.state.isFinished }
                    ?: continue
                if (!pending) continue
                workManager.cancelAllWorkByTag(fileTag(path))
                enqueueExpedited(context, path)
                expedited.add(path)
            }
            return expedited
        }
    }
}

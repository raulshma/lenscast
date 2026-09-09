package com.raulshma.lenscast.update

import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.update.model.UpdateCheckResult
import kotlinx.coroutines.flow.first

/**
 * The one update check: check → decide ([UpdatePolicy]) → persist the check
 * time → notify. The startup auto-check (MainApplication) and the manual
 * check (UpdateViewModel) both ran their own copy of this sequence and had
 * already diverged on RateLimited handling; now both call [runCheck] and map
 * the returned [UpdateOutcome] to their own presentation.
 *
 * RateLimited is canonicalized as fully silent: no notification and the
 * 24h clock does not advance (the next check may run again sooner) — the
 * pipeline reports the outcome so a UI caller can still surface an error
 * state. All collaborators are constructor-injected seams so the whole
 * pipeline runs under JVM tests with fakes.
 */
class UpdateCheckPipeline(
    private val checker: Checker,
    private val store: Store,
    private val notifier: Notifier,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Where a completed check lands, ready for the caller's presentation. */
    sealed interface UpdateOutcome {
        /** A newer release exists (and was notified when not dismissed). */
        data class UpdateAvailable(
            val version: String,
            val releaseNotes: String,
            val downloadUrl: String,
            val fileSizeBytes: Long,
            val fileName: String,
            /** The release asset's "sha256:<hex>" digest; null when GitHub omits it. */
            val digest: String? = null,
            /** False when the user had dismissed this version. */
            val notified: Boolean,
        ) : UpdateOutcome

        /** Local is current, or the remote release was already dismissed. */
        data class UpToDate(val remoteVersion: String) : UpdateOutcome

        /** Silent no-op: GitHub rate limit — no notify, no clock advance. */
        data object RateLimited : UpdateOutcome

        /** Silent failure; [message] is the checker's own description. */
        data class Error(val message: String) : UpdateOutcome
    }

    fun interface Checker {
        suspend fun checkForUpdate(): UpdateCheckResult
    }

    /** The two persisted values a check touches. */
    interface Store {
        suspend fun dismissedVersion(): String
        suspend fun saveLastCheckTime(timeMs: Long)
    }

    fun interface Notifier {
        fun showUpdateAvailable(version: String)
    }

    suspend fun runCheck(): UpdateOutcome {
        val result = checker.checkForUpdate()
        val dismissed = store.dismissedVersion()
        val decision = UpdatePolicy.shouldNotifyAfterCheck(result, dismissed)
        if (decision.saveLastCheck) {
            store.saveLastCheckTime(nowMs())
        }
        return when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                val version = UpdatePolicy.normalize(result.release.tagName)
                if (decision.notify) {
                    notifier.showUpdateAvailable(version)
                    UpdateOutcome.UpdateAvailable(
                        version = version,
                        releaseNotes = result.release.body,
                        downloadUrl = result.apkAsset.browserDownloadUrl,
                        fileSizeBytes = result.apkAsset.size,
                        fileName = result.apkAsset.name,
                        digest = result.apkAsset.digest,
                        notified = true,
                    )
                } else {
                    UpdateOutcome.UpToDate(remoteVersion = version)
                }
            }
            is UpdateCheckResult.UpToDate -> UpdateOutcome.UpToDate(result.remoteVersion)
            UpdateCheckResult.RateLimited -> UpdateOutcome.RateLimited
            is UpdateCheckResult.Error -> UpdateOutcome.Error(result.message)
        }
    }

    companion object {
        /** Production wiring: the real checker, notifier, and settings store. */
        fun production(
            checker: UpdateChecker,
            notifier: UpdateNotifier,
            settings: SettingsDataStore,
        ): UpdateCheckPipeline = UpdateCheckPipeline(
            checker = checker::checkForUpdate,
            store = object : Store {
                override suspend fun dismissedVersion(): String =
                    settings.updateDismissedVersion.first()

                override suspend fun saveLastCheckTime(timeMs: Long) {
                    settings.saveUpdateLastCheckTime(timeMs)
                }
            },
            notifier = notifier::showUpdateAvailable,
        )
    }
}

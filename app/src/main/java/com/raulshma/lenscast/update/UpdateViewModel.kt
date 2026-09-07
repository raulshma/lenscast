package com.raulshma.lenscast.update

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.update.model.UpdateState
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class UpdateViewModel(
    updateChecker: UpdateChecker,
    private val updateDownloader: UpdateDownloader,
    private val updateInstaller: UpdateInstaller,
    private val updateNotifier: UpdateNotifier,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    // The one check pipeline (check → decide → persist → notify), shared with
    // the startup auto-check; this ViewModel only maps outcomes to UI state.
    private val checkPipeline = UpdateCheckPipeline.production(
        checker = updateChecker,
        notifier = updateNotifier,
        settings = settingsDataStore,
    )

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // Store flows exposed directly — like every other ViewModel, no mirrors.
    val autoCheckEnabled: StateFlow<Boolean> = settingsDataStore.updateAutoCheckEnabled
    val lastCheckTime: StateFlow<Long> = settingsDataStore.updateLastCheckTime

    private var downloadJob: Job? = null

    companion object {
        private const val TAG = "UpdateViewModel"
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            when (val outcome = checkPipeline.runCheck()) {
                is UpdateCheckPipeline.UpdateOutcome.UpdateAvailable -> {
                    Log.d(TAG, "Update available: ${outcome.version}")
                    _updateState.value = UpdateState.UpdateAvailable(
                        version = outcome.version,
                        releaseNotes = outcome.releaseNotes,
                        downloadUrl = outcome.downloadUrl,
                        fileSizeBytes = outcome.fileSizeBytes,
                        fileName = outcome.fileName,
                    )
                }
                is UpdateCheckPipeline.UpdateOutcome.UpToDate -> {
                    Log.d(TAG, "App is up to date (remote=${outcome.remoteVersion})")
                    _updateState.value = UpdateState.UpToDate(outcome.remoteVersion)
                }
                UpdateCheckPipeline.UpdateOutcome.RateLimited -> {
                    Log.w(TAG, "Rate limited by GitHub API")
                    _updateState.value = UpdateState.Error("GitHub API rate limited. Try again later.")
                }
                is UpdateCheckPipeline.UpdateOutcome.Error -> {
                    Log.e(TAG, "Update check error: ${outcome.message}")
                    _updateState.value = UpdateState.Error(outcome.message)
                }
            }
        }
    }

    fun downloadUpdate() {
        val state = _updateState.value as? UpdateState.UpdateAvailable ?: return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                updateDownloader.download(state.downloadUrl, state.fileName)
                    .collect { progress ->
                        _updateState.value = UpdateState.Downloading(progress)
                    }
                val apkFile = updateDownloader.getDownloadedApk()
                if (apkFile != null && apkFile.exists()) {
                    _updateState.value = UpdateState.ReadyToInstall(apkFile.absolutePath)
                } else {
                    _updateState.value = UpdateState.Error("Download failed")
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun installUpdate(activity: Activity) {
        val state = _updateState.value as? UpdateState.ReadyToInstall ?: return
        val apkFile = File(state.apkFilePath)
        if (!updateInstaller.canRequestInstall()) {
            updateInstaller.openInstallPermissionSettings(activity)
            return
        }
        updateInstaller.installApk(apkFile, activity)
    }

    fun dismissUpdate() {
        val state = _updateState.value as? UpdateState.UpdateAvailable ?: return
        viewModelScope.launch {
            settingsDataStore.saveUpdateDismissedVersion(state.version)
        }
        updateNotifier.cancel()
        _updateState.value = UpdateState.Idle
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveUpdateAutoCheckEnabled(enabled)
        }
    }

    fun clearError() {
        _updateState.value = UpdateState.Idle
    }

    class Factory(
        private val updateChecker: UpdateChecker,
        private val updateDownloader: UpdateDownloader,
        private val updateInstaller: UpdateInstaller,
        private val updateNotifier: UpdateNotifier,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return UpdateViewModel(
                updateChecker, updateDownloader, updateInstaller,
                updateNotifier, settingsDataStore
            ) as T
        }
    }
}

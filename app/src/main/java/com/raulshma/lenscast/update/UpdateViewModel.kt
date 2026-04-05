package com.raulshma.lenscast.update

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.update.model.UpdateCheckResult
import com.raulshma.lenscast.update.model.UpdateState
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class UpdateViewModel(
    private val updateChecker: UpdateChecker,
    private val updateDownloader: UpdateDownloader,
    private val updateInstaller: UpdateInstaller,
    private val updateNotifier: UpdateNotifier,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _autoCheckEnabled = MutableStateFlow(true)
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    private val _lastCheckTime = MutableStateFlow(0L)
    val lastCheckTime: StateFlow<Long> = _lastCheckTime.asStateFlow()

    private var downloadJob: Job? = null

    companion object {
        private const val TAG = "UpdateViewModel"
    }

    init {
        viewModelScope.launch {
            settingsDataStore.updateAutoCheckEnabled.collect { _autoCheckEnabled.value = it }
        }
        viewModelScope.launch {
            settingsDataStore.updateLastCheckTime.collect { _lastCheckTime.value = it }
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            when (val result = updateChecker.checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val remoteVersion = result.release.tagName.trimStart('v')
                    val dismissed = settingsDataStore.updateDismissedVersion.first()
                    settingsDataStore.saveUpdateLastCheckTime(System.currentTimeMillis())
                    _lastCheckTime.value = System.currentTimeMillis()

                    if (dismissed == remoteVersion) {
                        Log.d(TAG, "Update $remoteVersion dismissed by user")
                        _updateState.value = UpdateState.UpToDate(remoteVersion)
                    } else {
                        Log.d(TAG, "Update available: $remoteVersion")
                        _updateState.value = UpdateState.UpdateAvailable(
                            version = remoteVersion,
                            releaseNotes = result.release.body,
                            downloadUrl = result.apkAsset.browserDownloadUrl,
                            fileSizeBytes = result.apkAsset.size,
                            fileName = result.apkAsset.name,
                        )
                        updateNotifier.showUpdateAvailable(remoteVersion)
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    Log.d(TAG, "App is up to date (local=${result.localVersion}, remote=${result.remoteVersion})")
                    settingsDataStore.saveUpdateLastCheckTime(System.currentTimeMillis())
                    _lastCheckTime.value = System.currentTimeMillis()
                    _updateState.value = UpdateState.UpToDate(result.remoteVersion)
                }
                is UpdateCheckResult.RateLimited -> {
                    Log.w(TAG, "Rate limited by GitHub API")
                    _updateState.value = UpdateState.Error("GitHub API rate limited. Try again later.")
                }
                is UpdateCheckResult.Error -> {
                    Log.e(TAG, "Update check error: ${result.message}")
                    _updateState.value = UpdateState.Error(result.message)
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
        _autoCheckEnabled.value = enabled
        viewModelScope.launch {
            settingsDataStore.saveUpdateAutoCheckEnabled(enabled)
        }
    }

    fun clearError() {
        _updateState.value = UpdateState.Idle
    }

    fun autoCheckIfNeeded() {
        viewModelScope.launch {
            val enabled = settingsDataStore.updateAutoCheckEnabled.first()
            if (!enabled) return@launch
            val lastCheck = settingsDataStore.updateLastCheckTime.first()
            val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            if (lastCheck > oneDayAgo) return@launch
            checkForUpdate()
        }
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

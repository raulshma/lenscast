package com.raulshma.lenscast.settings

import android.app.Activity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.CameraSettingsEditor
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Write-side settings interface for the settings screens. Persisted values are
 * exposed directly from the SettingsDataStore — the store is the single source
 * of truth and there is no mirror state to drift. Runtime application is owned
 * by SettingsApplier; this ViewModel only writes. Camera settings funnel
 * through the Quick Setting Catalog's write table via [updateQuickSetting];
 * every other write is a one-line store save through [save].
 */
class SettingsViewModel(
    private val cameraService: CameraService,
    private val settingsDataStore: SettingsDataStore,
    private val powerManager: PowerManager? = null,
) : ViewModel() {

    // Read-side settings come straight from the Settings Store's shared
    // StateFlows — no per-ViewModel stateIn, no retyped defaults.
    val settings: StateFlow<CameraSettings> = settingsDataStore.settings
    val streamingPort: StateFlow<Int> = settingsDataStore.streamingPort
    val webStreamingEnabled: StateFlow<Boolean> = settingsDataStore.webStreamingEnabled
    val jpegQuality: StateFlow<Int> = settingsDataStore.jpegQuality
    val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview
    val streamAudioEnabled: StateFlow<Boolean> = settingsDataStore.streamAudioEnabled
    val streamAudioBitrateKbps: StateFlow<Int> = settingsDataStore.streamAudioBitrateKbps
    val streamAudioChannels: StateFlow<Int> = settingsDataStore.streamAudioChannels
    val streamAudioEchoCancellation: StateFlow<Boolean> = settingsDataStore.streamAudioEchoCancellation
    val recordingAudioEnabled: StateFlow<Boolean> = settingsDataStore.recordingAudioEnabled
    val rtspEnabled: StateFlow<Boolean> = settingsDataStore.rtspEnabled
    val rtspPort: StateFlow<Int> = settingsDataStore.rtspPort
    val rtspInputFormat: StateFlow<RtspInputFormat> = settingsDataStore.rtspInputFormat
    val adaptiveBitrateEnabled: StateFlow<Boolean> = settingsDataStore.adaptiveBitrateEnabled
    val mdnsEnabled: StateFlow<Boolean> = settingsDataStore.mdnsEnabled
    val motionDetectionEnabled: StateFlow<Boolean> = settingsDataStore.motionDetectionEnabled
    val motionSensitivityPercent: StateFlow<Int> = settingsDataStore.motionSensitivity
    val motionRecordingEnabled: StateFlow<Boolean> = settingsDataStore.motionRecordingEnabled
    val motionPostRollSeconds: StateFlow<Int> = settingsDataStore.motionPostRollSeconds
    val motionArmScheduleEnabled: StateFlow<Boolean> = settingsDataStore.motionArmScheduleEnabled
    val motionArmStartMinute: StateFlow<Int> = settingsDataStore.motionArmStartMinute
    val motionArmEndMinute: StateFlow<Int> = settingsDataStore.motionArmEndMinute
    val soundDetectionEnabled: StateFlow<Boolean> = settingsDataStore.soundDetectionEnabled
    val soundThresholdPercent: StateFlow<Int> = settingsDataStore.soundThresholdPercent
    val detectionNotificationsEnabled: StateFlow<Boolean> = settingsDataStore.detectionNotificationsEnabled
    val tamperDetectionEnabled: StateFlow<Boolean> = settingsDataStore.tamperDetectionEnabled
    val watchdogEnabled: StateFlow<Boolean> = settingsDataStore.watchdogEnabled
    val watchdogMaxRetries: StateFlow<Int> = settingsDataStore.watchdogMaxRetries
    val watchdogCheckIntervalSeconds: StateFlow<Int> = settingsDataStore.watchdogCheckIntervalSeconds
    val backupEnabled: StateFlow<Boolean> = settingsDataStore.backupEnabled
    val backupWifiOnly: StateFlow<Boolean> = settingsDataStore.backupWifiOnly
    val backupWebdavUrl: StateFlow<String> = settingsDataStore.backupWebdavUrl
    val backupWebdavUsername: StateFlow<String> = settingsDataStore.backupWebdavUsername
    val backupWebdavPassword: StateFlow<String> = settingsDataStore.backupWebdavPassword
    val httpsEnabled: StateFlow<Boolean> = settingsDataStore.httpsEnabled
    val audioDeviceId: StateFlow<String> = settingsDataStore.audioDeviceId
    val resumeStreamsOnBoot: StateFlow<Boolean> = settingsDataStore.resumeStreamsOnBoot

    // Auth state comes straight from the Settings Store — no writable mirror.
    // Typing responsiveness comes from the store flow re-emit on save.
    val authSettings: StateFlow<StreamAuthSettings> = settingsDataStore.authSettings

    private val _isIgnoringBatteryOptimizations = mutableStateOf(false)
    val isIgnoringBatteryOptimizations = _isIgnoringBatteryOptimizations

    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = cameraService.availableZoomRange
    val availableExposureRange: StateFlow<ClosedRange<Int>> = cameraService.availableExposureRange
    val availableIsoRange: StateFlow<ClosedRange<Int>> = cameraService.availableIsoRange

    // Same editor as the camera screen, persist-only: runtime application is
    // the Settings Applier's. Field parsing stays shared through it.
    private val settingsEditor = CameraSettingsEditor(
        current = { settings.value },
        persist = { settingsDataStore.saveSettings(it) },
    )

    init {
        refreshBatteryOptimizationStatus()
    }

    fun refreshBatteryOptimizationStatus() {
        _isIgnoringBatteryOptimizations.value = powerManager?.isIgnoringBatteryOptimizations() == true
    }

    fun requestIgnoreBatteryOptimization(activity: Activity) {
        powerManager?.requestIgnoreBatteryOptimization(activity)
    }

    // ── Camera settings writes ──
    // One funnel onto the Quick Setting Catalog's write table through the
    // persist-only CameraSettingsEditor. Focus distance, color temperature,
    // and scene mode have no catalog row — their three writers stay explicit.

    /**
     * The settings screen's single camera-settings write entry, mirroring
     * CameraViewModel.updateQuickSetting: the raw editor callback value is
     * converted once onto the typed QuickSettingEditorValue per the
     * descriptor's editor shape, then dispatched through the catalog's pure
     * write transform onto the one CameraSettingsEditor path (persist-only;
     * the Settings Applier applies).
     */
    fun updateQuickSetting(type: QuickSettingType, value: Any) {
        val editorValue = QuickSettingCatalog.editorValueFor(type, value) ?: return
        update { current -> QuickSettingCatalog.descriptorFor(type).write(current, editorValue) }
    }

    /** The manual-focus slider; the catalog's FOCUS row covers only the mode. */
    fun updateFocusDistance(distance: Float?) {
        update { it.copy(focusDistance = distance) }
    }

    /** The manual white-balance Kelvin slider; the WHITE_BALANCE row covers only the mode. */
    fun updateColorTemperature(temp: Int?) {
        update { it.copy(colorTemperature = temp) }
    }

    /** The scene-mode dropdown; "OFF" clears the override (no catalog row). */
    fun updateSceneMode(mode: String) {
        update { it.copy(sceneMode = CameraSettingsEditor.parseSceneMode(mode)) }
    }

    // ── Streaming / app settings writes ──
    // Plain store saves; the Settings Applier reacts to the flow.

    fun updateStreamingPort(port: Int) = save { settingsDataStore.saveStreamingPort(port) }

    fun updateWebStreamingEnabled(enabled: Boolean) = save { settingsDataStore.saveWebStreamingEnabled(enabled) }

    fun updateJpegQuality(quality: Int) = save { settingsDataStore.saveJpegQuality(quality) }

    fun updateShowPreview(show: Boolean) = save { settingsDataStore.saveShowPreview(show) }

    fun updateStreamAudioEnabled(enabled: Boolean) = save { settingsDataStore.saveStreamAudioEnabled(enabled) }

    fun updateStreamAudioBitrateKbps(bitrateKbps: Int) = save { settingsDataStore.saveStreamAudioBitrateKbps(bitrateKbps) }

    fun updateStreamAudioChannels(channels: Int) = save { settingsDataStore.saveStreamAudioChannels(channels) }

    fun updateStreamAudioEchoCancellation(enabled: Boolean) = save { settingsDataStore.saveStreamAudioEchoCancellation(enabled) }

    fun updateRecordingAudioEnabled(enabled: Boolean) = save { settingsDataStore.saveRecordingAudioEnabled(enabled) }

    fun updateRtspEnabled(enabled: Boolean) = save { settingsDataStore.saveRtspEnabled(enabled) }

    fun updateRtspPort(port: Int) = save { settingsDataStore.saveRtspPort(port) }

    fun updateRtspInputFormat(name: String) = save {
        settingsDataStore.saveRtspInputFormat(parseEnum(name, RtspInputFormat.AUTO))
    }

    fun updateAdaptiveBitrateEnabled(enabled: Boolean) = save { settingsDataStore.saveAdaptiveBitrateEnabled(enabled) }

    fun updateMdnsEnabled(enabled: Boolean) = save { settingsDataStore.saveMdnsEnabled(enabled) }

    fun updateMotionDetectionEnabled(enabled: Boolean) = save { settingsDataStore.saveMotionDetectionEnabled(enabled) }

    fun updateMotionSensitivity(percent: Int) = save { settingsDataStore.saveMotionSensitivity(percent) }

    fun updateMotionRecordingEnabled(enabled: Boolean) = save { settingsDataStore.saveMotionRecordingEnabled(enabled) }

    fun updateMotionPostRollSeconds(seconds: Int) = save { settingsDataStore.saveMotionPostRollSeconds(seconds) }

    fun updateMotionArmScheduleEnabled(enabled: Boolean) = save { settingsDataStore.saveMotionArmScheduleEnabled(enabled) }

    fun updateMotionArmStartMinute(minute: Int) = save { settingsDataStore.saveMotionArmStartMinute(minute) }

    fun updateMotionArmEndMinute(minute: Int) = save { settingsDataStore.saveMotionArmEndMinute(minute) }

    fun updateSoundDetectionEnabled(enabled: Boolean) = save { settingsDataStore.saveSoundDetectionEnabled(enabled) }

    fun updateSoundThresholdPercent(percent: Int) = save { settingsDataStore.saveSoundThresholdPercent(percent) }

    fun updateDetectionNotificationsEnabled(enabled: Boolean) =
        save { settingsDataStore.saveDetectionNotificationsEnabled(enabled) }

    fun updateTamperDetectionEnabled(enabled: Boolean) =
        save { settingsDataStore.saveTamperDetectionEnabled(enabled) }

    fun updateWatchdogEnabled(enabled: Boolean) = save { settingsDataStore.saveWatchdogEnabled(enabled) }

    fun updateWatchdogMaxRetries(retries: Int) = save { settingsDataStore.saveWatchdogMaxRetries(retries) }

    fun updateWatchdogCheckIntervalSeconds(seconds: Int) = save { settingsDataStore.saveWatchdogCheckIntervalSeconds(seconds) }

    fun updateBackupEnabled(enabled: Boolean) = save { settingsDataStore.saveBackupEnabled(enabled) }

    fun updateBackupWifiOnly(wifiOnly: Boolean) = save { settingsDataStore.saveBackupWifiOnly(wifiOnly) }

    fun updateBackupWebdavUrl(url: String) = save { settingsDataStore.saveBackupWebdavUrl(url) }

    fun updateBackupWebdavUsername(username: String) = save { settingsDataStore.saveBackupWebdavUsername(username) }

    fun updateHttpsEnabled(enabled: Boolean) = save { settingsDataStore.saveHttpsEnabled(enabled) }

    fun updateAudioDeviceId(id: String) = save { settingsDataStore.saveAudioDeviceId(id) }

    fun updateResumeStreamsOnBoot(enabled: Boolean) = save { settingsDataStore.saveResumeStreamsOnBoot(enabled) }

    fun updateBackupWebdavPassword(password: String) {
        if (password.isNotEmpty()) save { settingsDataStore.saveBackupWebdavPassword(password) }
    }

    fun updateAuthEnabled(enabled: Boolean) {
        persistAuth(authSettings.value.copy(enabled = enabled))
    }

    fun updateAuthUsername(username: String) {
        persistAuth(authSettings.value.copy(username = username, rtspDigestHa1 = ""))
    }

    fun updateAuthPassword(password: String) {
        val hash = StreamAuthCrypto.hashPassword(password)
        val username = authSettings.value.username
        val digestHa1 = StreamAuthCrypto.computeRtspDigestHa1(username, password)
        persistAuth(authSettings.value.copy(passwordHash = hash, rtspDigestHa1 = digestHa1))
    }

    fun resetToDefaults() = save { settingsDataStore.saveSettings(CameraSettings()) }

    /** The one persist seam: every plain store write launches through here. */
    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** The camera-settings funnel: persist-only through the editor (apply is the Settings Applier's). */
    private fun update(transform: (CameraSettings) -> CameraSettings) = save { settingsEditor.edit(transform) }

    private fun persistAuth(newAuth: StreamAuthSettings) = save { settingsDataStore.saveAuthSettings(newAuth) }

    class Factory(
        private val cameraService: CameraService,
        private val settingsDataStore: SettingsDataStore,
        private val powerManager: PowerManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(cameraService, settingsDataStore, powerManager) as T
        }
    }
}

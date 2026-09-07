package com.raulshma.lenscast.settings

import android.app.Activity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.CameraSettingsEditor
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamDefaults
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
 * by SettingsApplier; this ViewModel only writes.
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

    fun updateExposure(value: Int) {
        update { it.copy(exposureCompensation = value) }
    }

    fun updateIso(iso: Int?) {
        update { it.copy(iso = iso) }
    }

    fun updateFocusMode(mode: String) {
        update { it.copy(focusMode = FocusMode.valueOf(mode)) }
    }

    fun updateFocusDistance(distance: Float?) {
        update { it.copy(focusDistance = distance) }
    }

    fun updateWhiteBalance(mode: String) {
        update { it.copy(whiteBalance = WhiteBalance.valueOf(mode)) }
    }

    fun updateColorTemperature(temp: Int?) {
        update { it.copy(colorTemperature = temp) }
    }

    fun updateZoom(ratio: Float) {
        update { it.copy(zoomRatio = ratio) }
    }

    fun updateResolution(name: String) {
        update { it.copy(resolution = Resolution.valueOf(name)) }
    }

    fun updateFrameRate(rate: Int) {
        update { it.copy(frameRate = rate) }
    }

    fun updateHdrMode(mode: String) {
        update { it.copy(hdrMode = HdrMode.valueOf(mode)) }
    }

    fun updateIso(value: String) {
        update { it.copy(iso = CameraSettingsEditor.parseIso(value)) }
    }

    fun updateStabilization(enabled: Boolean) {
        update { it.copy(stabilization = enabled) }
    }

    fun updateSceneMode(mode: String) {
        update { it.copy(sceneMode = CameraSettingsEditor.parseSceneMode(mode)) }
    }

    fun updateNightVisionMode(mode: String) {
        update { it.copy(nightVisionMode = NightVisionMode.valueOf(mode)) }
    }

    fun updateStreamingPort(port: Int) {
        viewModelScope.launch {
            settingsDataStore.saveStreamingPort(port)
        }
    }

    fun updateWebStreamingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveWebStreamingEnabled(enabled)
        }
    }

    fun updateJpegQuality(quality: Int) {
        viewModelScope.launch {
            settingsDataStore.saveJpegQuality(quality)
        }
    }

    fun updateShowPreview(show: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveShowPreview(show)
        }
    }

    fun updateStreamAudioEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveStreamAudioEnabled(enabled)
        }
    }

    fun updateStreamAudioBitrateKbps(bitrateKbps: Int) {
        viewModelScope.launch {
            settingsDataStore.saveStreamAudioBitrateKbps(bitrateKbps)
        }
    }

    fun updateStreamAudioChannels(channels: Int) {
        viewModelScope.launch {
            settingsDataStore.saveStreamAudioChannels(channels)
        }
    }

    fun updateStreamAudioEchoCancellation(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveStreamAudioEchoCancellation(enabled)
        }
    }

    fun updateRecordingAudioEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveRecordingAudioEnabled(enabled)
        }
    }

    fun updateRtspEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveRtspEnabled(enabled)
        }
    }

    fun updateRtspPort(port: Int) {
        viewModelScope.launch {
            settingsDataStore.saveRtspPort(port)
        }
    }

    fun updateRtspInputFormat(name: String) {
        val format = parseEnum(name, RtspInputFormat.AUTO)
        viewModelScope.launch {
            settingsDataStore.saveRtspInputFormat(format)
        }
    }

    fun updateAdaptiveBitrateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveAdaptiveBitrateEnabled(enabled)
        }
    }

    fun updateMdnsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveMdnsEnabled(enabled)
        }
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

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsDataStore.saveSettings(CameraSettings())
        }
    }

    private fun update(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch {
            settingsEditor.edit(transform)
        }
    }

    private fun persistAuth(newAuth: StreamAuthSettings) {
        viewModelScope.launch {
            settingsDataStore.saveAuthSettings(newAuth)
        }
    }

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

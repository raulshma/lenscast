package com.raulshma.lenscast.settings

import android.app.Activity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

    val settings: StateFlow<CameraSettings> = settingsDataStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraSettings())

    val streamingPort: StateFlow<Int> = settingsDataStore.streamingPort
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamDefaults.WEB_PORT)

    val webStreamingEnabled: StateFlow<Boolean> = settingsDataStore.webStreamingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val jpegQuality: StateFlow<Int> = settingsDataStore.jpegQuality
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamDefaults.JPEG_QUALITY)

    val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val streamAudioEnabled: StateFlow<Boolean> = settingsDataStore.streamAudioEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val streamAudioBitrateKbps: StateFlow<Int> = settingsDataStore.streamAudioBitrateKbps
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamDefaults.AUDIO_BITRATE_KBPS)

    val streamAudioChannels: StateFlow<Int> = settingsDataStore.streamAudioChannels
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamDefaults.AUDIO_CHANNELS)

    val streamAudioEchoCancellation: StateFlow<Boolean> = settingsDataStore.streamAudioEchoCancellation
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val recordingAudioEnabled: StateFlow<Boolean> = settingsDataStore.recordingAudioEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val rtspEnabled: StateFlow<Boolean> = settingsDataStore.rtspEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val rtspPort: StateFlow<Int> = settingsDataStore.rtspPort
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamDefaults.RTSP_PORT)

    val rtspInputFormat: StateFlow<RtspInputFormat> = settingsDataStore.rtspInputFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, RtspInputFormat.AUTO)

    val adaptiveBitrateEnabled: StateFlow<Boolean> = settingsDataStore.adaptiveBitrateEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val mdnsEnabled: StateFlow<Boolean> = settingsDataStore.mdnsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Auth credentials back the username text field directly, so this one flow
    // is kept writable: optimistic updates keep typing responsive.
    private val _authSettings = MutableStateFlow(StreamAuthSettings())
    val authSettings: StateFlow<StreamAuthSettings> = _authSettings.asStateFlow()

    private val _isIgnoringBatteryOptimizations = mutableStateOf(false)
    val isIgnoringBatteryOptimizations = _isIgnoringBatteryOptimizations

    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = cameraService.availableZoomRange
    val availableExposureRange: StateFlow<ClosedRange<Int>> = cameraService.availableExposureRange
    val availableIsoRange: StateFlow<ClosedRange<Int>> = cameraService.availableIsoRange

    init {
        viewModelScope.launch {
            settingsDataStore.authSettings.collect { auth ->
                _authSettings.value = auth
            }
        }
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
        val iso = if (value == "Auto") null else value.toIntOrNull()
        update { it.copy(iso = iso) }
    }

    fun updateStabilization(enabled: Boolean) {
        update { it.copy(stabilization = enabled) }
    }

    fun updateSceneMode(mode: String) {
        val sceneMode = if (mode == "OFF") null else mode
        update { it.copy(sceneMode = sceneMode) }
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
        val format = runCatching { RtspInputFormat.valueOf(name) }.getOrDefault(RtspInputFormat.AUTO)
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
        persistAuth(_authSettings.value.copy(enabled = enabled))
    }

    fun updateAuthUsername(username: String) {
        persistAuth(_authSettings.value.copy(username = username, rtspDigestHa1 = ""))
    }

    fun updateAuthPassword(password: String) {
        val hash = StreamAuthSettings.hashPassword(password)
        val username = _authSettings.value.username
        val digestHa1 = StreamAuthSettings.computeRtspDigestHa1(username, password)
        persistAuth(_authSettings.value.copy(passwordHash = hash, rtspDigestHa1 = digestHa1))
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsDataStore.saveSettings(CameraSettings())
        }
    }

    private fun update(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch {
            settingsDataStore.saveSettings(transform(settings.value))
        }
    }

    private fun persistAuth(newAuth: StreamAuthSettings) {
        _authSettings.value = newAuth
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

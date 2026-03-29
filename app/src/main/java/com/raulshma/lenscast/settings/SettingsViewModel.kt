package com.raulshma.lenscast.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val cameraService: CameraService,
    private val settingsDataStore: SettingsDataStore,
    private val streamingManager: StreamingManager? = null,
) : ViewModel() {

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    private val _authSettings = MutableStateFlow(StreamAuthSettings())
    val authSettings: StateFlow<StreamAuthSettings> = _authSettings.asStateFlow()

    private val _streamingPort = MutableStateFlow(8080)
    val streamingPort: StateFlow<Int> = _streamingPort.asStateFlow()

    private val _jpegQuality = MutableStateFlow(80)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    private val _showPreview = MutableStateFlow(true)
    val showPreview: StateFlow<Boolean> = _showPreview.asStateFlow()

    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = cameraService.availableZoomRange
    val availableExposureRange: StateFlow<ClosedRange<Int>> = cameraService.availableExposureRange

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { saved ->
                _settings.value = saved
                cameraService.applySettings(saved)
            }
        }
        viewModelScope.launch {
            settingsDataStore.authSettings.collect { auth ->
                _authSettings.value = auth
                streamingManager?.updateAuthSettings(auth)
            }
        }
        viewModelScope.launch {
            settingsDataStore.streamingPort.collect { port ->
                _streamingPort.value = port
            }
        }
        viewModelScope.launch {
            settingsDataStore.jpegQuality.collect { quality ->
                _jpegQuality.value = quality
                streamingManager?.setJpegQuality(quality)
            }
        }
        viewModelScope.launch {
            settingsDataStore.showPreview.collect { show ->
                _showPreview.value = show
            }
        }
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

    fun updateStreamingPort(port: Int) {
        _streamingPort.value = port
        viewModelScope.launch {
            settingsDataStore.saveStreamingPort(port)
        }
    }

    fun updateJpegQuality(quality: Int) {
        _jpegQuality.value = quality
        viewModelScope.launch {
            settingsDataStore.saveJpegQuality(quality)
            streamingManager?.setJpegQuality(quality)
        }
    }

    fun updateShowPreview(show: Boolean) {
        _showPreview.value = show
        viewModelScope.launch {
            settingsDataStore.saveShowPreview(show)
        }
    }

    fun updateAuthEnabled(enabled: Boolean) {
        val newAuth = _authSettings.value.copy(enabled = enabled)
        _authSettings.value = newAuth
        viewModelScope.launch {
            settingsDataStore.saveAuthSettings(newAuth)
            streamingManager?.updateAuthSettings(newAuth)
        }
    }

    fun updateAuthUsername(username: String) {
        val newAuth = _authSettings.value.copy(username = username)
        _authSettings.value = newAuth
        viewModelScope.launch {
            settingsDataStore.saveAuthSettings(newAuth)
            streamingManager?.updateAuthSettings(newAuth)
        }
    }

    fun updateAuthPassword(password: String) {
        val hash = StreamAuthSettings.hashPassword(password)
        val newAuth = _authSettings.value.copy(passwordHash = hash)
        _authSettings.value = newAuth
        viewModelScope.launch {
            settingsDataStore.saveAuthSettings(newAuth, rawPassword = password)
            streamingManager?.updateAuthSettings(newAuth)
        }
    }

    fun resetToDefaults() {
        val defaults = CameraSettings()
        _settings.value = defaults
        viewModelScope.launch {
            settingsDataStore.saveSettings(defaults)
            cameraService.applySettings(defaults)
        }
    }

    private fun update(transform: (CameraSettings) -> CameraSettings) {
        val newSettings = transform(_settings.value)
        _settings.value = newSettings
        viewModelScope.launch {
            settingsDataStore.saveSettings(newSettings)
            cameraService.applySettings(newSettings)
        }
    }

    class Factory(
        private val cameraService: CameraService,
        private val settingsDataStore: SettingsDataStore,
        private val streamingManager: StreamingManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(cameraService, settingsDataStore, streamingManager) as T
        }
    }
}

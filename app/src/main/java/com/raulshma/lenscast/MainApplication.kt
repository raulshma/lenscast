package com.raulshma.lenscast

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainApplication : Application(), ImageLoaderFactory {
    val cameraService: CameraService by lazy { CameraService(this) }
    val streamingManager: StreamingManager by lazy { StreamingManager(this) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val captureHistoryStore: CaptureHistoryStore by lazy { CaptureHistoryStore(this) }
    val powerManager: PowerManager by lazy { PowerManager(this) }
    val thermalMonitor: ThermalMonitor by lazy { ThermalMonitor(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        initializeStreamingServer()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun initializeStreamingServer() {
        appScope.launch {
            settingsDataStore.streamingPort.collectLatest { port ->
                streamingManager.setPort(port)
                streamingManager.ensureServerRunning()
            }
        }
        appScope.launch {
            settingsDataStore.jpegQuality.collectLatest { quality ->
                streamingManager.setJpegQuality(quality)
            }
        }
        appScope.launch {
            settingsDataStore.streamAudioEnabled.collectLatest { enabled ->
                streamingManager.setStreamAudioEnabled(enabled)
            }
        }
        appScope.launch {
            settingsDataStore.streamAudioBitrateKbps.collectLatest { bitrateKbps ->
                streamingManager.setStreamAudioBitrateKbps(bitrateKbps)
            }
        }
        appScope.launch {
            settingsDataStore.streamAudioChannels.collectLatest { channels ->
                streamingManager.setStreamAudioChannels(channels)
            }
        }
        appScope.launch {
            settingsDataStore.streamAudioEchoCancellation.collectLatest { enabled ->
                streamingManager.setStreamAudioEchoCancellation(enabled)
            }
        }
        appScope.launch {
            settingsDataStore.webStreamingEnabled.collectLatest { enabled ->
                streamingManager.setWebStreamingEnabled(enabled)
            }
        }
        appScope.launch {
            settingsDataStore.authSettings.collectLatest { auth ->
                streamingManager.updateAuthSettings(auth)
            }
        }
        appScope.launch {
            settingsDataStore.rtspEnabled.collectLatest { enabled ->
                streamingManager.setRtspEnabled(enabled)
            }
        }
        appScope.launch {
            settingsDataStore.rtspPort.collectLatest { port ->
                streamingManager.setRtspPort(port)
            }
        }
        appScope.launch {
            settingsDataStore.rtspInputFormat.collectLatest { format ->
                streamingManager.setRtspInputFormat(format)
            }
        }
    }
}

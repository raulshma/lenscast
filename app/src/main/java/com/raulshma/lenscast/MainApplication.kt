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
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
        // Combined: Port + server startup
        appScope.launch {
            settingsDataStore.streamingPort.collectLatest { port ->
                streamingManager.setPort(port)
                streamingManager.ensureServerRunning()
            }
        }

        // Combined: All audio-related settings in one coroutine
        appScope.launch {
            combine(
                settingsDataStore.streamAudioEnabled,
                settingsDataStore.streamAudioBitrateKbps,
                settingsDataStore.streamAudioChannels,
                settingsDataStore.streamAudioEchoCancellation,
            ) { enabled, bitrate, channels, echoCancellation ->
                AudioSettings(enabled, bitrate, channels, echoCancellation)
            }.collectLatest { audio ->
                streamingManager.setStreamAudioEnabled(audio.enabled)
                streamingManager.setStreamAudioBitrateKbps(audio.bitrateKbps)
                streamingManager.setStreamAudioChannels(audio.channels)
                streamingManager.setStreamAudioEchoCancellation(audio.echoCancellation)
            }
        }

        // Combined: JPEG quality + overlay settings
        appScope.launch {
            combine(
                settingsDataStore.jpegQuality,
                settingsDataStore.overlaySettings,
            ) { quality, overlay ->
                QualityOverlaySettings(quality, overlay)
            }.collectLatest { config ->
                streamingManager.setJpegQuality(config.quality)
                streamingManager.setOverlaySettings(config.overlay)
            }
        }

        // Frame rate (M-JPEG streaming + RTSP + adaptive bitrate)
        appScope.launch {
            settingsDataStore.frameRate.collectLatest { fps ->
                streamingManager.setStreamFrameRate(fps)
                streamingManager.setAdaptiveDefaultFrameRate(fps)
                streamingManager.setRtspFrameRate(fps)
            }
        }

        // Combined: RTSP settings
        appScope.launch {
            combine(
                settingsDataStore.rtspEnabled,
                settingsDataStore.rtspPort,
                settingsDataStore.rtspInputFormat,
            ) { enabled, port, format ->
                RtspSettings(enabled, port, format)
            }.collectLatest { rtsp ->
                streamingManager.setRtspEnabled(rtsp.enabled)
                streamingManager.setRtspPort(rtsp.port)
                streamingManager.setRtspInputFormat(rtsp.format)
            }
        }

        // Combined: Discovery + web streaming + adaptive bitrate
        appScope.launch {
            combine(
                settingsDataStore.webStreamingEnabled,
                settingsDataStore.mdnsEnabled,
                settingsDataStore.adaptiveBitrateEnabled,
            ) { webEnabled, mdns, adaptive ->
                DiscoverySettings(webEnabled, mdns, adaptive)
            }.collectLatest { discovery ->
                streamingManager.setWebStreamingEnabled(discovery.webEnabled)
                streamingManager.setMdnsEnabled(discovery.mdns)
                streamingManager.setAdaptiveBitrateEnabled(discovery.adaptive)
            }
        }

        // Auth settings (standalone - complex object, no benefit combining)
        appScope.launch {
            settingsDataStore.authSettings.collectLatest { auth ->
                streamingManager.updateAuthSettings(auth)
            }
        }
    }

    private data class AudioSettings(
        val enabled: Boolean,
        val bitrateKbps: Int,
        val channels: Int,
        val echoCancellation: Boolean,
    )

    private data class QualityOverlaySettings(
        val quality: Int,
        val overlay: com.raulshma.lenscast.camera.model.OverlaySettings,
    )

    private data class RtspSettings(
        val enabled: Boolean,
        val port: Int,
        val format: RtspInputFormat,
    )

    private data class DiscoverySettings(
        val webEnabled: Boolean,
        val mdns: Boolean,
        val adaptive: Boolean,
    )
}

package com.raulshma.lenscast.settings

import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Single owner of "persisted settings -> runtime" application.
 *
 * Every settings change goes through SettingsDataStore; this module watches the
 * store and applies new values to the StreamingManager, CameraService, and
 * StreamWatchdog. ViewModels and WebApiController write settings — nobody else
 * applies them, so each persisted change is applied exactly once regardless of
 * how many screens or clients are alive.
 */
class SettingsApplier(
    private val settingsDataStore: SettingsDataStore,
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val streamWatchdog: StreamWatchdog,
) {

    fun start(scope: CoroutineScope) {
        // Camera settings
        scope.launch {
            settingsDataStore.settings.distinctUntilChanged().collectLatest { saved ->
                cameraService.applySettings(saved)
            }
        }

        // Port + server startup
        scope.launch {
            settingsDataStore.streamingPort.collectLatest { port ->
                streamingManager.setPort(port)
                streamingManager.ensureServerRunning()
            }
        }

        // All audio-related settings in one coroutine
        scope.launch {
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

        // JPEG quality + overlay settings
        scope.launch {
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
        scope.launch {
            settingsDataStore.frameRate.collectLatest { fps ->
                streamingManager.setStreamFrameRate(fps)
                streamingManager.setAdaptiveDefaultFrameRate(fps)
                streamingManager.setRtspFrameRate(fps)
            }
        }

        // RTSP settings
        scope.launch {
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

        // Discovery + web streaming + adaptive bitrate
        scope.launch {
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

        // Auth settings
        scope.launch {
            settingsDataStore.authSettings.collectLatest { auth ->
                streamingManager.updateAuthSettings(auth)
            }
        }

        // Watchdog settings
        scope.launch {
            combine(
                settingsDataStore.watchdogEnabled,
                settingsDataStore.watchdogMaxRetries,
                settingsDataStore.watchdogCheckIntervalSeconds,
            ) { enabled, maxRetries, checkInterval ->
                WatchdogSettings(enabled, maxRetries, checkInterval)
            }.collectLatest { watchdog ->
                streamWatchdog.setEnabled(watchdog.enabled)
                streamWatchdog.setMaxRetries(watchdog.maxRetries)
                streamWatchdog.setCheckIntervalSeconds(watchdog.checkInterval)
            }
        }

        Log.d(TAG, "Settings applier started")
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

    private data class WatchdogSettings(
        val enabled: Boolean,
        val maxRetries: Int,
        val checkInterval: Int,
    )

    companion object {
        private const val TAG = "SettingsApplier"
    }
}

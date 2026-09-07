package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.model.AdaptiveBitrateStatusDto
import com.raulshma.lenscast.streaming.model.BatteryStatusDto
import com.raulshma.lenscast.streaming.model.ClientConnectionDetailDto
import com.raulshma.lenscast.streaming.model.ConnectionQualityStatusDto
import com.raulshma.lenscast.streaming.model.StatusResponseDto
import com.raulshma.lenscast.streaming.model.StreamingStatusDto
import com.raulshma.lenscast.streaming.model.WatchdogStatusDto

/** /api/status — aggregates live state from every runtime module into one DTO. */
class StatusWebHandler(
    private val streamingManager: StreamingManager,
    private val thermalMonitor: ThermalMonitor,
    private val powerManager: PowerManager,
    private val cameraService: CameraService,
    private val streamWatchdog: StreamWatchdog,
    private val settingsDataStore: SettingsDataStore,
) {

    private val responseAdapter by lazy { WebJson.moshi.adapter(StatusResponseDto::class.java) }

    suspend fun get(): String {
        val adaptiveState = streamingManager.adaptiveBitrateState.value
        val adaptiveBitrateDto = if (adaptiveState.enabled) {
            AdaptiveBitrateStatusDto(
                enabled = adaptiveState.enabled,
                qualityLevel = adaptiveState.qualityLevel.name,
                currentQuality = adaptiveState.currentQuality,
                targetQuality = adaptiveState.targetQuality,
                currentFps = adaptiveState.currentFps,
                targetFps = adaptiveState.targetFps,
                estimatedBandwidthKbps = adaptiveState.estimatedBandwidthKbps,
                minClientThroughputKbps = adaptiveState.minClientThroughputKbps,
                activeClients = adaptiveState.activeClients,
                adjustmentCount = adaptiveState.adjustmentCount,
            )
        } else {
            null
        }

        val networkStats = streamingManager.getNetworkStatsSnapshot()
        val connectionQualityDto = if (streamingManager.isLiveStreaming()) {
            ConnectionQualityStatusDto(
                qualityLevel = networkStats.qualityLevel.name,
                estimatedBandwidthKbps = networkStats.estimatedBandwidthKbps,
                avgThroughputKbps = networkStats.avgThroughputKbps,
                minThroughputKbps = networkStats.minThroughputKbps,
                worstLatencyMs = networkStats.worstLatencyMs,
                avgFrameSizeBytes = networkStats.avgFrameSizeBytes,
                totalBytesSent = networkStats.totalBytesSent,
                activeClients = networkStats.activeClients,
                framesPerSecond = if (networkStats.activeClients > 0 && networkStats.clientDetails.isNotEmpty()) {
                    streamingManager.getFramesPerSecond(networkStats.clientDetails.keys.first())
                } else {
                    0.0
                },
                clientDetails = networkStats.clientDetails.mapValues { (_, detail) ->
                    ClientConnectionDetailDto(
                        framesSent = detail.framesSent,
                        bytesSent = detail.bytesSent,
                        avgThroughputKbps = detail.avgThroughputKbps,
                        lastFrameSizeBytes = detail.lastFrameSizeBytes,
                        lastSendDurationMs = detail.lastSendDurationMs,
                    )
                },
            )
        } else {
            null
        }

        val wdState = streamWatchdog.state.value
        val response = StatusResponseDto(
            streaming = StreamingStatusDto(
                isActive = streamingManager.isLiveStreaming(),
                url = streamingManager.streamUrl.value,
                webStreamingEnabled = settingsDataStore.webStreamingEnabled.value,
                webStreamingActive = streamingManager.isWebStreamActive(),
                clientCount = streamingManager.clientCount.value,
                audioEnabled = streamingManager.isAudioStreaming.value,
                audioUrl = streamingManager.audioStreamUrl.value,
                rtspEnabled = streamingManager.isRtspEnabled.value,
                rtspStreamingActive = streamingManager.isRtspRunning.value,
                rtspUrl = streamingManager.rtspUrl.value,
            ),
            thermal = thermalMonitor.thermalState.value.name,
            camera = cameraService.cameraState.value.toString(),
            battery = BatteryStatusDto(
                level = powerManager.batteryLevel.value,
                isCharging = powerManager.isCharging.value,
                isPowerSaveMode = powerManager.isPowerSaveMode.value,
            ),
            adaptiveBitrate = adaptiveBitrateDto,
            connectionQuality = connectionQualityDto,
            watchdog = WatchdogStatusDto(
                enabled = wdState.enabled,
                status = wdState.status.name,
                consecutiveFailures = wdState.consecutiveFailures,
                totalRecoveries = wdState.totalRecoveries,
                lastRecoveryTimestamp = wdState.lastRecoveryTimestamp,
                lastFailureReason = wdState.lastFailureReason,
            ),
        )
        return responseAdapter.toJson(response)
    }
}

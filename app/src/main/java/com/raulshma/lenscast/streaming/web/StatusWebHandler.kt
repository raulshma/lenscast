package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.model.StatusResponseDto

/** /api/status — aggregates live state from every runtime module into one DTO. */
class StatusWebHandler(
    private val streamingManager: StreamingManager,
    private val thermalMonitor: ThermalMonitor,
    private val powerManager: PowerManager,
    private val cameraService: CameraService,
    private val streamWatchdog: StreamWatchdog,
    private val settingsDataStore: SettingsDataStore,
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(StatusResponseDto::class.java) }

    suspend fun get(): String {
        val adaptiveState = streamingManager.adaptiveBitrateState.value
        val networkStats = streamingManager.getNetworkStatsSnapshot()
        val firstClientKey = networkStats.clientDetails.keys.firstOrNull()
        val firstClientFps = if (networkStats.activeClients > 0 && firstClientKey != null) {
            streamingManager.getFramesPerSecond(firstClientKey)
        } else {
            0.0
        }
        val wdState = streamWatchdog.state.value
        val response = StatusSnapshotBuilder.build(
            streaming = StatusSnapshotBuilder.StreamingInputs(
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
            thermal = StatusSnapshotBuilder.ThermalInputs(
                cameraStateName = cameraService.cameraState.value.toString(),
                thermalName = thermalMonitor.thermalState.value.name,
            ),
            battery = StatusSnapshotBuilder.BatteryInputs(
                // Unknown level (first read pending) keeps the status DTO's
                // non-null contract; only the event payload omits the field.
                level = powerManager.batteryLevel.value ?: PowerManager.UNKNOWN_BATTERY_FALLBACK_PERCENT,
                isCharging = powerManager.isChargingNow(),
                isPowerSaveMode = powerManager.isPowerSaveMode.value,
            ),
            watchdog = StatusSnapshotBuilder.WatchdogInputs(
                enabled = wdState.enabled,
                statusName = wdState.status.name,
                consecutiveFailures = wdState.consecutiveFailures,
                totalRecoveries = wdState.totalRecoveries,
                lastRecoveryTimestamp = wdState.lastRecoveryTimestamp,
                lastFailureReason = wdState.lastFailureReason,
            ),
            adaptive = StatusSnapshotBuilder.AdaptiveInputs(
                enabled = adaptiveState.enabled,
                qualityLevelName = adaptiveState.qualityLevel.name,
                currentQuality = adaptiveState.currentQuality,
                targetQuality = adaptiveState.targetQuality,
                currentFps = adaptiveState.currentFps,
                targetFps = adaptiveState.targetFps,
                estimatedBandwidthKbps = adaptiveState.estimatedBandwidthKbps,
                minClientThroughputKbps = adaptiveState.minClientThroughputKbps,
                activeClients = adaptiveState.activeClients,
                adjustmentCount = adaptiveState.adjustmentCount,
            ),
            network = StatusSnapshotBuilder.NetworkInputs(
                qualityLevelName = networkStats.qualityLevel.name,
                estimatedBandwidthKbps = networkStats.estimatedBandwidthKbps,
                avgThroughputKbps = networkStats.avgThroughputKbps,
                minThroughputKbps = networkStats.minThroughputKbps,
                worstLatencyMs = networkStats.worstLatencyMs,
                avgFrameSizeBytes = networkStats.avgFrameSizeBytes,
                totalBytesSent = networkStats.totalBytesSent,
                activeClients = networkStats.activeClients,
                firstClientFps = firstClientFps,
                clientDetails = networkStats.clientDetails.mapValues { (_, detail) ->
                    StatusSnapshotBuilder.ClientDetailInputs(
                        framesSent = detail.framesSent,
                        bytesSent = detail.bytesSent,
                        avgThroughputKbps = detail.avgThroughputKbps,
                        lastFrameSizeBytes = detail.lastFrameSizeBytes,
                        lastSendDurationMs = detail.lastSendDurationMs,
                    )
                },
            ),
        )
        return responseAdapter.toJson(response)
    }
}

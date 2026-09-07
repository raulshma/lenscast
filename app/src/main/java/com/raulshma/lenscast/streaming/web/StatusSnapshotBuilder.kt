package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.streaming.model.AdaptiveBitrateStatusDto
import com.raulshma.lenscast.streaming.model.BatteryStatusDto
import com.raulshma.lenscast.streaming.model.ClientConnectionDetailDto
import com.raulshma.lenscast.streaming.model.ConnectionQualityStatusDto
import com.raulshma.lenscast.streaming.model.StatusResponseDto
import com.raulshma.lenscast.streaming.model.StreamingStatusDto
import com.raulshma.lenscast.streaming.model.WatchdogStatusDto

/**
 * The pure status aggregation: typed inputs in, one [StatusResponseDto] out.
 *
 * [StatusWebHandler] only collects flows/snapshots into these inputs and
 * delegates here, so the field mapping — previously inline in the handler's
 * `get()` over live manager state — is testable without a manager.
 *
 * All inputs are stdlib/Kotlin types plus pure DTO-adjacent mirrors. Enum and
 * state objects are stringified by the caller (`.name` / `.toString()`), so
 * this module never touches Android-tainted types.
 */
object StatusSnapshotBuilder {

    data class StreamingInputs(
        val isActive: Boolean,
        val url: String,
        val webStreamingEnabled: Boolean,
        val webStreamingActive: Boolean,
        val clientCount: Int,
        val audioEnabled: Boolean,
        val audioUrl: String,
        val rtspEnabled: Boolean,
        val rtspStreamingActive: Boolean,
        val rtspUrl: String,
    )

    data class ThermalInputs(
        val cameraStateName: String,
        val thermalName: String,
    )

    data class BatteryInputs(
        val level: Int,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean,
    )

    data class WatchdogInputs(
        val enabled: Boolean,
        val statusName: String,
        val consecutiveFailures: Int,
        val totalRecoveries: Int,
        val lastRecoveryTimestamp: Long,
        val lastFailureReason: String?,
    )

    data class AdaptiveInputs(
        val enabled: Boolean,
        val qualityLevelName: String,
        val currentQuality: Int,
        val targetQuality: Int,
        val currentFps: Int,
        val targetFps: Int,
        val estimatedBandwidthKbps: Int,
        val minClientThroughputKbps: Int,
        val activeClients: Int,
        val adjustmentCount: Int,
    )

    data class ClientDetailInputs(
        val framesSent: Long,
        val bytesSent: Long,
        val avgThroughputKbps: Int,
        val lastFrameSizeBytes: Int,
        val lastSendDurationMs: Long,
    )

    data class NetworkInputs(
        val qualityLevelName: String,
        val estimatedBandwidthKbps: Int,
        val avgThroughputKbps: Int,
        val minThroughputKbps: Int,
        val worstLatencyMs: Long,
        val avgFrameSizeBytes: Int,
        val totalBytesSent: Long,
        val activeClients: Int,
        /** Pre-resolved fps for the first client; gated below per the first-client hack. */
        val firstClientFps: Double,
        val clientDetails: Map<String, ClientDetailInputs>,
    )

    fun build(
        streaming: StreamingInputs,
        thermal: ThermalInputs,
        battery: BatteryInputs,
        watchdog: WatchdogInputs,
        adaptive: AdaptiveInputs,
        network: NetworkInputs,
    ): StatusResponseDto {
        val adaptiveBitrateDto = if (adaptive.enabled) {
            AdaptiveBitrateStatusDto(
                enabled = adaptive.enabled,
                qualityLevel = adaptive.qualityLevelName,
                currentQuality = adaptive.currentQuality,
                targetQuality = adaptive.targetQuality,
                currentFps = adaptive.currentFps,
                targetFps = adaptive.targetFps,
                estimatedBandwidthKbps = adaptive.estimatedBandwidthKbps,
                minClientThroughputKbps = adaptive.minClientThroughputKbps,
                activeClients = adaptive.activeClients,
                adjustmentCount = adaptive.adjustmentCount,
            )
        } else {
            null
        }

        val connectionQualityDto = if (streaming.isActive) {
            ConnectionQualityStatusDto(
                qualityLevel = network.qualityLevelName,
                estimatedBandwidthKbps = network.estimatedBandwidthKbps,
                avgThroughputKbps = network.avgThroughputKbps,
                minThroughputKbps = network.minThroughputKbps,
                worstLatencyMs = network.worstLatencyMs,
                avgFrameSizeBytes = network.avgFrameSizeBytes,
                totalBytesSent = network.totalBytesSent,
                activeClients = network.activeClients,
                framesPerSecond = if (network.activeClients > 0 && network.clientDetails.isNotEmpty()) {
                    network.firstClientFps
                } else {
                    0.0
                },
                clientDetails = network.clientDetails.mapValues { (_, detail) ->
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

        return StatusResponseDto(
            streaming = StreamingStatusDto(
                isActive = streaming.isActive,
                url = streaming.url,
                webStreamingEnabled = streaming.webStreamingEnabled,
                webStreamingActive = streaming.webStreamingActive,
                clientCount = streaming.clientCount,
                audioEnabled = streaming.audioEnabled,
                audioUrl = streaming.audioUrl,
                rtspEnabled = streaming.rtspEnabled,
                rtspStreamingActive = streaming.rtspStreamingActive,
                rtspUrl = streaming.rtspUrl,
            ),
            thermal = thermal.thermalName,
            camera = thermal.cameraStateName,
            battery = BatteryStatusDto(
                level = battery.level,
                isCharging = battery.isCharging,
                isPowerSaveMode = battery.isPowerSaveMode,
            ),
            adaptiveBitrate = adaptiveBitrateDto,
            connectionQuality = connectionQualityDto,
            watchdog = WatchdogStatusDto(
                enabled = watchdog.enabled,
                status = watchdog.statusName,
                consecutiveFailures = watchdog.consecutiveFailures,
                totalRecoveries = watchdog.totalRecoveries,
                lastRecoveryTimestamp = watchdog.lastRecoveryTimestamp,
                lastFailureReason = watchdog.lastFailureReason,
            ),
        )
    }
}

package com.raulshma.lenscast.streaming.model

/**
 * Data Transfer Objects for the Web API.
 * These replace manual JSONObject construction with type-safe Moshi serialization.
 * Uses KotlinJsonAdapterFactory (reflection-based) for adapter generation.
 */

// ── Settings DTOs ──

data class CameraSettingsDto(
    val exposureCompensation: Int = 0,
    val iso: Int? = null,
    val exposureTime: Long? = null,
    val focusMode: String = "AUTO",
    val focusDistance: Float? = null,
    val whiteBalance: String = "AUTO",
    val colorTemperature: Int? = null,
    val zoomRatio: Double = 1.0,
    val frameRate: Int = 24,
    val resolution: String = "FHD_1080P",
    val stabilization: Boolean = true,
    val hdrMode: String = "OFF",
    val sceneMode: String? = null,
    val nightVisionMode: String = "OFF",
)

data class MaskingZoneDto(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = true,
    val type: String = "BLACKOUT",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.2,
    val height: Double = 0.2,
    val pixelateSize: Int = 16,
    val blurRadius: Double = 10.0,
)

data class StreamingSettingsDto(
    val port: Int = DEFAULT_PORT,
    val webStreamingEnabled: Boolean = true,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    val showPreview: Boolean = true,
    val streamAudioEnabled: Boolean = true,
    val streamAudioBitrateKbps: Int = DEFAULT_AUDIO_BITRATE_KBPS,
    val streamAudioChannels: Int = 1,
    val streamAudioEchoCancellation: Boolean = true,
    val recordingAudioEnabled: Boolean = true,
    val rtspEnabled: Boolean = false,
    val rtspPort: Int = DEFAULT_RTSP_PORT,
    val rtspInputFormat: String = "",
    val adaptiveBitrateEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val showTimestamp: Boolean = true,
    val timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
    val showBranding: Boolean = false,
    val brandingText: String = "LensCast",
    val showStatus: Boolean = false,
    val showCustomText: Boolean = false,
    val customText: String = "",
    val overlayPosition: String = "TOP_LEFT",
    val overlayFontSize: Int = 28,
    val overlayTextColor: String = "#FFFFFF",
    val overlayBackgroundColor: String = "#80000000",
    val overlayPadding: Int = 8,
    val overlayLineHeight: Int = 4,
    val maskingEnabled: Boolean = false,
    val maskingZones: List<MaskingZoneDto> = emptyList(),
) {
    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_AUDIO_BITRATE_KBPS = 128
        const val DEFAULT_JPEG_QUALITY = 70
        const val DEFAULT_RTSP_PORT = 8554
    }
}

data class SettingsResponseDto(
    val camera: CameraSettingsDto,
    val streaming: StreamingSettingsDto,
)

data class SettingsUpdateRequestDto(
    val camera: CameraSettingsDto? = null,
    val streaming: StreamingSettingsDto? = null,
)

// ── Status DTOs ──

data class StreamingStatusDto(
    val isActive: Boolean,
    val url: String,
    val webStreamingEnabled: Boolean = true,
    val clientCount: Int,
    val audioEnabled: Boolean,
    val audioUrl: String,
    val rtspEnabled: Boolean = false,
    val rtspUrl: String = "",
)

data class BatteryStatusDto(
    val level: Int,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
)

data class StatusResponseDto(
    val streaming: StreamingStatusDto,
    val thermal: String,
    val camera: String,
    val battery: BatteryStatusDto,
    val adaptiveBitrate: AdaptiveBitrateStatusDto? = null,
    val connectionQuality: ConnectionQualityStatusDto? = null,
)

data class AdaptiveBitrateStatusDto(
    val enabled: Boolean,
    val qualityLevel: String,
    val currentQuality: Int,
    val targetQuality: Int,
    val currentFps: Int,
    val targetFps: Int,
    val estimatedBandwidthKbps: Int,
    val minClientThroughputKbps: Int,
    val activeClients: Int,
    val adjustmentCount: Int,
)

data class ConnectionQualityStatusDto(
    val qualityLevel: String,
    val estimatedBandwidthKbps: Int,
    val avgThroughputKbps: Int,
    val minThroughputKbps: Int,
    val worstLatencyMs: Long,
    val avgFrameSizeBytes: Int,
    val totalBytesSent: Long,
    val activeClients: Int,
    val framesPerSecond: Double,
    val clientDetails: Map<String, ClientConnectionDetailDto>,
)

data class ClientConnectionDetailDto(
    val framesSent: Long,
    val bytesSent: Long,
    val avgThroughputKbps: Int,
    val lastFrameSizeBytes: Int,
    val lastSendDurationMs: Long,
)

// ── API Response DTOs ──

data class SuccessResponse(val success: Boolean = true)

data class ErrorResponse(val success: Boolean = false, val error: String)

data class StreamActionResponse(
    val success: Boolean = true,
    val isActive: Boolean = false,
    val url: String? = null,
    val error: String? = null,
)

data class CaptureResponse(
    val success: Boolean = true,
    val fileName: String? = null,
    val error: String? = null,
)

// ── Lens DTOs ──

data class LensDto(
    val index: Int,
    val id: String,
    val label: String,
    val focalLength: Double,
    val isFront: Boolean,
    val selected: Boolean,
)

data class LensesResponseDto(
    val lenses: List<LensDto>,
    val selectedIndex: Int,
)

data class LensSelectRequest(val index: Int)

// ── Interval Capture DTOs ──

data class IntervalCaptureStatusDto(
    val isRunning: Boolean,
    val completedCaptures: Int,
)

// ── Recording DTOs ──

data class RecordingStatusDto(
    val isRecording: Boolean,
    val elapsedSeconds: Int,
    val isScheduled: Boolean = false,
    val scheduledStartTimeMs: Long? = null,
)

// ── Gallery DTOs ──

data class GalleryItemDto(
    val id: String,
    val type: String,
    val fileName: String,
    val timestamp: Long,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val thumbnailUrl: String,
    val downloadUrl: String,
)

data class GalleryResponseDto(
    val items: List<GalleryItemDto>,
    val total: Int,
    val page: Int = 0,
    val pageSize: Int = 0,
    val hasMore: Boolean = false,
)

data class BatchDeleteRequest(val ids: List<String>)

data class BatchDeleteResponse(
    val success: Boolean = true,
    val deleted: List<String>,
)

data class TapFocusRequest(
    val x: Double,
    val y: Double,
)

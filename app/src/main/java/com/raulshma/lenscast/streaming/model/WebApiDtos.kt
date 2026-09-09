package com.raulshma.lenscast.streaming.model

import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.BackupTargetPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec

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
    val frameRate: Int = StreamDefaults.STREAM_FPS,
    val resolution: String = "FHD_1080P",
    val stabilization: Boolean = true,
    val hdrMode: String = "OFF",
    val sceneMode: String? = null,
    val nightVisionMode: String = "OFF",
)

data class MaskingZoneDto(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = MaskingZone.DEFAULT.enabled,
    val type: String = MaskingZone.DEFAULT.type.name,
    val x: Double = MaskingZone.DEFAULT.x.toDouble(),
    val y: Double = MaskingZone.DEFAULT.y.toDouble(),
    val width: Double = MaskingZone.DEFAULT.width.toDouble(),
    val height: Double = MaskingZone.DEFAULT.height.toDouble(),
    val pixelateSize: Int = MaskingZone.DEFAULT.pixelateSize,
    val blurRadius: Double = MaskingZone.DEFAULT.blurRadius.toDouble(),
)

data class MotionZoneDto(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = MotionZone.DEFAULT.enabled,
    val x: Double = MotionZone.DEFAULT.x.toDouble(),
    val y: Double = MotionZone.DEFAULT.y.toDouble(),
    val width: Double = MotionZone.DEFAULT.width.toDouble(),
    val height: Double = MotionZone.DEFAULT.height.toDouble(),
)

data class StreamingSettingsDto(
    val port: Int = StreamDefaults.WEB_PORT,
    val webStreamingEnabled: Boolean = true,
    val jpegQuality: Int = StreamDefaults.JPEG_QUALITY,
    val showPreview: Boolean = true,
    val streamAudioEnabled: Boolean = true,
    val streamAudioBitrateKbps: Int = StreamDefaults.AUDIO_BITRATE_KBPS,
    val streamAudioChannels: Int = StreamDefaults.AUDIO_CHANNELS,
    val streamAudioEchoCancellation: Boolean = true,
    val recordingAudioEnabled: Boolean = true,
    val rtspEnabled: Boolean = false,
    val rtspPort: Int = StreamDefaults.RTSP_PORT,
    val rtspInputFormat: String = "",
    /** The RTSP output resolution wire name: "480p" | "720p" | "1080p". */
    val rtspResolution: String = RtspResolution.DEFAULT_WIRE_NAME,
    /**
     * The RTSP video codec wire name: "h264" | "h265". Persisted as
     * `rtsp_video_codec` and re-applied on process start; a live swap
     * restarts the RTSP output via its NEEDS_RESTART ladder.
     */
    val rtspVideoCodec: String = RtspVideoCodec.DEFAULT_WIRE_NAME,
    val adaptiveBitrateEnabled: Boolean = false,
    val overlayEnabled: Boolean = OverlaySettings.DEFAULT.enabled,
    val showTimestamp: Boolean = OverlaySettings.DEFAULT.showTimestamp,
    val timestampFormat: String = OverlaySettings.DEFAULT.timestampFormat,
    val showBranding: Boolean = OverlaySettings.DEFAULT.showBranding,
    val brandingText: String = OverlaySettings.DEFAULT.brandingText,
    val showStatus: Boolean = OverlaySettings.DEFAULT.showStatus,
    val showCustomText: Boolean = OverlaySettings.DEFAULT.showCustomText,
    val customText: String = OverlaySettings.DEFAULT.customText,
    val overlayPosition: String = OverlaySettings.DEFAULT.position.name,
    val overlayFontSize: Int = OverlaySettings.DEFAULT.fontSize,
    val overlayTextColor: String = OverlaySettings.DEFAULT.textColor,
    val overlayBackgroundColor: String = OverlaySettings.DEFAULT.backgroundColor,
    val overlayPadding: Int = OverlaySettings.DEFAULT.padding,
    val overlayLineHeight: Int = OverlaySettings.DEFAULT.lineHeight,
    val maskingEnabled: Boolean = false,
    val maskingZones: List<MaskingZoneDto> = emptyList(),
    val watchdogEnabled: Boolean = false,
    val watchdogMaxRetries: Int = StreamDefaults.WATCHDOG_MAX_RETRIES,
    val watchdogCheckIntervalSeconds: Int = StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS,
    val mdnsEnabled: Boolean = true,
    val motionDetectionEnabled: Boolean = false,
    val motionSensitivityPercent: Int = StreamDefaults.MOTION_SENSITIVITY_PERCENT_DEFAULT,
    val motionZones: List<MotionZoneDto> = emptyList(),
    val motionRecordingEnabled: Boolean = false,
    val motionPostRollSeconds: Int = StreamDefaults.MOTION_POST_ROLL_SECONDS_DEFAULT,
    val motionArmScheduleEnabled: Boolean = false,
    val motionArmStartMinute: Int = StreamDefaults.MOTION_ARM_START_MINUTE_DEFAULT,
    val motionArmEndMinute: Int = StreamDefaults.MOTION_ARM_END_MINUTE_DEFAULT,
    val soundDetectionEnabled: Boolean = false,
    val soundThresholdPercent: Int = StreamDefaults.SOUND_THRESHOLD_PERCENT_DEFAULT,
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    /** Custom POST headers as a JSON `{"Name": "value"}` map string. */
    val webhookHeaders: String = "",
    val autoSiren: Boolean = false,
    val autoTorch: Boolean = false,
    val sirenDurationSeconds: Int = StreamDefaults.SIREN_DURATION_SECONDS_DEFAULT,
    val autoDeterrenceCooldownSeconds: Int = StreamDefaults.AUTO_DETERRENCE_COOLDOWN_SECONDS_DEFAULT,
    val backupEnabled: Boolean = false,
    val backupWifiOnly: Boolean = true,
    /** `"webdav"` or `"telegram"` — the BackupWorker's routing selection. */
    val backupTarget: String = BackupTargetPolicy.DEFAULT_WIRE_NAME,
    val backupWebdavUrl: String = "",
    val backupWebdavUsername: String = "",
    /**
     * Write-only, like the stream-auth password: requests carry it, responses
     * always serialize it blank — the stored secret never round-trips.
     */
    val backupWebdavPassword: String = "",
    val telegramChatId: String = "",
    /**
     * Write-only, exactly like [backupWebdavPassword]: accepted on PUT, never
     * serialized back out.
     */
    val telegramBotToken: String = "",
    /** Whether the read-only API token path is armed. */
    val apiTokenEnabled: Boolean = false,
    /** True when a token hash is stored; the token and the hash never round-trip. */
    val apiTokenConfigured: Boolean = false,
    /**
     * Write-only plaintext token: the dashboard generates it client-side and
     * sends it once; the server stores only its SHA-256 hex hash and this
     * field always serializes blank.
     */
    val apiToken: String = "",
    val httpsEnabled: Boolean = false,
    val audioDeviceId: String = "",
    val detectionNotificationsEnabled: Boolean = true,
    val tamperDetectionEnabled: Boolean = false,
    val mqttEnabled: Boolean = false,
    val mqttBrokerHost: String = "",
    val mqttBrokerPort: Int = StreamDefaults.MQTT_PORT_DEFAULT,
    val mqttUsername: String = "",
    /**
     * Write-only, like [backupWebdavPassword]: accepted on PUT, always
     * serialized blank in responses.
     */
    val mqttPassword: String = "",
    val mqttTls: Boolean = false,
    val mqttDiscoveryPrefix: String = StreamDefaults.MQTT_DISCOVERY_PREFIX_DEFAULT,
    /** Capture retention window in days; 0 keeps captures forever. */
    val captureRetentionDays: Int = StreamDefaults.RETENTION_DAYS_DISABLED,
    /** Detection-event retention window in days; 0 keeps events forever. */
    val eventRetentionDays: Int = StreamDefaults.RETENTION_DAYS_DISABLED,
    /** ML object-detection gate on top of motion detection. */
    val mlDetectionEnabled: Boolean = false,
    /** Minimum ML confidence percent for a detected object to count. */
    val mlMinScorePercent: Int = StreamDefaults.ML_SCORE_PERCENT_DEFAULT,
    /** Continuous NVR-style loop recording (chained bounded segments). */
    val continuousRecording: Boolean = false,
    val continuousSegmentMinutes: Int = StreamDefaults.CONTINUOUS_SEGMENT_MINUTES_DEFAULT,
    /** ONVIF Profile S device endpoint + WS-Discovery responder. */
    val onvifEnabled: Boolean = false,
)

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
    val webStreamingActive: Boolean = false,
    val clientCount: Int,
    val audioEnabled: Boolean,
    val audioUrl: String,
    val rtspEnabled: Boolean = false,
    val rtspStreamingActive: Boolean = false,
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
    val watchdog: WatchdogStatusDto? = null,
)

data class WatchdogStatusDto(
    val enabled: Boolean,
    val status: String,
    val consecutiveFailures: Int,
    val totalRecoveries: Int,
    val lastRecoveryTimestamp: Long,
    val lastFailureReason: String?,
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
    /** Grid thumbnail: the downscaled photo route or the video frame route. */
    val thumbnailUrl: String,
    /** The full-size media route — the viewer's source for photos. */
    val url: String,
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

data class ZoomRequest(
    val zoomRatio: Double? = null,
    val ratio: Double? = null,
)

data class TorchRequest(val enabled: Boolean? = null)

data class StreamClientsResponseDto(
    val httpClients: List<String>,
    val httpCount: Int,
    val rtspCount: Int,
    val maxHttp: Int = StreamDefaults.MAX_HTTP_CLIENTS,
)

// ── Detection Event DTOs ──

data class DetectionEventDto(
    val id: String,
    val type: String,
    val source: String,
    val timestampMs: Long,
    val snapshotJpegBase64: String? = null,
    val dispatchedActions: List<String> = emptyList(),
    /** Labels of the motion zones that fired; empty for whole-frame or non-motion events. */
    val zones: List<String> = emptyList(),
    /** ML class labels (person, dog, car...) attached by the object-detection gate; empty when the gate is off. */
    val labels: List<String> = emptyList(),
    /** MediaStore numeric id of the motion clip once its bounded recording finalized; null until (or unless) linked. */
    val clipMediaId: Long? = null,
    /** File name of the motion clip, linked together with [clipMediaId]. */
    val clipFileName: String? = null,
)

data class DetectionEventsResponseDto(
    val events: List<DetectionEventDto>,
    val total: Int,
)

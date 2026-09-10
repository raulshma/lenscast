package com.raulshma.lenscast.streaming.model

import com.raulshma.lenscast.capture.ml.DetectionModelStore
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
    /**
     * The arm schedule's day-of-week mask: bit 0 = Monday … bit 6 = Sunday
     * (127 = every day, the default — the schedule stays time-of-day-only
     * unless a day is explicitly cleared).
     */
    val motionArmDaysMask: Int = StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT,
    val soundDetectionEnabled: Boolean = false,
    val soundThresholdPercent: Int = StreamDefaults.SOUND_THRESHOLD_PERCENT_DEFAULT,
    /** Sound trigger threshold rides a tracked ambient noise floor (adaptive). */
    val soundAdaptiveNoiseFloor: Boolean = false,
    /** Sound events start a bounded clip (the motion post-roll duration). */
    val soundRecordingEnabled: Boolean = false,
    /** Minimum seconds between two motion events. */
    val motionCooldownSeconds: Int = StreamDefaults.MOTION_COOLDOWN_SECONDS_DEFAULT,
    /** Minimum seconds between two sound events. */
    val soundCooldownSeconds: Int = StreamDefaults.SOUND_COOLDOWN_SECONDS_DEFAULT,
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
    /**
     * Quiet hours for local detection alerts: heads-up notifications are
     * held inside the window (webhook/MQTT keep firing). Defaults 22:00→07:00.
     */
    val alertQuietHoursEnabled: Boolean = false,
    val alertQuietHoursStartMinute: Int = StreamDefaults.QUIET_HOURS_START_MINUTE_DEFAULT,
    val alertQuietHoursEndMinute: Int = StreamDefaults.QUIET_HOURS_END_MINUTE_DEFAULT,
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
    /**
     * The capture-history storage quota in MB (100 MB–32 GB): the oldest
     * captures age out once LensCast's media passes it.
     */
    val storageQuotaMb: Int = StreamDefaults.STORAGE_QUOTA_MB_DEFAULT,
    /** ML object-detection gate on top of motion detection. */
    val mlDetectionEnabled: Boolean = false,
    /** Minimum ML confidence percent for a detected object to count. */
    val mlMinScorePercent: Int = StreamDefaults.ML_SCORE_PERCENT_DEFAULT,
    /** ML gate class groups: which detected classes count toward an alert. */
    val mlIncludePerson: Boolean = true,
    val mlIncludePets: Boolean = true,
    val mlIncludeVehicles: Boolean = true,
    /**
     * Response-only: the on-demand detection model's state —
     * `not_downloaded` | `downloading` | `ready` | `failed` (the
     * DetectionModelStore wire names). Accepted-but-ignored on PUT; the
     * dashboard cannot write the model's lifecycle, only request its
     * download through the dedicated POST route.
     */
    val mlModelState: String = DetectionModelStore.STATE_NOT_DOWNLOADED,
    /** Response-only: download progress 0..1; [DetectionModelStore.PROGRESS_NONE] when none is running. */
    val mlModelProgress: Double = DetectionModelStore.PROGRESS_NONE,
    /** Response-only: the failure reason when [mlModelState] is `failed`. */
    val mlModelError: String = "",
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

/**
 * The versioned settings-export envelope behind GET /api/settings/export:
 * the current settings document (secrets blanked, exactly as GET /api/settings
 * serializes them) plus the import-side identity fields. Import accepts this
 * envelope — or the bare settings document — and rejects unknown versions.
 */
data class SettingsExportDto(
    val schemaVersion: Int = SETTINGS_SCHEMA_VERSION,
    val exportedAtMs: Long = 0,
    val app: String = APP_IDENTITY,
    val settings: SettingsResponseDto? = null,
) {
    companion object {
        /** Bumped only when the settings document's shape breaks decode compat. */
        const val SETTINGS_SCHEMA_VERSION = 1
        const val APP_IDENTITY = "lenscast"
    }
}

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
    /** Live torch state, so the dashboard's toggles mirror the device instead of guessing. */
    val torchOn: Boolean = false,
    /** The persisted zoom ratio the camera is currently applying. */
    val zoomRatio: Double = 1.0,
    /** The selected lens's camera id and label; null before the lens enumeration lands. */
    val lensId: String? = null,
    val lensLabel: String? = null,
    /** The device's live control ranges, so web sliders stop hardcoding bounds. */
    val zoomRange: RangeDto? = null,
    val exposureCompensationRange: RangeDto? = null,
    val isoRange: RangeDto? = null,
    val adaptiveBitrate: AdaptiveBitrateStatusDto? = null,
    val connectionQuality: ConnectionQualityStatusDto? = null,
    val watchdog: WatchdogStatusDto? = null,
)

/** A device control range, min inclusive / max inclusive. */
data class RangeDto(
    val min: Double,
    val max: Double,
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

// ── Detection Test-Alert DTOs ──

/**
 * The POST /api/detection/test answer: which alert sinks actually dispatched
 * the synthetic test alert (the same action names the event log uses —
 * `webhook`, `mqtt`, `notify`), so the dashboard can show a per-channel
 * verdict instead of a bare success flag.
 */
data class DetectionTestResponseDto(
    val success: Boolean = true,
    val dispatchedActions: List<String> = emptyList(),
)

// ── Audit Log DTOs ──

/** One audited action: a write-route dispatch or a login outcome. */
data class AuditEntryDto(
    val timestampMs: Long,
    /** The action verb — `"$method $path"` for a route dispatch, `login.success` / `login.failed`. */
    val action: String,
    /** Human-readable context: the error message for failures, the remote address for logins. */
    val detail: String = "",
    /** `ok` or `error`. */
    val outcome: String = "ok",
)

data class AuditLogResponseDto(
    val entries: List<AuditEntryDto>,
    val total: Int,
)

// ── System Info DTOs ──

/**
 * GET /api/system — the headless-triage snapshot: what build is running, on
 * what device, for how long, and how the battery and storage are holding up.
 * Read-only; nothing here is writable.
 */
data class SystemInfoResponseDto(
    val appVersion: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    /** Ms since the OS booted (elapsedRealtime) — the live session's age. */
    val osUptimeMs: Long,
    /** Ms since this process started; 0 when the platform cannot tell. */
    val processUptimeMs: Long,
    val battery: BatteryDetailDto,
    val storage: StorageInfoDto,
)

/** Battery facts beyond the status DTO's level/charging trio. */
data class BatteryDetailDto(
    val level: Int,
    val isCharging: Boolean,
    /** Tenths of a degree Celsius, as the platform reports it; null when absent. */
    val temperatureTenthsC: Int?,
    /** Millivolts; null when absent. */
    val voltageMillivolts: Int?,
    /** The platform's health constant name ("good", "overheat", ...); null when unknown. */
    val health: String?,
)

/** App-volume storage facts: configured quota plus live volume usage. */
data class StorageInfoDto(
    /** LensCast media bytes tracked by the capture history. */
    val usedBytes: Long,
    /** The configured capture quota in bytes. */
    val quotaBytes: Long,
    /** Free bytes on the app's storage volume. */
    val freeBytes: Long,
    /** Total bytes on the app's storage volume. */
    val totalBytes: Long,
)

// ── Detection Stats DTOs ──

/**
 * GET /api/detection/stats — aggregate counts over the persisted event log:
 * per-type totals across three windows, a per-day series for the recent
 * stretch, and the most-fired zones/labels. Read-only.
 */
data class DetectionStatsResponseDto(
    /** Event counts per wire-name type, per window. */
    val last24h: Map<String, Int>,
    val last7d: Map<String, Int>,
    val allTime: Map<String, Int>,
    /** Total events per UTC day ("yyyy-MM-dd"), oldest first, for the recent window. */
    val perDay: List<DailyCountDto>,
    /** Total events in the log right now (the store's capped list). */
    val totalEvents: Int,
    /** The most-fired motion-zone labels, most first. */
    val topZones: List<LabeledCountDto>,
    /** The most-seen ML class labels, most first. */
    val topLabels: List<LabeledCountDto>,
)

data class DailyCountDto(
    /** UTC day, "yyyy-MM-dd". */
    val day: String,
    val count: Int,
)

data class LabeledCountDto(
    val label: String,
    val count: Int,
)

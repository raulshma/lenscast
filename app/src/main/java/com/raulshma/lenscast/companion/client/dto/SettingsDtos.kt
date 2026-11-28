package com.raulshma.lenscast.companion.client.dto

import com.squareup.moshi.JsonClass

/**
 * The companion client's settings-surface DTOs: faithful Kotlin
 * transcriptions of the web dashboard's settings document (web/src/types.ts
 * `AllSettings` → `CameraSettings` / `StreamingSettings`, and the
 * `Partial<AllSettings>` PUT body), decoded from the same `GET /api/settings`
 * answer and re-encoded for `PUT /api/settings`. Field names are IDENTICAL to
 * the TS wire names (camelCase) so Moshi maps 1:1 with no `@Json` renames;
 * every field carries a default so an older device that omits an additive
 * field decodes cleanly — and so a PUT of a section echoes every field the
 * section carries (the server persists a present section WHOLESALE: omitted
 * fields take the server defaults, not the stored values — the web console's
 * "a partial object would reset the other fields to their defaults" rule,
 * which is why every save is a GET-merge-PUT of the full section).
 *
 * Naming: three TS names are prefixed `Remote` because the plain name already
 * exists as an app-side Kotlin class (`camera.model.CameraSettings`,
 * `camera.model.MotionZone`, `camera.model.MaskingZone`) — the same
 * collision convention CoreDtos established for `RemoteDetectionEvent` /
 * `RemoteAuditEntry`. Everything else keeps the TS name; the server's own
 * DTOs carry a `Dto` suffix and never collide.
 *
 * Secrets ride the wire as plain strings: the server serializes every
 * write-only credential blank in GET responses (the `Secret` wire type), and
 * an empty value on PUT means "keep the stored credential" — so echoing a
 * fetched document back with its blank secrets is exactly the keep contract,
 * and the companion can never echo a stored secret it never received.
 */

/**
 * The camera half of GET /api/settings (TS `CameraSettings`). The companion
 * decodes it to carry the document whole but never PUTs it: the settings
 * update omits the `camera` key, which the server decodes as "leave the
 * camera section untouched".
 */
@JsonClass(generateAdapter = true)
data class RemoteCameraSettings(
    val exposureCompensation: Int = 0,
    val iso: Int? = null,
    val exposureTime: Long? = null,
    val focusMode: String = "AUTO",
    val focusDistance: Double? = null,
    val whiteBalance: String = "AUTO",
    val colorTemperature: Int? = null,
    val zoomRatio: Double = 1.0,
    val frameRate: Int = 24,
    val resolution: String = "FHD_1080P",
    val stabilization: Boolean = true,
    val hdrMode: String = "OFF",
    val sceneMode: String? = null,
    val nightVisionMode: String = "OFF",
    val outputRotation: Int = 0,
    val orientationLocked: Boolean = false,
)

/**
 * One masking zone inside the settings document's overlay block (TS
 * `MaskingZone`). Echoed back untouched on PUT; the companion's v1 editor
 * does not edit zones. `type` is `BLACKOUT` | `PIXELATE` | `BLUR`.
 */
@JsonClass(generateAdapter = true)
data class RemoteMaskingZone(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = false,
    val type: String = "BLACKOUT",
    /** Normalized 0..1 frame-space rectangle. */
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
    val pixelateSize: Int = 0,
    val blurRadius: Double = 0.0,
)

/**
 * One motion zone inside the settings document (TS `MotionZone`). Echoed
 * back untouched on PUT; the companion's v1 editor does not edit zones.
 */
@JsonClass(generateAdapter = true)
data class RemoteMotionZone(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = false,
    /** Normalized 0..1 frame-space rectangle. */
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
)

/**
 * The streaming half of GET /api/settings (TS `StreamingSettings`) — the
 * whole device-side settings document in one flat block: stream outputs,
 * overlay/masking, motion/sound/ML detection, alerts, integrations, storage.
 * Every field the server's DTO carries is mirrored here with a default, so
 * the PUT round trip (a full-section echo) never drops a field the server
 * would otherwise reset to its default.
 */
@JsonClass(generateAdapter = true)
data class StreamingSettings(
    val port: Int = 8080,
    val webStreamingEnabled: Boolean = true,
    val jpegQuality: Int = 70,
    val showPreview: Boolean = true,
    val streamAudioEnabled: Boolean = true,
    val streamAudioBitrateKbps: Int = 128,
    val streamAudioChannels: Int = 1,
    val streamAudioEchoCancellation: Boolean = true,
    val recordingAudioEnabled: Boolean = true,
    val rtspEnabled: Boolean = false,
    val rtspPort: Int = 8554,
    /**
     * Send RTSP Basic auth instead of Digest when the client cannot speak
     * Digest (the legacy-compat knob). Additive to this transcription
     * (P6): the server DTO and the web console carry it; the app settings
     * screen edits it.
     */
    val rtspBasicAuthCompat: Boolean = false,
    /** `AUTO` | `NV21` | `NV12` | `I420`. */
    val rtspInputFormat: String = "AUTO",
    /** `480p` | `720p` | `720p43` | `1080p` | `1080p43`. */
    val rtspResolution: String = "720p",
    /** `h264` | `h265`. */
    val rtspVideoCodec: String = "h264",
    val rtmpEnabled: Boolean = false,
    /** Write-only: GET is blank, an empty PUT keeps the stored URL. */
    val rtmpUrl: String = "",
    val whipEnabled: Boolean = false,
    val whipUrl: String = "",
    /** Write-only: GET is blank, an empty PUT keeps the stored token. */
    val whipToken: String = "",
    val whipStunServer: String = "",
    val srtEnabled: Boolean = false,
    /** Write-only: GET is blank, an empty PUT keeps the stored URL. */
    val srtUrl: String = "",
    val adaptiveBitrateEnabled: Boolean = false,
    val adaptiveEncodedBitrateEnabled: Boolean = false,
    val rtspSubStreamEnabled: Boolean = false,
    val hlsDvrSegments: Int = 0,
    val overlayEnabled: Boolean = false,
    val showTimestamp: Boolean = true,
    val timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
    val showBranding: Boolean = false,
    val brandingText: String = "LensCast",
    val showStatus: Boolean = false,
    val showIlluminance: Boolean = false,
    val showCustomText: Boolean = false,
    val customText: String = "",
    /** `TOP_LEFT` | `TOP_RIGHT` | `BOTTOM_LEFT` | `BOTTOM_RIGHT`. */
    val overlayPosition: String = "TOP_LEFT",
    val overlayFontSize: Int = 28,
    val overlayTextColor: String = "#FFFFFF",
    val overlayBackgroundColor: String = "#80000000",
    val overlayPadding: Int = 8,
    val overlayLineHeight: Int = 4,
    val maskingEnabled: Boolean = false,
    val maskingZones: List<RemoteMaskingZone> = emptyList(),
    val watchdogEnabled: Boolean = false,
    val watchdogMaxRetries: Int = 5,
    val watchdogCheckIntervalSeconds: Int = 5,
    val mdnsEnabled: Boolean = true,
    val motionDetectionEnabled: Boolean = false,
    val motionSensitivityPercent: Int = 50,
    val motionZones: List<RemoteMotionZone> = emptyList(),
    val motionRecordingEnabled: Boolean = false,
    val motionPostRollSeconds: Int = 10,
    val motionArmScheduleEnabled: Boolean = false,
    /** Minute of day, 0..1439. */
    val motionArmStartMinute: Int = 0,
    val motionArmEndMinute: Int = 1_439,
    /**
     * The arm schedule's day-of-week mask: bit 0 = Monday … bit 6 = Sunday
     * (127 = every day).
     */
    val motionArmDaysMask: Int = 127,
    val soundDetectionEnabled: Boolean = false,
    val soundThresholdPercent: Int = 30,
    val soundAdaptiveNoiseFloor: Boolean = false,
    val soundRecordingEnabled: Boolean = false,
    val motionCooldownSeconds: Int = 10,
    val soundCooldownSeconds: Int = 10,
    val soundClassificationEnabled: Boolean = false,
    val soundClassificationConfidencePercent: Int = 60,
    val soundClassificationAllowedClasses: List<String> = emptyList(),
    val soundTriggerEnabled: Boolean = false,
    val soundTriggerClasses: List<String> = emptyList(),
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val webhookHeaders: String = "",
    val autoSiren: Boolean = false,
    val autoTorch: Boolean = false,
    val sirenDurationSeconds: Int = 10,
    val autoDeterrenceCooldownSeconds: Int = 60,
    val backupEnabled: Boolean = false,
    val backupWifiOnly: Boolean = true,
    /** `webdav` | `telegram`. */
    val backupTarget: String = "webdav",
    val backupWebdavUrl: String = "",
    val backupWebdavUsername: String = "",
    /** Write-only: GET is blank, an empty PUT keeps the stored password. */
    val backupWebdavPassword: String = "",
    val telegramChatId: String = "",
    /** Write-only: GET is blank, an empty PUT keeps the stored token. */
    val telegramBotToken: String = "",
    val apiTokenEnabled: Boolean = false,
    /** True when a token hash is stored server-side; the token never round-trips. */
    val apiTokenConfigured: Boolean = false,
    /** Write-only plaintext token: hashed server-side on save, never returned. */
    val apiToken: String = "",
    val httpsEnabled: Boolean = false,
    val audioDeviceId: String = "",
    val detectionNotificationsEnabled: Boolean = true,
    val alertQuietHoursEnabled: Boolean = false,
    /** Minute of day, 0..1439; defaults 22:00→07:00. */
    val alertQuietHoursStartMinute: Int = 1_320,
    val alertQuietHoursEndMinute: Int = 420,
    val keepAwakeEnabled: Boolean = true,
    val tamperDetectionEnabled: Boolean = false,
    val mqttEnabled: Boolean = false,
    val mqttBrokerHost: String = "",
    val mqttBrokerPort: Int = 1883,
    val mqttUsername: String = "",
    /** Write-only: GET is blank, an empty PUT keeps the stored password. */
    val mqttPassword: String = "",
    val mqttTls: Boolean = false,
    val mqttDiscoveryPrefix: String = "homeassistant",
    val mqttTelemetryEnabled: Boolean = false,
    val pushEnabled: Boolean = false,
    val unifiedPushEnabled: Boolean = false,
    /** Capture retention window in days; 0 keeps captures forever. */
    val captureRetentionDays: Int = 0,
    /** Detection-event retention window in days; 0 keeps events forever. */
    val eventRetentionDays: Int = 0,
    /** The capture-history storage quota in MB (100 MB–32 GB). */
    val storageQuotaMb: Int = 2_048,
    val mediaEncryptionEnabled: Boolean = false,
    val mlDetectionEnabled: Boolean = false,
    val mlMinScorePercent: Int = 60,
    val mlIncludePerson: Boolean = true,
    val mlIncludePets: Boolean = true,
    val mlIncludeVehicles: Boolean = true,
    /** Response-only: `not_downloaded` | `downloading` | `ready` | `failed`. */
    val mlModelState: String = "not_downloaded",
    /** Response-only: download progress 0..1; -1 when none is running. */
    val mlModelProgress: Double = -1.0,
    /** Response-only: failure reason when [mlModelState] is `failed`. */
    val mlModelError: String = "",
    val continuousRecording: Boolean = false,
    val continuousSegmentMinutes: Int = 15,
    val onvifEnabled: Boolean = false,
    val ecoIdleFpsEnabled: Boolean = false,
)

/**
 * GET /api/settings — the whole settings document (TS `AllSettings`).
 */
@JsonClass(generateAdapter = true)
data class AllSettings(
    val camera: RemoteCameraSettings,
    val streaming: StreamingSettings,
)

/**
 * PUT /api/settings — the update body (TS `Partial<AllSettings>`). Both
 * sections are nullable and Moshi omits nulls from the wire, so a null
 * section means "leave that section untouched" server-side; a PRESENT
 * section persists wholesale (every field of it, omitted ones taking the
 * server defaults — never send a hand-built partial section).
 */
@JsonClass(generateAdapter = true)
data class SettingsUpdate(
    val camera: RemoteCameraSettings? = null,
    val streaming: StreamingSettings? = null,
)

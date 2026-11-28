package com.raulshma.lenscast.companion.client.dto

import com.squareup.moshi.JsonClass

/**
 * The companion client's wire DTOs: faithful Kotlin transcriptions of the
 * web dashboard's TypeScript mirrors (web/src/types.ts + the auth response
 * shapes in web/src/api/client.ts). Field names are IDENTICAL to the TS wire
 * names (camelCase) so Moshi maps 1:1 with no @Json renames; nullability and
 * defaults follow the TS optionality, so an older device that omits an
 * additive field decodes cleanly. The checked-in web/contract fixtures
 * pin these shapes — CoreDtosFixtureTest fails on any drift.
 *
 * Two names are prefixed `Remote` because the plain TS name already exists
 * as an app-side Kotlin class the companion UI imports alongside these:
 * `DetectionEvent` (capture.DetectionEventStore) and `AuditEntry`
 * (streaming.web.AuditLog). Everything else keeps the TS name; the server's
 * own DTOs carry a `Dto` suffix and never collide.
 */

/** A device control range, min inclusive / max inclusive (GET /api/status). */
@JsonClass(generateAdapter = true)
data class ControlRange(
    val min: Double,
    val max: Double,
)

/** The streaming block of GET /api/status — every output's live state. */
@JsonClass(generateAdapter = true)
data class StreamingStatus(
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
    /** The RTMP push output; absent on older devices (pre-RTMP firmware). */
    val rtmpEnabled: Boolean? = null,
    val rtmpActive: Boolean? = null,
    /** `idle` | `connecting` | `connected` | `error`. */
    val rtmpStatus: String? = null,
    val rtmpError: String? = null,
    /** The WHIP push output; absent on older devices (pre-WHIP firmware). */
    val whipEnabled: Boolean? = null,
    val whipActive: Boolean? = null,
    val whipStatus: String? = null,
    val whipError: String? = null,
    /** The SRT push output; absent on older devices (pre-SRT firmware). */
    val srtEnabled: Boolean? = null,
    val srtActive: Boolean? = null,
    val srtStatus: String? = null,
    val srtError: String? = null,
    /** The SRT push's measured round-trip time in ms; absent while not connected. */
    val srtRttMs: Int? = null,
    /** The live WHEP (WebRTC viewer) session count; absent on pre-WHEP firmware. */
    val whepClients: Int? = null,
)

/** The battery trio inside GET /api/status. */
@JsonClass(generateAdapter = true)
data class BatteryStatus(
    val level: Int,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
)

/** The adaptive-bitrate block of GET /api/status. */
@JsonClass(generateAdapter = true)
data class AdaptiveBitrateStatus(
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

/** One web-stream client's throughput detail inside [ConnectionQualityStatus]. */
@JsonClass(generateAdapter = true)
data class ClientConnectionDetail(
    val framesSent: Long,
    val bytesSent: Long,
    val avgThroughputKbps: Int,
    val lastFrameSizeBytes: Int,
    val lastSendDurationMs: Long,
)

/** The per-client connection-quality block of GET /api/status. */
@JsonClass(generateAdapter = true)
data class ConnectionQualityStatus(
    val qualityLevel: String,
    val estimatedBandwidthKbps: Int,
    val avgThroughputKbps: Int,
    val minThroughputKbps: Int,
    val worstLatencyMs: Long,
    val avgFrameSizeBytes: Int,
    val totalBytesSent: Long,
    val activeClients: Int,
    val framesPerSecond: Double,
    /** Keyed by the client's `address:port`. */
    val clientDetails: Map<String, ClientConnectionDetail>,
)

/** The watchdog block of GET /api/status. */
@JsonClass(generateAdapter = true)
data class WatchdogStatus(
    val enabled: Boolean,
    val status: String,
    val consecutiveFailures: Int,
    val totalRecoveries: Int,
    val lastRecoveryTimestamp: Long,
    val lastFailureReason: String? = null,
)

/** GET /api/status — the dashboard poll: stream outputs, power, camera controls. */
@JsonClass(generateAdapter = true)
data class DeviceStatus(
    val streaming: StreamingStatus,
    /** The platform thermal constant name — `NORMAL` | `LIGHT` | `MODERATE` | `SEVERE` | `CRITICAL`. */
    val thermal: String,
    val battery: BatteryStatus,
    val camera: String,
    /** Live torch state, so dashboard toggles mirror the device. */
    val torchOn: Boolean,
    /** Live siren state, so dashboard toggles mirror the device. */
    val sirenActive: Boolean,
    /**
     * A live stream's camera bind is being denied because the device is locked;
     * absent on pre-flag firmware, false when frames are flowing.
     */
    val cameraBlockedByLock: Boolean? = null,
    /** The zoom ratio the camera is currently applying. */
    val zoomRatio: Double,
    /** The selected lens; absent before the lens enumeration lands. */
    val lensId: String? = null,
    val lensLabel: String? = null,
    /** The device's live control ranges, so sliders stop hardcoding bounds. */
    val zoomRange: ControlRange? = null,
    val exposureCompensationRange: ControlRange? = null,
    val isoRange: ControlRange? = null,
    val adaptiveBitrate: AdaptiveBitrateStatus? = null,
    val connectionQuality: ConnectionQualityStatus? = null,
    val watchdog: WatchdogStatus? = null,
    /** The encoded pipeline's effective target bitrate in bps. */
    val encodedVideoBitrate: Int? = null,
    /** Smoothed ambient illuminance; absent on older firmware or while unsampled. */
    val illuminanceLux: Int? = null,
    /** Whether the paired device exposes an ambient-light sensor. */
    val lightSensorAvailable: Boolean? = null,
)

/** The battery detail block of GET /api/system. */
@JsonClass(generateAdapter = true)
data class BatteryDetail(
    val level: Int,
    val isCharging: Boolean,
    /** Tenths of a degree Celsius; absent when the platform reports none. */
    val temperatureTenthsC: Int? = null,
    val voltageMillivolts: Int? = null,
    val health: String? = null,
    /** Charge counter in mAh; absent when the device does not report it. */
    val batteryChargeCounterMah: Int? = null,
    /** Instant current in µA, signed as the platform reports it; absent when unsupported. */
    val batteryCurrentMicroAmps: Int? = null,
    /** Charge-cycle count (API 34+); absent below Android 14 or untracked. */
    val batteryCycleCount: Int? = null,
)

/** The storage block of GET /api/system. */
@JsonClass(generateAdapter = true)
data class StorageInfo(
    val usedBytes: Long,
    val quotaBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** GET /api/system — the read-only diagnostics snapshot for a headless phone. */
@JsonClass(generateAdapter = true)
data class SystemInfo(
    val appVersion: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    /** Ms since the OS booted (elapsedRealtime). */
    val osUptimeMs: Long,
    /** Ms since this process started; 0 when the platform cannot tell. */
    val processUptimeMs: Long,
    val battery: BatteryDetail,
    val storage: StorageInfo,
    /** Null below API 31 (no exact-alarm grant model) or on older firmware. */
    val canScheduleExactAlarms: Boolean? = null,
)

/**
 * One camera lens on GET /api/camera/lenses. [kind] is the backend's
 * LensLabelKind display role (optional — a stale cached dashboard against an
 * older device falls back to [label]); [zoomRatio] carries the relative
 * magnification for `ZOOM` lenses.
 */
@JsonClass(generateAdapter = true)
data class LensInfo(
    val index: Int,
    val id: String,
    val label: String,
    val kind: String? = null,
    val focalLength: Double,
    /** Relative magnification for ZOOM lenses; absent on pre-generic firmware. */
    val zoomRatio: Double? = null,
    val isFront: Boolean,
    val selected: Boolean,
)

/** GET /api/camera/lenses — the enumerated lens list plus the selection. */
@JsonClass(generateAdapter = true)
data class LensesResponse(
    val lenses: List<LensInfo>,
    val selectedIndex: Int,
)

/** One connected RTSP session, additive to the /api/stream/clients payload. */
@JsonClass(generateAdapter = true)
data class RtspClient(
    val id: String,
    val remoteAddress: String,
    val connectedAtMs: Long,
    val transport: String,
    /** SETUPed media: `video`, `audio`, and/or the sub-stream's `sub`. */
    val media: List<String> = emptyList(),
    val playing: Boolean = false,
    val framesSent: Long = 0,
)

/** GET /api/stream/clients — the HTTP viewer roster and RTSP session list. */
@JsonClass(generateAdapter = true)
data class StreamClientsInfo(
    val httpClients: List<String>,
    val httpCount: Int,
    val rtspCount: Int,
    val maxHttp: Int,
    val rtspClients: List<RtspClient> = emptyList(),
)

/**
 * One detection event on GET /api/detection/events (and the SSE event feed).
 * `Remote`-prefixed: the plain TS name is the capture package's own
 * [com.raulshma.lenscast.capture.DetectionEvent], which companion UI files
 * import alongside this wire type.
 */
@JsonClass(generateAdapter = true)
data class RemoteDetectionEvent(
    val id: String,
    /** `motion` | `sound` | `tamper`. */
    val type: String,
    val source: String,
    val timestampMs: Long,
    val snapshotJpegBase64: String? = null,
    val dispatchedActions: List<String> = emptyList(),
    /** Labels of the motion zones that fired; empty for whole-frame or non-motion events. */
    val zones: List<String> = emptyList(),
    /** ML class labels (person, dog, car...) attached by the object-detection gate. */
    val labels: List<String> = emptyList(),
    /** MediaStore numeric id of the motion clip once its bounded recording finalized; absent until (or unless) linked. */
    val clipMediaId: Long? = null,
    /** File name of the motion clip, linked together with [clipMediaId]. */
    val clipFileName: String? = null,
    /** The dashboard deep link for the event: `#/gallery/<clipMediaId>` once linked, else `#/events`. */
    val url: String? = null,
)

/** GET /api/detection/events — the event-log page. */
@JsonClass(generateAdapter = true)
data class DetectionEventsResponse(
    val events: List<RemoteDetectionEvent>,
    val total: Int,
)

/** One per-UTC-day total in the detection stats series. */
@JsonClass(generateAdapter = true)
data class DailyCount(
    val day: String,
    val count: Int,
)

/** One zone/label count row in the detection stats. */
@JsonClass(generateAdapter = true)
data class LabeledCount(
    val label: String,
    val count: Int,
)

/** GET /api/detection/stats — aggregate counts over the persisted event log. */
@JsonClass(generateAdapter = true)
data class DetectionStats(
    val last24h: Map<String, Int>,
    val last7d: Map<String, Int>,
    val allTime: Map<String, Int>,
    val perDay: List<DailyCount>,
    val totalEvents: Int,
    val topZones: List<LabeledCount>,
    val topLabels: List<LabeledCount>,
)

/** One gallery capture (photo or video) on GET /api/gallery. */
@JsonClass(generateAdapter = true)
data class GalleryItem(
    val id: String,
    /** `PHOTO` | `VIDEO`. */
    val type: String,
    val fileName: String,
    val timestamp: Long,
    val fileSizeBytes: Long,
    val durationMs: Long,
    /** The user-marked favorite flag (app gallery star). */
    val favorite: Boolean,
    val thumbnailUrl: String,
    /** Full-size media route — the viewer's source for photos. */
    val url: String,
    val downloadUrl: String,
)

/** GET /api/gallery — one page of the remote capture library. */
@JsonClass(generateAdapter = true)
data class GalleryResponse(
    val items: List<GalleryItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
)

/** GET /api/recording/status — the manual/scheduled recording state. */
@JsonClass(generateAdapter = true)
data class RecordingStatus(
    val isRecording: Boolean,
    val elapsedSeconds: Int,
    val isScheduled: Boolean? = null,
    val scheduledStartTimeMs: Long? = null,
)

/**
 * One NVR recording session overlapping the requested day on
 * GET /api/recordings/sessions; `endMs` may sit past midnight for cross-day
 * sessions.
 */
@JsonClass(generateAdapter = true)
data class RecordingSession(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** `manual` | `motion` | `sound` | `continuous` | `scheduled` | `interval`. */
    val trigger: String,
    /** MediaStore id of the finalized clip; null while (or if) none is linked. */
    val mediaId: String? = null,
)

/** GET /api/recordings/sessions?day=YYYY-MM-DD — the day's recording sessions. */
@JsonClass(generateAdapter = true)
data class RecordingsResponse(
    val sessions: List<RecordingSession>,
)

/** GET /api/auth/status — whether the remote requires a sign-in at all. */
@JsonClass(generateAdapter = true)
data class AuthStatusInfo(
    val required: Boolean,
)

/** GET /api/auth/session — whether the held session cookie is live, and its role. */
@JsonClass(generateAdapter = true)
data class AuthSessionInfo(
    val authenticated: Boolean,
    /** `"admin"` | `"viewer"`; absent in pre-role responses — treated as admin. */
    val role: String? = null,
)

/** POST /api/auth/login — the probe/sign-in answer, carrying the minted session's role. */
@JsonClass(generateAdapter = true)
data class LoginResponse(
    val success: Boolean,
    val required: Boolean? = null,
    /** `"admin"` | `"viewer"`, so the UI can gate immediately after sign-in. */
    val role: String? = null,
)

/**
 * One audited action on GET /api/audit. `Remote`-prefixed: the plain TS name
 * is the server's own [com.raulshma.lenscast.streaming.web.AuditEntry], which
 * the system tab imports alongside this wire type.
 */
@JsonClass(generateAdapter = true)
data class RemoteAuditEntry(
    val timestampMs: Long,
    /** `"POST /api/settings"`, `"login.failed"`, … */
    val action: String,
    val detail: String = "",
    /** `ok` or `error`. */
    val outcome: String = "ok",
)

/** GET /api/audit — the audit trail page. */
@JsonClass(generateAdapter = true)
data class AuditResponse(
    val entries: List<RemoteAuditEntry>,
    val total: Int,
)

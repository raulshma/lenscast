export type FocusMode = 'AUTO' | 'MANUAL' | 'MACRO' | 'CONTINUOUS_PICTURE' | 'CONTINUOUS_VIDEO'
export type WhiteBalance = 'AUTO' | 'DAYLIGHT' | 'CLOUDY' | 'INDOOR' | 'FLUORESCENT' | 'MANUAL'
export type Resolution = 'SD_480P' | 'HD_720P' | 'FHD_1080P' | 'QHD_1440P' | 'UHD_4K'
export type HdrMode = 'OFF' | 'ON' | 'AUTO'
export type NightVisionMode = 'OFF' | 'AUTO' | 'ON'
export type ThermalState = 'NORMAL' | 'LIGHT' | 'MODERATE' | 'SEVERE' | 'CRITICAL'
export type FlashMode = 'ON' | 'OFF' | 'AUTO'
export type RecordingQuality = 'HIGH' | 'MEDIUM' | 'LOW'
export type OverlayPosition = 'TOP_LEFT' | 'TOP_RIGHT' | 'BOTTOM_LEFT' | 'BOTTOM_RIGHT'
export type MaskingType = 'BLACKOUT' | 'PIXELATE' | 'BLUR'
export type RtspInputFormat = 'AUTO' | 'NV21' | 'NV12' | 'I420'
/** The RTSP output resolution wire names, mirroring the server's RtspResolution mapper. */
export type RtspResolution = '480p' | '720p' | '1080p'

/** The RTSP output codec wire names, mirroring the server's RtspVideoCodec mapper. */
export type RtspVideoCodec = 'h264' | 'h265'

// Stream auth contract: the web client sends the password as PLAINTEXT over
// the wire (e.g. POST /api/auth/login {username, password}); the server never
// stores or returns it — at rest it keeps only passwordHash and an RTSP
// digest HA1, and verifies logins against the hash.
//
// API token contract (same write-only pattern): the dashboard generates a
// random token client-side and sends it ONCE as the write-only `apiToken`
// field of StreamingSettings; the server stores only its SHA-256 hex hash and
// never returns the token or the hash — responses carry `apiTokenEnabled` and
// `apiTokenConfigured` instead. The token authorizes GET/HEAD requests and
// POST on the server's fixed write allow-list (stream/recording start-stop,
// photo capture, siren/torch deterrence — never /api/auth/*) via
// `Authorization: Bearer` or `X-Api-Token`.

export interface StreamAuthSettings {
  enabled: boolean
  username: string
  password: string
}

export interface MaskingZone {
  id: string
  label: string
  enabled: boolean
  type: MaskingType
  x: number
  y: number
  width: number
  height: number
  pixelateSize: number
  blurRadius: number
}

export interface MotionZone {
  id: string
  label: string
  enabled: boolean
  x: number
  y: number
  width: number
  height: number
}

export interface CameraSettings {
  exposureCompensation: number
  iso: number | null
  exposureTime: number | null
  focusMode: FocusMode
  focusDistance: number | null
  whiteBalance: WhiteBalance
  colorTemperature: number | null
  zoomRatio: number
  frameRate: number
  resolution: Resolution
  stabilization: boolean
  hdrMode: HdrMode
  sceneMode: string | null
  nightVisionMode: NightVisionMode
  outputRotation: OutputRotation
  orientationLocked: boolean
}

// The output-orientation correction for a mounted phone (issue #6) —
// clockwise degrees applied to every output (streams, snapshots, recordings).
export type OutputRotation = 0 | 90 | 180 | 270

export const OUTPUT_ROTATION_OPTIONS: OutputRotation[] = [0, 90, 180, 270]

// The backup destinations, matching the server's BackupTargetPolicy wire names.
export type BackupTarget = 'webdav' | 'telegram'

export interface StreamingSettings {
  port: number
  webStreamingEnabled: boolean
  jpegQuality: number
  showPreview: boolean
  streamAudioEnabled: boolean
  streamAudioBitrateKbps: number
  streamAudioChannels: number
  streamAudioEchoCancellation: boolean
  recordingAudioEnabled: boolean
  rtspEnabled: boolean
  rtspPort: number
  rtspInputFormat: RtspInputFormat
  rtspResolution: RtspResolution
  /** RTSP output codec: 'h264' (default, full HLS/WebCodecs compatibility) or 'h265'. */
  rtspVideoCodec: RtspVideoCodec
  /**
   * The RTMP push output (publish to an RTMP/RTMPS server). The server
   * judges the push URL (scheme, host, H.264 codec) at start time, not on
   * save.
   */
  rtmpEnabled: boolean
  /**
   * The push target: `rtmp(s)://[user:pass@]host[:port]/app/streamKey`.
   * Write-only, like whipToken: the URL embeds the stream key, so PUT
   * carries it and responses are blank (an empty PUT keeps the stored one).
   */
  rtmpUrl: string
  /**
   * The WHIP push output (WebRTC-HTTP egress, RFC 9725). The endpoint
   * round-trips over the Web API and carries no embedded secret; the
   * bearer token rides whipToken.
   */
  whipEnabled: boolean
  /** The WHIP endpoint: `http(s)://[user:pass@]host[:port]/endpoint`. */
  whipUrl: string
  /** Write-only, like mqttPassword: PUT carries it, responses are blank. */
  whipToken: string
  /**
   * The one STUN server for one-shot ICE gathering (`host[:port]`); blank
   * means no iceServers — host candidates only, i.e. LAN-only reachability.
   */
  whipStunServer: string
  /**
   * The SRT push output (publish MPEG-TS over SRT to an `srt://` listener).
   * H.264 only; the server judges the URL at start time.
   */
  srtEnabled: boolean
  /**
   * The SRT push target: `srt://[user:pass@]host[:port][?streamid=…]`.
   * Write-only, like rtmpUrl: the userinfo and stream id are credentials,
   * so PUT carries it and responses are blank (an empty PUT keeps the
   * stored one).
   */
  srtUrl: string
  adaptiveBitrateEnabled: boolean
  /**
   * The adaptive ENCODED-video bitrate: measured encoded-sink throughput
   * (RTSP/WS/HLS) + thermal scale the encoder's live target within its
   * configured bounds. Off by default like adaptiveBitrateEnabled.
   */
  adaptiveEncodedBitrateEnabled: boolean
  /** The RTSP low-res sub-stream (rtsp://…/sub) for NVR detect roles; a second encode, off by default. */
  rtspSubStreamEnabled: boolean
  /** The HLS DVR window in segments: 0 = sliding live playlist, >0 = EVENT with seekable history (~2s per segment). */
  hlsDvrSegments: number
  overlayEnabled: boolean
  showTimestamp: boolean
  timestampFormat: string
  showBranding: boolean
  brandingText: string
  showStatus: boolean
  showCustomText: boolean
  customText: string
  overlayPosition: OverlayPosition
  overlayFontSize: number
  overlayTextColor: string
  overlayBackgroundColor: string
  overlayPadding: number
  overlayLineHeight: number
  maskingEnabled: boolean
  maskingZones: MaskingZone[]
  watchdogEnabled: boolean
  watchdogMaxRetries: number
  watchdogCheckIntervalSeconds: number
  mdnsEnabled: boolean
  motionDetectionEnabled: boolean
  motionSensitivityPercent: number
  motionZones: MotionZone[]
  motionRecordingEnabled: boolean
  motionPostRollSeconds: number
  motionArmScheduleEnabled: boolean
  motionArmStartMinute: number
  motionArmEndMinute: number
  /**
   * The arm schedule's day-of-week mask: bit 0 = Monday … bit 6 = Sunday
   * (127 = every day, the default — the schedule stays time-of-day-only
   * unless a day is explicitly cleared).
   */
  motionArmDaysMask: number
  soundDetectionEnabled: boolean
  soundThresholdPercent: number
  /** Sound trigger threshold rides a tracked ambient noise floor (adaptive). */
  soundAdaptiveNoiseFloor: boolean
  /** Sound events start a bounded clip (the motion post-roll duration). */
  soundRecordingEnabled: boolean
  /** Minimum seconds between two motion events. */
  motionCooldownSeconds: number
  /** Minimum seconds between two sound events. */
  soundCooldownSeconds: number
  /** YAMNet sound classification on top of sound detection: annotate-only labels ride sound events. */
  soundClassificationEnabled: boolean
  /** Minimum YAMNet confidence percent for a window's top label to count. */
  soundClassificationConfidencePercent: number
  /** The user-narrowed YAMNet allow-list (exact AudioSet class names). */
  soundClassificationAllowedClasses: string[]
  /** YAMNet class triggers: a chosen class at/above the confidence floor fires an event of its own. */
  soundTriggerEnabled: boolean
  /** The chosen sound-trigger class set (exact AudioSet names; empty list means the curated default). */
  soundTriggerClasses: string[]
  webhookEnabled: boolean
  webhookUrl: string
  /** Custom POST headers as a JSON `{"Name": "value"}` map string. */
  webhookHeaders: string
  autoSiren: boolean
  autoTorch: boolean
  sirenDurationSeconds: number
  autoDeterrenceCooldownSeconds: number
  backupEnabled: boolean
  backupWifiOnly: boolean
  backupTarget: BackupTarget
  backupWebdavUrl: string
  backupWebdavUsername: string
  backupWebdavPassword: string
  telegramChatId: string
  /** Write-only, like backupWebdavPassword: PUT carries it, responses are blank. */
  telegramBotToken: string
  apiTokenEnabled: boolean
  /** True when a token hash is stored server-side; the secret never round-trips. */
  apiTokenConfigured: boolean
  /** Write-only plaintext token: hashed server-side on save, never returned. */
  apiToken: string
  httpsEnabled: boolean
  audioDeviceId: string
  detectionNotificationsEnabled: boolean
  /**
   * Quiet hours for local detection alerts: heads-up notifications are held
   * inside the window (webhook/MQTT keep firing). Defaults 22:00→07:00.
   */
  alertQuietHoursEnabled: boolean
  alertQuietHoursStartMinute: number
  alertQuietHoursEndMinute: number
  tamperDetectionEnabled: boolean
  mqttEnabled: boolean
  mqttBrokerHost: string
  mqttBrokerPort: number
  mqttUsername: string
  /** Write-only, like backupWebdavPassword: PUT carries it, responses are blank. */
  mqttPassword: string
  mqttTls: boolean
  mqttDiscoveryPrefix: string
  /**
   * MQTT telemetry (client events + periodic sensor readings: battery,
   * thermal, encoded bitrate, active clients) riding the alert publisher's
   * connection. Alerts and stream states are unaffected by this toggle.
   */
  mqttTelemetryEnabled: boolean
  /**
   * Web Push alerts to subscribed browsers (RFC 8291/8292): the master gate
   * on the phone's push dispatches. Subscriptions are browser-session state
   * through the /api/push routes, never part of this settings document.
   */
  pushEnabled: boolean
  /**
   * UnifiedPush alerts to paired receiver endpoints: an independent master
   * gate. Targets are managed through the /api/push/unifiedpush routes,
   * never part of this settings document.
   */
  unifiedPushEnabled: boolean
  /** Capture retention window in days; 0 keeps captures forever. */
  captureRetentionDays: number
  /** Detection-event retention window in days; 0 keeps events forever. */
  eventRetentionDays: number
  /** The capture-history storage quota in MB (100 MB–32 GB); oldest captures age out past it. */
  storageQuotaMb: number
  /**
   * Opt-in media-at-rest encryption (AES-256-GCM per file, one Keystore key).
   * Migration-free by design: flipping it never touches existing media — every
   * reader sniffs the per-file header, so plaintext and encrypted captures
   * coexist.
   */
  mediaEncryptionEnabled: boolean
  /** ML object-detection gate on top of motion detection. */
  mlDetectionEnabled: boolean
  /** Minimum ML confidence percent for a detected object to count. */
  mlMinScorePercent: number
  /** ML gate class groups: which detected classes count toward an alert. */
  mlIncludePerson: boolean
  mlIncludePets: boolean
  mlIncludeVehicles: boolean
  /** Response-only: on-demand detection model state. */
  mlModelState: MlModelState
  /** Response-only: model download progress 0..1; -1 when none is running. */
  mlModelProgress: number
  /** Response-only: failure reason when mlModelState is "failed". */
  mlModelError: string
  /** Continuous NVR-style loop recording (chained bounded segments). */
  continuousRecording: boolean
  continuousSegmentMinutes: number
  /** ONVIF Profile S device endpoint + WS-Discovery responder. */
  onvifEnabled: boolean
  /**
   * Eco idle-fps mode: while off charger, thermal NORMAL, and no stream
   * consumer connected, the frame rate drops to the eco floor and live-audio
   * encoding pauses; the first client, charger, or thermal event restores.
   */
  ecoIdleFpsEnabled: boolean
}

/** The DetectionModelStore wire names the settings DTO carries for mlModelState. */
export type MlModelState = 'not_downloaded' | 'downloading' | 'ready' | 'failed'

export interface AllSettings {
  camera: CameraSettings
  streaming: StreamingSettings
}

/** A device control range, min inclusive / max inclusive. */
export interface ControlRange {
  min: number
  max: number
}

export interface DeviceStatus {
  streaming: {
    isActive: boolean
    url: string
    webStreamingEnabled: boolean
    webStreamingActive: boolean
    clientCount: number
    audioEnabled: boolean
    audioUrl: string
    rtspEnabled: boolean
    rtspStreamingActive: boolean
    rtspUrl: string
    /** The RTMP push output; absent on older devices (pre-RTMP firmware). */
    rtmpEnabled?: boolean
    rtmpActive?: boolean
    rtmpStatus?: 'idle' | 'connecting' | 'connected' | 'error'
    rtmpError?: string | null
    /** The WHIP push output; absent on older devices (pre-WHIP firmware). */
    whipEnabled?: boolean
    whipActive?: boolean
    whipStatus?: 'idle' | 'connecting' | 'connected' | 'error'
    whipError?: string | null
    /** The SRT push output; absent on older devices (pre-SRT firmware). */
    srtEnabled?: boolean
    srtActive?: boolean
    srtStatus?: 'idle' | 'connecting' | 'connected' | 'error'
    srtError?: string | null
    /** The SRT push's measured round-trip time in ms (from full ACKs); absent while not connected. */
    srtRttMs?: number | null
    /** The live WHEP (WebRTC viewer) session count; absent on pre-WHEP firmware. */
    whepClients?: number
  }
  thermal: ThermalState
  battery: {
    level: number
    isCharging: boolean
    isPowerSaveMode: boolean
  }
  camera: string
  /** Live torch state, so dashboard toggles mirror the device. */
  torchOn: boolean
  /** Live siren state, so dashboard toggles mirror the device. */
  sirenActive: boolean
  /** The zoom ratio the camera is currently applying. */
  zoomRatio: number
  /** The selected lens; absent before the lens enumeration lands. */
  lensId?: string
  lensLabel?: string
  /** The device's live control ranges, so sliders stop hardcoding bounds. */
  zoomRange?: ControlRange
  exposureCompensationRange?: ControlRange
  isoRange?: ControlRange
  adaptiveBitrate?: {
    enabled: boolean
    qualityLevel: string
    currentQuality: number
    targetQuality: number
    currentFps: number
    targetFps: number
    estimatedBandwidthKbps: number
    minClientThroughputKbps: number
    activeClients: number
    adjustmentCount: number
  }
  connectionQuality?: ConnectionQualityStatus
  watchdog?: {
    enabled: boolean
    status: string
    consecutiveFailures: number
    totalRecoveries: number
    lastRecoveryTimestamp: number
    lastFailureReason: string | null
  }
  /** The encoded pipeline's effective target bitrate in bps (the adaptive encoded-bitrate ladder's current value). */
  encodedVideoBitrate?: number
}

/** One connected RTSP session, additive to the /api/stream/clients payload. */
export interface RtspClient {
  id: string
  remoteAddress: string
  connectedAtMs: number
  transport: string
  /** SETUPed media: 'video', 'audio', and/or the sub-stream's 'sub'. */
  media: string[]
  playing: boolean
  framesSent: number
}

export interface ClientConnectionDetail {
  framesSent: number
  bytesSent: number
  avgThroughputKbps: number
  lastFrameSizeBytes: number
  lastSendDurationMs: number
}

export interface ConnectionQualityStatus {
  qualityLevel: string
  estimatedBandwidthKbps: number
  avgThroughputKbps: number
  minThroughputKbps: number
  worstLatencyMs: number
  avgFrameSizeBytes: number
  totalBytesSent: number
  activeClients: number
  framesPerSecond: number
  clientDetails: Record<string, ClientConnectionDetail>
}

/**
 * Display role for a lens, from the backend's LensLabelKind — the chip label
 * is localized from this instead of the backend's English base label. Optional
 * so a stale cached dashboard against an older backend falls back to `label`.
 * ZOOM carries the relative magnification in `zoomRatio` (generic: 2x, 3.4x,
 * 10x — never a new enum value).
 */
export type LensKind =
  | 'FRONT'
  | 'BACK'
  | 'ULTRAWIDE'
  | 'WIDE'
  | 'ZOOM'
  | 'FOCAL_MM'
  | 'CAMERA_ID'

export interface LensInfo {
  index: number
  id: string
  label: string
  kind?: LensKind
  focalLength: number
  /** Relative magnification for ZOOM lenses; absent on pre-generic firmware. */
  zoomRatio?: number | null
  isFront: boolean
  selected: boolean
}

export interface LensesResponse {
  lenses: LensInfo[]
  selectedIndex: number
}

export const RESOLUTION_LABELS: Record<Resolution, string> = {
  SD_480P: '480p (720x480)',
  HD_720P: '720p (1280x720)',
  FHD_1080P: '1080p (1920x1080)',
  QHD_1440P: '1440p (2560x1440)',
  UHD_4K: '4K (3840x2160)',
}

export const FOCUS_MODE_LABELS: Record<FocusMode, string> = {
  AUTO: 'Auto',
  MANUAL: 'Manual',
  MACRO: 'Macro',
  CONTINUOUS_PICTURE: 'Continuous (Photo)',
  CONTINUOUS_VIDEO: 'Continuous (Video)',
}

export const WB_LABELS: Record<WhiteBalance, string> = {
  AUTO: 'Auto',
  DAYLIGHT: 'Daylight',
  CLOUDY: 'Cloudy',
  INDOOR: 'Indoor',
  FLUORESCENT: 'Fluorescent',
  MANUAL: 'Manual',
}

export const HDR_LABELS: Record<HdrMode, string> = {
  OFF: 'Off',
  ON: 'On',
  AUTO: 'Auto',
}

export const NIGHT_VISION_LABELS: Record<NightVisionMode, string> = {
  OFF: 'Off',
  AUTO: 'Auto',
  ON: 'IR On',
}

export const FRAME_RATE_OPTIONS = [15, 24, 25, 30, 60]

export const SCENE_MODE_OPTIONS = [
  { value: '', label: 'Auto' },
  { value: 'ACTION', label: 'Action' },
  { value: 'BARCODE', label: 'Barcode' },
  { value: 'BEACH', label: 'Beach' },
  { value: 'CANDLELIGHT', label: 'Candlelight' },
  { value: 'FACE_PRIORITY', label: 'Face Priority' },
  { value: 'FACE_PRIORITY_LOW_LIGHT', label: 'Face Priority (Low Light)' },
  { value: 'FIREWORKS', label: 'Fireworks' },
  { value: 'HDR', label: 'HDR' },
  { value: 'HIGH_SPEED_VIDEO', label: 'High Speed Video' },
  { value: 'LANDSCAPE', label: 'Landscape' },
  { value: 'NIGHT', label: 'Night' },
  { value: 'NIGHT_PORTRAIT', label: 'Night Portrait' },
  { value: 'PARTY', label: 'Party' },
  { value: 'PORTRAIT', label: 'Portrait' },
  { value: 'SNOW', label: 'Snow' },
  { value: 'SPORTS', label: 'Sports' },
  { value: 'STEADYPHOTO', label: 'Steady Photo' },
  { value: 'SUNSET', label: 'Sunset' },
  { value: 'THEATRE', label: 'Theatre' },
]

export const FLASH_MODE_LABELS: Record<FlashMode, string> = {
  OFF: 'Off',
  ON: 'On',
  AUTO: 'Auto',
}

export const RECORDING_QUALITY_LABELS: Record<RecordingQuality, string> = {
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
}

export interface IntervalCaptureConfig {
  intervalSeconds: number
  totalCaptures: number
  flashMode: FlashMode
}

export interface RecordingConfig {
  durationSeconds: number
  repeatIntervalSeconds: number
  quality: RecordingQuality
  includeAudio: boolean
  startTimeMs?: number | null
}

export interface IntervalCaptureStatus {
  isRunning: boolean
  completedCaptures: number
}

export interface RecordingStatus {
  isRecording: boolean
  elapsedSeconds: number
  isScheduled?: boolean
  scheduledStartTimeMs?: number | null
}

export type GalleryFilter = 'ALL' | 'PHOTO' | 'VIDEO'

export interface GalleryItem {
  id: string
  type: 'PHOTO' | 'VIDEO'
  fileName: string
  timestamp: number
  fileSizeBytes: number
  durationMs: number
  /** The user-marked favorite flag (app gallery star). */
  favorite: boolean
  thumbnailUrl: string
  /** Full-size media route — the viewer's source for photos. */
  url: string
  downloadUrl: string
}

export interface GalleryResponse {
  items: GalleryItem[]
  total: number
  page: number
  pageSize: number
  hasMore: boolean
}

export type DetectionEventType = 'motion' | 'sound' | 'tamper'

export interface DetectionEvent {
  id: string
  type: DetectionEventType
  source: string
  timestampMs: number
  snapshotJpegBase64?: string | null
  dispatchedActions: string[]
  /** Labels of the motion zones that fired; empty for whole-frame or non-motion events. */
  zones: string[]
  /** ML class labels (person, dog, car...) attached by the object-detection gate. */
  labels?: string[]
  /** MediaStore numeric id of the motion clip once its bounded recording finalized; absent until (or unless) linked. */
  clipMediaId?: number | null
  /** File name of the motion clip, linked together with clipMediaId. */
  clipFileName?: string | null
  /**
   * The dashboard deep link for the event: `#/gallery/<clipMediaId>` once a
   * clip is linked, else `#/events` — the same value push notifications and
   * the MQTT/webhook bodies carry.
   */
  url?: string | null
}

export interface DetectionEventsResponse {
  events: DetectionEvent[]
  total: number
}

export interface DetectionTestResponse {
  success: boolean
  /** The alert sinks that actually dispatched the synthetic test alert. */
  dispatchedActions: string[]
}

/** GET /api/system — the read-only diagnostics snapshot for a headless phone. */
export interface SystemInfo {
  appVersion: string
  deviceModel: string
  deviceManufacturer: string
  androidVersion: string
  sdkInt: number
  /** Ms since the OS booted (elapsedRealtime). */
  osUptimeMs: number
  /** Ms since this process started; 0 when the platform cannot tell. */
  processUptimeMs: number
  battery: {
    level: number
    isCharging: boolean
    /** Tenths of a degree Celsius; absent when the platform reports none. */
    temperatureTenthsC?: number | null
    voltageMillivolts?: number | null
    health?: string | null
    /** Charge counter in mAh (BatteryManager CHARGE_COUNTER); absent when the device does not report it. */
    batteryChargeCounterMah?: number
    /** Instant current in µA (CURRENT_NOW), signed as the platform reports it; absent when unsupported. */
    batteryCurrentMicroAmps?: number
    /** Charge-cycle count (API 34+); absent below Android 14 or when the hardware does not track it. */
    batteryCycleCount?: number
  }
  storage: {
    usedBytes: number
    quotaBytes: number
    freeBytes: number
    totalBytes: number
  }
}

/** One per-UTC-day total in the detection stats series. */
export interface DailyCount {
  day: string
  count: number
}

/** One zone/label count row in the detection stats. */
export interface LabeledCount {
  label: string
  count: number
}

/** GET /api/detection/stats — aggregate counts over the persisted event log. */
export interface DetectionStats {
  last24h: Record<string, number>
  last7d: Record<string, number>
  allTime: Record<string, number>
  perDay: DailyCount[]
  totalEvents: number
  topZones: LabeledCount[]
  topLabels: LabeledCount[]
}

/** The recording-session trigger wire names on GET /api/recordings/sessions. */
export type RecordingTrigger = 'manual' | 'motion' | 'sound' | 'continuous' | 'scheduled' | 'interval'

/** One NVR recording session overlapping the requested day; `endMs` may sit past midnight for cross-day sessions. */
export interface RecordingSession {
  id: string
  startMs: number
  endMs: number
  trigger: RecordingTrigger
  /** MediaStore id of the finalized clip; null while (or if) none is linked. */
  mediaId: string | null
}

/** GET /api/recordings/sessions?day=YYYY-MM-DD — the day's recording sessions. */
export interface RecordingSessionsResponse {
  sessions: RecordingSession[]
}

export interface AuditEntry {
  timestampMs: number
  /** `"POST /api/settings"`, `"login.failed"`, … */
  action: string
  detail: string
  outcome: 'ok' | 'error'
}

/** The role a web session was minted with; viewer is the read-only pair. */
export type SessionRole = 'admin' | 'viewer'

/**
 * GET /api/auth/config. The `username` field is the admin username and is
 * only populated for admin sessions — viewer sessions get the redacted view
 * (`username` omitted, their own `viewerUsername` visible).
 */
export interface AuthConfig {
  enabled: boolean
  username?: string
  password?: string
  viewerEnabled?: boolean
  viewerUsername?: string | null
  viewerPassword?: string
  viewerConfigured: boolean
}

export interface AuditLogResponse {
  entries: AuditEntry[]
  total: number
}

/** The versioned envelope GET /api/settings/export downloads; import takes it back. */
export interface SettingsExport {
  schemaVersion: number
  exportedAtMs: number
  app: string
  settings: AllSettings | null
}

export const OVERLAY_POSITION_LABELS: Record<OverlayPosition, string> = {
  TOP_LEFT: 'Top Left',
  TOP_RIGHT: 'Top Right',
  BOTTOM_LEFT: 'Bottom Left',
  BOTTOM_RIGHT: 'Bottom Right',
}

export const MASKING_TYPE_LABELS: Record<MaskingType, string> = {
  BLACKOUT: 'Blackout',
  PIXELATE: 'Pixelate',
  BLUR: 'Blur',
}

export const DEFAULT_OVERLAY_SETTINGS: Partial<StreamingSettings> = {
  overlayEnabled: false,
  showTimestamp: true,
  timestampFormat: 'yyyy-MM-dd HH:mm:ss',
  showBranding: false,
  brandingText: 'LensCast',
  showStatus: false,
  showCustomText: false,
  customText: '',
  overlayPosition: 'TOP_LEFT',
  overlayFontSize: 28,
  overlayTextColor: '#FFFFFF',
  overlayBackgroundColor: '#80000000',
  overlayPadding: 8,
  overlayLineHeight: 4,
}

// ── Automation (time-of-day rules; GET/PUT /api/automation) ──
// Mirrors the app's AutomationWebHandler DTOs (WebApiDtos.kt): the whole
// automation surface in one document — master toggle, configured rules, and
// the read-only action catalog the rule editor renders from.

/** How a catalog param's string value is interpreted (AutomationParamSpec.kind). */
export type AutomationParamKind = 'BOOL' | 'INT' | 'FLOAT'

/** What happens when a rule fires late (AutomationActionType catch-up policy). */
export type AutomationCatchUp = 'STATEFUL' | 'ONESHOT'

/**
 * One step inside an AutomationRuleDto: a catalog action id plus its raw
 * parameters. Everything is a string on the wire (the same convention as the
 * persisted AutomationActionSpec) so the shape never needs versioning when an
 * action gains a parameter — validity and typing are the catalog's job at
 * sanitize time.
 */
export interface AutomationActionDto {
  /** Catalog id, e.g. `"torch"`; an id outside the catalog is dropped on save. */
  type: string
  /** Raw parameter values, all serialized as strings; clamped to the catalog bounds on save. */
  params: Record<string, string>
}

/**
 * One precondition inside an AutomationRuleDto (the rule's "ONLY WHEN"
 * clause): a catalog condition id plus its raw parameters — the same string
 * convention as AutomationActionDto; an unknown condition is dropped on save.
 */
export interface AutomationConditionDto {
  /** Condition catalog id, e.g. `"time_window"`; unknown ids are dropped on save. */
  type: string
  /** Raw parameter values, all serialized as strings; clamped to the catalog bounds on save. */
  params: Record<string, string>
}

/**
 * One automation rule as the dashboard configured it: WHEN triggerType
 * (+ ONLY WHEN conditions) THEN actions. Every field is sanitized through
 * the schedule policy on save: an unknown trigger folds to time_of_day,
 * unknown conditions/actions drop, and every bound clamps. A blank `id` on
 * write lets the device mint the fire-journal UUID.
 */
export interface AutomationRuleDto {
  /** Stable identity (the fire-journal key); blank on a new rule. */
  id: string
  /** Free-form display label; trimmed on save, never interpreted. */
  name: string
  /** Per-rule kill switch, independent of the master automation toggle. */
  enabled: boolean
  /** Which cue fires the rule — a trigger catalog id; unknown folds to time_of_day on save. */
  triggerType: string
  /**
   * The time_of_day fire minute, local wall-clock 0..1439; clamped into that
   * span on save. Meaningless for event triggers (the editor hides it) but
   * kept top-level so the original time-only wire shape never migrated.
   */
  minuteOfDay: number
  /**
   * The time_of_day day mask: one bit per ISO day index (bit 0 = Monday …
   * bit 6 = Sunday, 127 = every day); clamped to 1..127 on save.
   */
  daysMask: number
  /** Parameters for non-time triggers (e.g. battery_level's level); clamped on save. */
  triggerParams: Record<string, string>
  /** Preconditions that must hold when the trigger fires; unknown conditions drop on save. */
  conditions: AutomationConditionDto[]
  /** How multiple conditions combine: `"all"` | `"any"`; anything else folds to `all`. */
  conditionMode: string
  /** Minimum seconds between two firings (event rules); clamped to 0..86400 on save. */
  cooldownSeconds: number
  /** The ordered action list; each entry is validated against the catalog on save. */
  actions: AutomationActionDto[]
  /**
   * Epoch milliseconds (UTC) of the rule's last actual firing, decoded from
   * the device's fire journal; null/absent = never fired. Read-only — PUT
   * bodies ignore it (the response is a fresh GET-shaped read).
   */
  lastFiredAt?: number | null
}

/**
 * One parameter of a catalog action, as GET /api/automation publishes it: the
 * schema a rule editor needs to render and validate inputs. Read-only — the
 * catalog is device truth and never accepted back on the wire.
 */
export interface AutomationCatalogParamDto {
  /** The exact wire key inside AutomationActionDto.params. */
  name: string
  /** How the string value is interpreted. */
  kind: AutomationParamKind
  /** Lower bound, or null for unbounded; omitted on the wire when null. */
  min?: number | null
  /** Upper bound, or null for unbounded; omitted on the wire when null. */
  max?: number | null
  /** When true, an absent parameter means "leave the current setting unchanged". */
  optional: boolean
  /** The value an absent required or invalid value folds to; empty for optional params with no persisted default. */
  defaultValue: string
  /**
   * '0' is the auto/unlimited sentinel — clients may render an Auto toggle;
   * false = ordinary value. Always serialized (a non-nullable Kotlin boolean,
   * unlike the nullable min/max which are omitted on the wire when null).
   */
  autoAtZero: boolean
}

/** One action the automation engine can execute, as GET /api/automation publishes it. */
export interface AutomationCatalogActionDto {
  /** The action id an AutomationActionDto.type must name. */
  id: string
  /** The parameter schema, in display/contract order. */
  params: AutomationCatalogParamDto[]
  /** What happens when the rule fires late. */
  catchUp: AutomationCatchUp
  /**
   * UI warning flag; the executor does not force a teardown — the lens switch
   * rebinds when the camera is free (brief interruption) and defers to the
   * next natural rebind while a recording is active.
   */
  interruptsStream: boolean
}

/**
 * One trigger a rule may fire on, as GET /api/automation publishes it —
 * read-only device truth. time_of_day's params list is empty: its parameters
 * are the rule's own minuteOfDay/daysMask fields.
 */
export interface AutomationCatalogTriggerDto {
  /** The trigger id an AutomationRuleDto.triggerType must name. */
  id: string
  /** The parameter schema, in display/contract order. */
  params: AutomationCatalogParamDto[]
}

/** One precondition a rule may require, as GET /api/automation publishes it — read-only device truth. */
export interface AutomationCatalogConditionDto {
  /** The condition id an AutomationConditionDto.type must name. */
  id: string
  /** The parameter schema, in display/contract order. */
  params: AutomationCatalogParamDto[]
}

/** GET /api/automation — the master toggle, the rules, and the three read-only catalogs. */
export interface AutomationStateDto {
  enabled: boolean
  rules: AutomationRuleDto[]
  catalog: AutomationCatalogActionDto[]
  triggers: AutomationCatalogTriggerDto[]
  conditions: AutomationCatalogConditionDto[]
}

/**
 * PUT /api/automation body: the master toggle plus the full rule list (the
 * document persists wholesale, like the settings sections). The catalog is
 * read-only on the wire and never accepted here; the response is a fresh
 * GET-shaped read, so the client sees the sanitized truth rather than what
 * it sent.
 */
export interface AutomationUpdateRequestDto {
  enabled: boolean
  rules: AutomationRuleDto[]
}


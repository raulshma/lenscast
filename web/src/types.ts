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

// Stream auth contract: the web client sends the password as PLAINTEXT over
// the wire (e.g. POST /api/auth/login {username, password}); the server never
// stores or returns it — at rest it keeps only passwordHash and an RTSP
// digest HA1, and verifies logins against the hash.
//
// API token contract (same write-only pattern): the dashboard generates a
// random token client-side and sends it ONCE as the write-only `apiToken`
// field of StreamingSettings; the server stores only its SHA-256 hex hash and
// never returns the token or the hash — responses carry `apiTokenEnabled` and
// `apiTokenConfigured` instead. The token authorizes read-only GET/HEAD
// requests (never /api/auth/*) via `Authorization: Bearer` or `X-Api-Token`.

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
}

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
  adaptiveBitrateEnabled: boolean
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
  soundDetectionEnabled: boolean
  soundThresholdPercent: number
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
}

export interface AllSettings {
  camera: CameraSettings
  streaming: StreamingSettings
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
  }
  thermal: ThermalState
  battery: {
    level: number
    isCharging: boolean
    isPowerSaveMode: boolean
  }
  camera: string
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

export interface LensInfo {
  index: number
  id: string
  label: string
  focalLength: number
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
  thumbnailUrl: string
  downloadUrl: string
}

export interface GalleryResponse {
  items: GalleryItem[]
  total: number
  page: number
  pageSize: number
  hasMore: boolean
}

export type DetectionEventType = 'motion' | 'sound'

export interface DetectionEvent {
  id: string
  type: DetectionEventType
  source: string
  timestampMs: number
  snapshotJpegBase64?: string | null
  dispatchedActions: string[]
}

export interface DetectionEventsResponse {
  events: DetectionEvent[]
  total: number
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


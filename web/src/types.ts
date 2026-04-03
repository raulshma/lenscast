export type FocusMode = 'AUTO' | 'MANUAL' | 'MACRO' | 'CONTINUOUS_PICTURE' | 'CONTINUOUS_VIDEO'
export type WhiteBalance = 'AUTO' | 'DAYLIGHT' | 'CLOUDY' | 'INDOOR' | 'FLUORESCENT' | 'MANUAL'
export type Resolution = 'SD_480P' | 'HD_720P' | 'FHD_1080P' | 'QHD_1440P' | 'UHD_4K'
export type HdrMode = 'OFF' | 'ON' | 'AUTO'
export type ThermalState = 'NORMAL' | 'LIGHT' | 'MODERATE' | 'SEVERE' | 'CRITICAL'
export type CaptureMode = 'MINIMIZE_LATENCY' | 'MAXIMIZE_QUALITY'
export type FlashMode = 'ON' | 'OFF' | 'AUTO'
export type RecordingQuality = 'HIGH' | 'MEDIUM' | 'LOW'

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
}

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
  adaptiveBitrateEnabled: boolean
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
    clientCount: number
    audioEnabled: boolean
    audioUrl: string
    rtspEnabled: boolean
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

export const CAPTURE_MODE_LABELS: Record<CaptureMode, string> = {
  MINIMIZE_LATENCY: 'Minimize Latency',
  MAXIMIZE_QUALITY: 'Maximize Quality',
}

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
  imageQuality: number
  captureMode: CaptureMode
  flashMode: FlashMode
}

export interface RecordingConfig {
  durationSeconds: number
  repeatIntervalSeconds: number
  quality: RecordingQuality
  maxFileSizeBytes: number
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
}


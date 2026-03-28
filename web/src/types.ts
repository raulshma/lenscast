export type FocusMode = 'AUTO' | 'MANUAL' | 'MACRO' | 'CONTINUOUS_PICTURE' | 'CONTINUOUS_VIDEO'
export type WhiteBalance = 'AUTO' | 'DAYLIGHT' | 'CLOUDY' | 'INDOOR' | 'FLUORESCENT' | 'MANUAL'
export type Resolution = 'SD_480P' | 'HD_720P' | 'FHD_1080P' | 'QHD_1440P' | 'UHD_4K'
export type HdrMode = 'OFF' | 'ON' | 'AUTO'
export type ThermalState = 'NORMAL' | 'LIGHT' | 'MODERATE' | 'SEVERE' | 'CRITICAL'

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
  jpegQuality: number
  showPreview: boolean
}

export interface AuthSettings {
  enabled: boolean
  username: string
  password?: string
}

export interface AllSettings {
  camera: CameraSettings
  streaming: StreamingSettings
  auth: AuthSettings
}

export interface DeviceStatus {
  streaming: {
    isActive: boolean
    url: string
    clientCount: number
  }
  thermal: ThermalState
  battery: {
    level: number
    isCharging: boolean
    isPowerSaveMode: boolean
  }
  camera: string
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

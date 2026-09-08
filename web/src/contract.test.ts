import { describe, expect, it } from 'vitest'
import {
  type AllSettings,
  type DeviceStatus,
  type GalleryResponse,
  type IntervalCaptureStatus,
  type LensesResponse,
  type RecordingStatus,
  FRAME_RATE_OPTIONS,
} from './types'
import { API_DEFAULTS } from './api/defaults'
import settingsFixture from '../contract/settings.json'
import statusFixture from '../contract/status.json'
import galleryFixture from '../contract/gallery.json'
import recordingStatusFixture from '../contract/recording-status.json'
import lensesFixture from '../contract/lenses.json'
import intervalCaptureStatusFixture from '../contract/interval-capture-status.json'

// The checked-in JSON files in web/contract/ are the shared DTO contract:
// app's DtoContractFixtureTest serializes the Kotlin DTOs (through the
// production AppJson adapters) and asserts they match these exact files, so
// any Kotlin-side shape change lands here and turns this suite red. See
// CONTEXT.md "Web API Handlers".

function expectKeys(obj: Record<string, unknown>, keys: string[]): void {
  expect(Object.keys(obj)).toEqual(expect.arrayContaining(keys))
}

const CAMERA_KEYS = [
  'exposureCompensation',
  'iso',
  'exposureTime',
  'focusMode',
  'focusDistance',
  'whiteBalance',
  'colorTemperature',
  'zoomRatio',
  'frameRate',
  'resolution',
  'stabilization',
  'hdrMode',
  'sceneMode',
  'nightVisionMode',
]

const STREAMING_KEYS = [
  'port',
  'webStreamingEnabled',
  'jpegQuality',
  'showPreview',
  'streamAudioEnabled',
  'streamAudioBitrateKbps',
  'streamAudioChannels',
  'streamAudioEchoCancellation',
  'recordingAudioEnabled',
  'rtspEnabled',
  'rtspPort',
  'rtspInputFormat',
  'adaptiveBitrateEnabled',
  'overlayEnabled',
  'showTimestamp',
  'timestampFormat',
  'showBranding',
  'brandingText',
  'showStatus',
  'showCustomText',
  'customText',
  'overlayPosition',
  'overlayFontSize',
  'overlayTextColor',
  'overlayBackgroundColor',
  'overlayPadding',
  'overlayLineHeight',
  'maskingEnabled',
  'maskingZones',
  'watchdogEnabled',
  'watchdogMaxRetries',
  'watchdogCheckIntervalSeconds',
]

describe('DTO contract fixtures', () => {
  it('settings fixture assigns to AllSettings', () => {
    const settings: AllSettings = settingsFixture as AllSettings
    expectKeys(settings as unknown as Record<string, unknown>, ['camera', 'streaming'])
    expectKeys(settings.camera as unknown as Record<string, unknown>, CAMERA_KEYS)
    expectKeys(settings.streaming as unknown as Record<string, unknown>, STREAMING_KEYS)
    expect(Array.isArray(settings.streaming.maskingZones)).toBe(true)
  })

  it('status fixture assigns to DeviceStatus', () => {
    const status: DeviceStatus = statusFixture as DeviceStatus
    expectKeys(status as unknown as Record<string, unknown>, ['streaming', 'thermal', 'camera', 'battery'])
    expectKeys(status.streaming as unknown as Record<string, unknown>, [
      'isActive',
      'url',
      'webStreamingEnabled',
      'webStreamingActive',
      'clientCount',
      'audioEnabled',
      'audioUrl',
      'rtspEnabled',
      'rtspStreamingActive',
      'rtspUrl',
    ])
    expectKeys(status.battery as unknown as Record<string, unknown>, ['level', 'isCharging', 'isPowerSaveMode'])
    expectKeys(status.adaptiveBitrate as unknown as Record<string, unknown>, [
      'enabled',
      'qualityLevel',
      'currentQuality',
      'targetQuality',
      'currentFps',
      'targetFps',
      'estimatedBandwidthKbps',
      'minClientThroughputKbps',
      'activeClients',
      'adjustmentCount',
    ])
    expectKeys(status.connectionQuality as unknown as Record<string, unknown>, [
      'qualityLevel',
      'estimatedBandwidthKbps',
      'avgThroughputKbps',
      'minThroughputKbps',
      'worstLatencyMs',
      'avgFrameSizeBytes',
      'totalBytesSent',
      'activeClients',
      'framesPerSecond',
      'clientDetails',
    ])
    const [clientDetail] = Object.values(status.connectionQuality!.clientDetails)
    expectKeys(clientDetail as unknown as Record<string, unknown>, [
      'framesSent',
      'bytesSent',
      'avgThroughputKbps',
      'lastFrameSizeBytes',
      'lastSendDurationMs',
    ])
    expectKeys(status.watchdog as unknown as Record<string, unknown>, [
      'enabled',
      'status',
      'consecutiveFailures',
      'totalRecoveries',
      'lastRecoveryTimestamp',
      'lastFailureReason',
    ])
  })

  it('gallery fixture assigns to GalleryResponse', () => {
    const gallery: GalleryResponse = galleryFixture as GalleryResponse
    expectKeys(gallery as unknown as Record<string, unknown>, ['items', 'total', 'page', 'pageSize', 'hasMore'])
    expect(gallery.items.length).toBeGreaterThan(0)
    for (const item of gallery.items) {
      expectKeys(item as unknown as Record<string, unknown>, [
        'id',
        'type',
        'fileName',
        'timestamp',
        'fileSizeBytes',
        'durationMs',
        'thumbnailUrl',
        'downloadUrl',
      ])
    }
  })

  it('recording status fixture assigns to RecordingStatus', () => {
    const recording: RecordingStatus = recordingStatusFixture as RecordingStatus
    expectKeys(recording as unknown as Record<string, unknown>, [
      'isRecording',
      'elapsedSeconds',
      'isScheduled',
      'scheduledStartTimeMs',
    ])
  })

  it('lenses fixture assigns to LensesResponse', () => {
    const lenses: LensesResponse = lensesFixture as LensesResponse
    expectKeys(lenses as unknown as Record<string, unknown>, ['lenses', 'selectedIndex'])
    expect(lenses.lenses.length).toBeGreaterThan(0)
    for (const lens of lenses.lenses) {
      expectKeys(lens as unknown as Record<string, unknown>, [
        'index',
        'id',
        'label',
        'focalLength',
        'isFront',
        'selected',
      ])
    }
  })

  it('interval capture status fixture assigns to IntervalCaptureStatus', () => {
    const interval: IntervalCaptureStatus = intervalCaptureStatusFixture as IntervalCaptureStatus
    expectKeys(interval as unknown as Record<string, unknown>, ['isRunning', 'completedCaptures'])
  })
})

describe('API_DEFAULTS lockstep with the fixtures', () => {
  const settings: AllSettings = settingsFixture as AllSettings
  const { camera, streaming } = settings

  it('streaming fallbacks match the settings fixture defaults', () => {
    expect(streaming.port).toBe(API_DEFAULTS.webPort)
    expect(streaming.webStreamingEnabled).toBe(API_DEFAULTS.webStreamingEnabled)
    expect(streaming.jpegQuality).toBe(API_DEFAULTS.jpegQuality)
    expect(streaming.adaptiveBitrateEnabled).toBe(API_DEFAULTS.adaptiveBitrateEnabled)
    expect(streaming.showPreview).toBe(API_DEFAULTS.showPreview)
    expect(streaming.streamAudioEnabled).toBe(API_DEFAULTS.streamAudioEnabled)
    expect(streaming.streamAudioBitrateKbps).toBe(API_DEFAULTS.streamAudioBitrateKbps)
    expect(streaming.streamAudioChannels).toBe(API_DEFAULTS.streamAudioChannels)
    expect(streaming.streamAudioEchoCancellation).toBe(API_DEFAULTS.streamAudioEchoCancellation)
    expect(streaming.recordingAudioEnabled).toBe(API_DEFAULTS.recordingAudioEnabled)
    expect(streaming.rtspEnabled).toBe(API_DEFAULTS.rtspEnabled)
    expect(streaming.rtspPort).toBe(API_DEFAULTS.rtspPort)
    expect(streaming.rtspInputFormat).toBe(API_DEFAULTS.rtspInputFormat)
  })

  it('overlay fallbacks match the settings fixture defaults', () => {
    expect(streaming.overlayEnabled).toBe(API_DEFAULTS.overlayEnabled)
    expect(streaming.overlayPosition).toBe(API_DEFAULTS.overlayPosition)
    expect(streaming.overlayTextColor).toBe(API_DEFAULTS.overlayTextColor)
    expect(streaming.overlayBackgroundColor).toBe(API_DEFAULTS.overlayBackgroundColor)
    expect(streaming.overlayFontSize).toBe(API_DEFAULTS.overlayFontSize)
    expect(streaming.overlayPadding).toBe(API_DEFAULTS.overlayPadding)
    expect(streaming.overlayLineHeight).toBe(API_DEFAULTS.overlayLineHeight)
  })

  it('watchdog fallbacks match the settings fixture defaults', () => {
    expect(streaming.watchdogEnabled).toBe(API_DEFAULTS.watchdogEnabled)
    expect(streaming.watchdogMaxRetries).toBe(API_DEFAULTS.watchdogMaxRetries)
    expect(streaming.watchdogCheckIntervalSeconds).toBe(API_DEFAULTS.watchdogCheckIntervalSeconds)
  })

  it('camera fallbacks match the settings fixture defaults', () => {
    expect(camera.exposureCompensation).toBe(API_DEFAULTS.cameraExposureCompensation)
    expect(camera.iso).toBe(API_DEFAULTS.cameraIso)
    expect(camera.exposureTime).toBe(API_DEFAULTS.cameraExposureTime)
    expect(camera.focusMode).toBe(API_DEFAULTS.cameraFocusMode)
    expect(camera.focusDistance).toBe(API_DEFAULTS.cameraFocusDistance)
    expect(camera.whiteBalance).toBe(API_DEFAULTS.cameraWhiteBalance)
    expect(camera.colorTemperature).toBe(API_DEFAULTS.cameraColorTemperature)
    expect(camera.zoomRatio).toBe(API_DEFAULTS.cameraZoomRatio)
    expect(camera.resolution).toBe(API_DEFAULTS.cameraResolution)
    expect(camera.stabilization).toBe(API_DEFAULTS.cameraStabilization)
    expect(camera.hdrMode).toBe(API_DEFAULTS.cameraHdrMode)
    expect(camera.sceneMode).toBe(API_DEFAULTS.cameraSceneMode)
    expect(camera.nightVisionMode).toBe(API_DEFAULTS.cameraNightVisionMode)
    // Known divergence, kept intentionally: the Kotlin DTO default follows
    // StreamDefaults.STREAM_FPS (24) while the web select falls back to 30.
    // Locked loosely instead of by equality — the fixture fps must stay a
    // value the web frame-rate select can actually display.
    expect(FRAME_RATE_OPTIONS).toContain(camera.frameRate)
  })

  it('slider bounds match the Kotlin StreamDefaults bounds and contain their defaults', () => {
    expect(API_DEFAULTS.jpegQualityMin).toBe(10)
    expect(API_DEFAULTS.jpegQualityMax).toBe(100)
    expect(streaming.jpegQuality).toBeGreaterThanOrEqual(API_DEFAULTS.jpegQualityMin)
    expect(streaming.jpegQuality).toBeLessThanOrEqual(API_DEFAULTS.jpegQualityMax)

    expect(API_DEFAULTS.watchdogMaxRetriesMin).toBe(1)
    expect(API_DEFAULTS.watchdogMaxRetriesMax).toBe(20)
    expect(streaming.watchdogMaxRetries).toBeGreaterThanOrEqual(API_DEFAULTS.watchdogMaxRetriesMin)
    expect(streaming.watchdogMaxRetries).toBeLessThanOrEqual(API_DEFAULTS.watchdogMaxRetriesMax)

    expect(API_DEFAULTS.watchdogCheckIntervalMinSeconds).toBe(3)
    expect(API_DEFAULTS.watchdogCheckIntervalMaxSeconds).toBe(30)
    expect(streaming.watchdogCheckIntervalSeconds).toBeGreaterThanOrEqual(API_DEFAULTS.watchdogCheckIntervalMinSeconds)
    expect(streaming.watchdogCheckIntervalSeconds).toBeLessThanOrEqual(API_DEFAULTS.watchdogCheckIntervalMaxSeconds)

    expect(API_DEFAULTS.audioBitrateMinKbps).toBe(32)
    expect(API_DEFAULTS.audioBitrateMaxKbps).toBe(320)
    expect(streaming.streamAudioBitrateKbps).toBeGreaterThanOrEqual(API_DEFAULTS.audioBitrateMinKbps)
    expect(streaming.streamAudioBitrateKbps).toBeLessThanOrEqual(API_DEFAULTS.audioBitrateMaxKbps)

    expect(API_DEFAULTS.rtspPortMin).toBe(1024)
    expect(API_DEFAULTS.rtspPortMax).toBe(65535)
    expect(streaming.rtspPort).toBeGreaterThanOrEqual(API_DEFAULTS.rtspPortMin)
    expect(streaming.rtspPort).toBeLessThanOrEqual(API_DEFAULTS.rtspPortMax)
  })
})

import { describe, expect, it } from 'vitest'
import {
  type AllSettings,
  type AuditLogResponse,
  type DetectionStats,
  type DetectionTestResponse,
  type DeviceStatus,
  type DetectionEventsResponse,
  type GalleryResponse,
  type IntervalCaptureStatus,
  type LensesResponse,
  type RecordingStatus,
  type SettingsExport,
  type SystemInfo,
  FRAME_RATE_OPTIONS,
} from './types'
import { API_DEFAULTS } from './api/defaults'
import settingsFixture from '../contract/settings.json'
import settingsExportFixture from '../contract/settings-export.json'
import statusFixture from '../contract/status.json'
import galleryFixture from '../contract/gallery.json'
import recordingStatusFixture from '../contract/recording-status.json'
import lensesFixture from '../contract/lenses.json'
import intervalCaptureStatusFixture from '../contract/interval-capture-status.json'
import detectionEventsFixture from '../contract/detection-events.json'
import detectionTestFixture from '../contract/detection-test.json'
import auditLogFixture from '../contract/audit-log.json'
import systemFixture from '../contract/system.json'
import detectionStatsFixture from '../contract/detection-stats.json'

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
  'rtspVideoCodec',
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
  'mdnsEnabled',
  'motionDetectionEnabled',
  'motionSensitivityPercent',
  'motionZones',
  'motionRecordingEnabled',
  'motionPostRollSeconds',
  'motionArmScheduleEnabled',
  'motionArmStartMinute',
  'motionArmEndMinute',
  'motionArmDaysMask',
  'soundDetectionEnabled',
  'soundThresholdPercent',
  'soundAdaptiveNoiseFloor',
  'soundRecordingEnabled',
  'motionCooldownSeconds',
  'soundCooldownSeconds',
  'webhookEnabled',
  'webhookUrl',
  'webhookHeaders',
  'autoSiren',
  'autoTorch',
  'sirenDurationSeconds',
  'autoDeterrenceCooldownSeconds',
  'backupEnabled',
  'backupWifiOnly',
  'backupTarget',
  'backupWebdavUrl',
  'backupWebdavUsername',
  'backupWebdavPassword',
  'telegramChatId',
  'telegramBotToken',
  'apiTokenEnabled',
  'apiTokenConfigured',
  'apiToken',
  'httpsEnabled',
  'audioDeviceId',
  'detectionNotificationsEnabled',
  'alertQuietHoursEnabled',
  'alertQuietHoursStartMinute',
  'alertQuietHoursEndMinute',
  'tamperDetectionEnabled',
  'mqttEnabled',
  'mqttBrokerHost',
  'mqttBrokerPort',
  'mqttUsername',
  'mqttPassword',
  'mqttTls',
  'mqttDiscoveryPrefix',
  'mlModelState',
  'mlModelProgress',
  'mlModelError',
  'mlIncludePerson',
  'mlIncludePets',
  'mlIncludeVehicles',
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
    expectKeys(status as unknown as Record<string, unknown>, [
      'streaming',
      'thermal',
      'camera',
      'battery',
      'torchOn',
      'zoomRatio',
      'lensId',
      'lensLabel',
      'zoomRange',
      'exposureCompensationRange',
      'isoRange',
    ])
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

  it('detection events fixture assigns to DetectionEventsResponse', () => {
    const events: DetectionEventsResponse = detectionEventsFixture as DetectionEventsResponse
    expectKeys(events as unknown as Record<string, unknown>, ['events', 'total'])
    expect(events.events.length).toBeGreaterThan(0)
    for (const event of events.events) {
      expectKeys(event as unknown as Record<string, unknown>, [
        'id',
        'type',
        'source',
        'timestampMs',
        'snapshotJpegBase64',
        'dispatchedActions',
        'zones',
      ])
      expect(Array.isArray(event.dispatchedActions)).toBe(true)
      expect(Array.isArray(event.zones)).toBe(true)
    }
  })

  it('settings export fixture assigns to SettingsExport', () => {
    const exportFile: SettingsExport = settingsExportFixture as SettingsExport
    expectKeys(exportFile as unknown as Record<string, unknown>, [
      'schemaVersion',
      'exportedAtMs',
      'app',
      'settings',
    ])
    expect(exportFile.schemaVersion).toBe(1)
    expect(exportFile.app).toBe('lenscast')
    // The envelope's settings document is the same AllSettings shape the
    // settings fixture pins.
    const settings: AllSettings = exportFile.settings as AllSettings
    expectKeys(settings as unknown as Record<string, unknown>, ['camera', 'streaming'])
  })

  it('detection test fixture assigns to DetectionTestResponse', () => {
    const test: DetectionTestResponse = detectionTestFixture as DetectionTestResponse
    expectKeys(test as unknown as Record<string, unknown>, ['success', 'dispatchedActions'])
    expect(Array.isArray(test.dispatchedActions)).toBe(true)
  })

  it('audit log fixture assigns to AuditLogResponse', () => {
    const audit: AuditLogResponse = auditLogFixture as AuditLogResponse
    expectKeys(audit as unknown as Record<string, unknown>, ['entries', 'total'])
    expect(audit.entries.length).toBeGreaterThan(0)
    for (const entry of audit.entries) {
      expectKeys(entry as unknown as Record<string, unknown>, [
        'timestampMs',
        'action',
        'detail',
        'outcome',
      ])
    }
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

  it('detection, webhook and backup fallbacks match the settings fixture defaults', () => {
    expect(streaming.mdnsEnabled).toBe(API_DEFAULTS.mdnsEnabled)
    expect(streaming.motionDetectionEnabled).toBe(API_DEFAULTS.motionDetectionEnabled)
    expect(streaming.motionSensitivityPercent).toBe(API_DEFAULTS.motionSensitivityPercent)
    expect(Array.isArray(streaming.motionZones)).toBe(true)
    expect(streaming.motionRecordingEnabled).toBe(API_DEFAULTS.motionRecordingEnabled)
    expect(streaming.motionPostRollSeconds).toBe(API_DEFAULTS.motionPostRollSeconds)
    expect(streaming.motionArmScheduleEnabled).toBe(API_DEFAULTS.motionArmScheduleEnabled)
    expect(streaming.motionArmStartMinute).toBe(API_DEFAULTS.motionArmStartMinute)
    expect(streaming.motionArmEndMinute).toBe(API_DEFAULTS.motionArmEndMinute)
    expect(streaming.motionArmDaysMask).toBe(API_DEFAULTS.motionArmDaysMask)
    expect(streaming.soundDetectionEnabled).toBe(API_DEFAULTS.soundDetectionEnabled)
    expect(streaming.soundThresholdPercent).toBe(API_DEFAULTS.soundThresholdPercent)
    expect(streaming.soundAdaptiveNoiseFloor).toBe(API_DEFAULTS.soundAdaptiveNoiseFloor)
    expect(streaming.soundRecordingEnabled).toBe(API_DEFAULTS.soundRecordingEnabled)
    expect(streaming.motionCooldownSeconds).toBe(API_DEFAULTS.motionCooldownSeconds)
    expect(streaming.soundCooldownSeconds).toBe(API_DEFAULTS.soundCooldownSeconds)
    expect(streaming.webhookEnabled).toBe(API_DEFAULTS.webhookEnabled)
    expect(streaming.webhookUrl).toBe(API_DEFAULTS.webhookUrl)
    expect(streaming.webhookHeaders).toBe(API_DEFAULTS.webhookHeaders)
    expect(streaming.autoSiren).toBe(API_DEFAULTS.autoSiren)
    expect(streaming.autoTorch).toBe(API_DEFAULTS.autoTorch)
    expect(streaming.sirenDurationSeconds).toBe(API_DEFAULTS.sirenDurationSeconds)
    expect(streaming.autoDeterrenceCooldownSeconds).toBe(API_DEFAULTS.autoDeterrenceCooldownSeconds)
    expect(streaming.backupEnabled).toBe(API_DEFAULTS.backupEnabled)
    expect(streaming.backupWifiOnly).toBe(API_DEFAULTS.backupWifiOnly)
    expect(streaming.backupTarget).toBe(API_DEFAULTS.backupTarget)
    expect(streaming.backupWebdavUrl).toBe(API_DEFAULTS.backupWebdavUrl)
    expect(streaming.backupWebdavUsername).toBe(API_DEFAULTS.backupWebdavUsername)
    expect(streaming.backupWebdavPassword).toBe(API_DEFAULTS.backupWebdavPassword)
    expect(streaming.telegramChatId).toBe(API_DEFAULTS.telegramChatId)
    expect(streaming.telegramBotToken).toBe(API_DEFAULTS.telegramBotToken)
    expect(streaming.apiTokenEnabled).toBe(API_DEFAULTS.apiTokenEnabled)
    expect(streaming.apiTokenConfigured).toBe(API_DEFAULTS.apiTokenConfigured)
    expect(streaming.apiToken).toBe(API_DEFAULTS.apiToken)
    expect(streaming.httpsEnabled).toBe(API_DEFAULTS.httpsEnabled)
    expect(streaming.audioDeviceId).toBe(API_DEFAULTS.audioDeviceId)
    expect(streaming.detectionNotificationsEnabled).toBe(API_DEFAULTS.detectionNotificationsEnabled)
    expect(streaming.alertQuietHoursEnabled).toBe(API_DEFAULTS.alertQuietHoursEnabled)
    expect(streaming.alertQuietHoursStartMinute).toBe(API_DEFAULTS.alertQuietHoursStartMinute)
    expect(streaming.alertQuietHoursEndMinute).toBe(API_DEFAULTS.alertQuietHoursEndMinute)
    expect(streaming.tamperDetectionEnabled).toBe(API_DEFAULTS.tamperDetectionEnabled)
    expect(streaming.mqttEnabled).toBe(API_DEFAULTS.mqttEnabled)
    expect(streaming.mqttBrokerHost).toBe(API_DEFAULTS.mqttBrokerHost)
    expect(streaming.mqttBrokerPort).toBe(API_DEFAULTS.mqttBrokerPort)
    expect(streaming.mqttUsername).toBe(API_DEFAULTS.mqttUsername)
    expect(streaming.mqttPassword).toBe(API_DEFAULTS.mqttPassword)
    expect(streaming.mqttTls).toBe(API_DEFAULTS.mqttTls)
    expect(streaming.mqttDiscoveryPrefix).toBe(API_DEFAULTS.mqttDiscoveryPrefix)
  })

  it('detection suite, retention and rtspResolution fallbacks match the settings fixture defaults', () => {
    expect(streaming.rtspResolution).toBe(API_DEFAULTS.rtspResolution)
    expect(streaming.rtspVideoCodec).toBe(API_DEFAULTS.rtspVideoCodec)
    expect(streaming.mlDetectionEnabled).toBe(API_DEFAULTS.mlDetectionEnabled)
    expect(streaming.mlMinScorePercent).toBe(API_DEFAULTS.mlMinScorePercent)
    expect(streaming.mlIncludePerson).toBe(API_DEFAULTS.mlIncludePerson)
    expect(streaming.mlIncludePets).toBe(API_DEFAULTS.mlIncludePets)
    expect(streaming.mlIncludeVehicles).toBe(API_DEFAULTS.mlIncludeVehicles)
    expect(streaming.mlModelState).toBe(API_DEFAULTS.mlModelState)
    expect(streaming.mlModelProgress).toBe(API_DEFAULTS.mlModelProgress)
    expect(streaming.mlModelError).toBe(API_DEFAULTS.mlModelError)
    expect(streaming.continuousRecording).toBe(API_DEFAULTS.continuousRecording)
    expect(streaming.continuousSegmentMinutes).toBe(API_DEFAULTS.continuousSegmentMinutes)
    expect(streaming.onvifEnabled).toBe(API_DEFAULTS.onvifEnabled)
    expect(streaming.captureRetentionDays).toBe(API_DEFAULTS.captureRetentionDays)
    expect(streaming.eventRetentionDays).toBe(API_DEFAULTS.eventRetentionDays)
    expect(streaming.storageQuotaMb).toBe(API_DEFAULTS.storageQuotaMb)
  })

  it('system fixture assigns to SystemInfo', () => {
    const system: SystemInfo = systemFixture as SystemInfo
    expectKeys(system as unknown as Record<string, unknown>, [
      'appVersion',
      'deviceModel',
      'deviceManufacturer',
      'androidVersion',
      'sdkInt',
      'osUptimeMs',
      'processUptimeMs',
      'battery',
      'storage',
    ])
    expectKeys(system.battery as unknown as Record<string, unknown>, [
      'level',
      'isCharging',
      'temperatureTenthsC',
      'voltageMillivolts',
      'health',
    ])
    expectKeys(system.storage as unknown as Record<string, unknown>, [
      'usedBytes',
      'quotaBytes',
      'freeBytes',
      'totalBytes',
    ])
  })

  it('detection stats fixture assigns to DetectionStats', () => {
    const stats: DetectionStats = detectionStatsFixture as DetectionStats
    expectKeys(stats as unknown as Record<string, unknown>, [
      'last24h',
      'last7d',
      'allTime',
      'perDay',
      'totalEvents',
      'topZones',
      'topLabels',
    ])
    expect(stats.perDay.length).toBeGreaterThan(0)
    for (const day of stats.perDay) {
      expectKeys(day as unknown as Record<string, unknown>, ['day', 'count'])
    }
  })

  it('retention inputs share the 0–365 bound and contain their defaults', () => {
    expect(API_DEFAULTS.retentionMinDays).toBe(0)
    expect(API_DEFAULTS.retentionMaxDays).toBe(365)
    expect(streaming.captureRetentionDays).toBeGreaterThanOrEqual(API_DEFAULTS.retentionMinDays)
    expect(streaming.captureRetentionDays).toBeLessThanOrEqual(API_DEFAULTS.retentionMaxDays)
    expect(streaming.eventRetentionDays).toBeGreaterThanOrEqual(API_DEFAULTS.retentionMinDays)
    expect(streaming.eventRetentionDays).toBeLessThanOrEqual(API_DEFAULTS.retentionMaxDays)
    expect(API_DEFAULTS.storageQuotaMinMb).toBe(100)
    expect(API_DEFAULTS.storageQuotaMaxMb).toBe(32768)
    expect(streaming.storageQuotaMb).toBeGreaterThanOrEqual(API_DEFAULTS.storageQuotaMinMb)
    expect(streaming.storageQuotaMb).toBeLessThanOrEqual(API_DEFAULTS.storageQuotaMaxMb)
    expect(API_DEFAULTS.mlMinScoreMinPercent).toBe(10)
    expect(API_DEFAULTS.mlMinScoreMaxPercent).toBe(95)
    expect(streaming.mlMinScorePercent).toBeGreaterThanOrEqual(API_DEFAULTS.mlMinScoreMinPercent)
    expect(streaming.mlMinScorePercent).toBeLessThanOrEqual(API_DEFAULTS.mlMinScoreMaxPercent)
    expect(API_DEFAULTS.continuousSegmentMinMinutes).toBe(5)
    expect(API_DEFAULTS.continuousSegmentMaxMinutes).toBe(60)
    expect(streaming.continuousSegmentMinutes).toBeGreaterThanOrEqual(API_DEFAULTS.continuousSegmentMinMinutes)
    expect(streaming.continuousSegmentMinutes).toBeLessThanOrEqual(API_DEFAULTS.continuousSegmentMaxMinutes)
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

    expect(API_DEFAULTS.sirenDurationMinSeconds).toBe(5)
    expect(API_DEFAULTS.sirenDurationMaxSeconds).toBe(60)
    expect(streaming.sirenDurationSeconds).toBeGreaterThanOrEqual(API_DEFAULTS.sirenDurationMinSeconds)
    expect(streaming.sirenDurationSeconds).toBeLessThanOrEqual(API_DEFAULTS.sirenDurationMaxSeconds)

    expect(API_DEFAULTS.deterrenceCooldownMinSeconds).toBe(30)
    expect(API_DEFAULTS.deterrenceCooldownMaxSeconds).toBe(600)
    expect(streaming.autoDeterrenceCooldownSeconds).toBeGreaterThanOrEqual(API_DEFAULTS.deterrenceCooldownMinSeconds)
    expect(streaming.autoDeterrenceCooldownSeconds).toBeLessThanOrEqual(API_DEFAULTS.deterrenceCooldownMaxSeconds)

    expect(API_DEFAULTS.detectionCooldownMinSeconds).toBe(1)
    expect(API_DEFAULTS.detectionCooldownMaxSeconds).toBe(300)
    expect(streaming.motionCooldownSeconds).toBeGreaterThanOrEqual(API_DEFAULTS.detectionCooldownMinSeconds)
    expect(streaming.motionCooldownSeconds).toBeLessThanOrEqual(API_DEFAULTS.detectionCooldownMaxSeconds)
    expect(streaming.soundCooldownSeconds).toBeGreaterThanOrEqual(API_DEFAULTS.detectionCooldownMinSeconds)
    expect(streaming.soundCooldownSeconds).toBeLessThanOrEqual(API_DEFAULTS.detectionCooldownMaxSeconds)
  })
})

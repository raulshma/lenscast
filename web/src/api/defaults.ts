/**
 * Single home for the fallback values and slider bounds the web UI re-types
 * from the Kotlin side (StreamDefaults, the WebApiDtos defaults and the
 * OverlaySettings.DEFAULT overlay block). Components reference these instead
 * of repeating literals, so a default or bound cannot drift per-component.
 *
 * src/contract.test.ts locks these values to the checked-in DTO fixtures in
 * web/contract/, and app's DtoContractFixtureTest pins the same fixtures to
 * the Kotlin DTOs — the three-way lockstep keeps this file honest.
 * See CONTEXT.md "Web API Handlers".
 */
export const API_DEFAULTS = {
  // StreamingSettingsDto / StreamDefaults
  webPort: 8080,
  webStreamingEnabled: true,
  jpegQuality: 70,
  adaptiveBitrateEnabled: false,
  showPreview: true,
  streamAudioEnabled: true,
  streamAudioBitrateKbps: 128,
  streamAudioChannels: 1,
  streamAudioEchoCancellation: true,
  recordingAudioEnabled: true,
  rtspEnabled: false,
  rtspPort: 8554,
  rtspStreamingActive: false,
  webStreamingActive: false,
  // The DTO default on the Kotlin side is '' (unset); 'AUTO' is the neutral
  // encoder-input value the web UI selects and sends.
  rtspInputFormat: 'AUTO',
  // RTSP output resolution wire name (RtspResolution mapper). Applies only
  // after an RTSP restart — the running pipeline keeps the old size.
  rtspResolution: '720p',
  // RTSP video codec wire name (RtspVideoCodec mapper). The swap restarts a
  // live RTSP output; HLS/WebCodecs stay H.264-only until it flips back.
  rtspVideoCodec: 'h264',

  // Slider bounds (StreamDefaults validation bounds)
  jpegQualityMin: 10,
  jpegQualityMax: 100,
  audioBitrateMinKbps: 32,
  audioBitrateMaxKbps: 320,
  rtspPortMin: 1024,
  rtspPortMax: 65535,
  watchdogMaxRetriesMin: 1,
  watchdogMaxRetriesMax: 20,
  watchdogCheckIntervalMinSeconds: 3,
  watchdogCheckIntervalMaxSeconds: 30,

  // Watchdog settings
  watchdogEnabled: false,
  watchdogMaxRetries: 5,
  watchdogCheckIntervalSeconds: 5,

  // Detection (motion/sound), discovery, webhook and backup settings
  mdnsEnabled: true,
  motionDetectionEnabled: false,
  motionSensitivityPercent: 50,
  motionRecordingEnabled: false,
  motionPostRollSeconds: 10,
  motionArmScheduleEnabled: false,
  motionArmStartMinute: 0,
  motionArmEndMinute: 1439,
  soundDetectionEnabled: false,
  soundThresholdPercent: 30,
  webhookEnabled: false,
  webhookUrl: '',
  webhookHeaders: '',
  autoSiren: false,
  autoTorch: false,
  sirenDurationSeconds: 10,
  autoDeterrenceCooldownSeconds: 60,

  // Deterrence slider bounds (StreamDefaults validation bounds)
  sirenDurationMinSeconds: 5,
  sirenDurationMaxSeconds: 60,
  deterrenceCooldownMinSeconds: 30,
  deterrenceCooldownMaxSeconds: 600,

  backupEnabled: false,
  backupWifiOnly: true,
  backupTarget: 'webdav',
  backupWebdavUrl: '',
  backupWebdavUsername: '',
  backupWebdavPassword: '',
  telegramChatId: '',
  telegramBotToken: '',
  apiTokenEnabled: false,
  apiTokenConfigured: false,
  apiToken: '',
  httpsEnabled: false,
  audioDeviceId: '',
  detectionNotificationsEnabled: true,
  tamperDetectionEnabled: false,
  mqttEnabled: false,
  mqttBrokerHost: '',
  mqttBrokerPort: 1883,
  mqttUsername: '',
  mqttPassword: '',
  mqttTls: false,
  mqttDiscoveryPrefix: 'homeassistant',

  // Detection suite extensions (StreamDefaults / StreamingSettingsDto)
  mlDetectionEnabled: false,
  mlMinScorePercent: 60,
  mlModelState: 'not_downloaded',
  mlModelProgress: -1,
  mlModelError: '',
  /** Human-facing model size for the Detection Model row (DetectionModelStore.DISPLAY_SIZE_MB). */
  mlModelSizeLabel: '4.4 MB',
  mlMinScoreMinPercent: 10,
  mlMinScoreMaxPercent: 95,
  continuousRecording: false,
  continuousSegmentMinutes: 15,
  continuousSegmentMinMinutes: 5,
  continuousSegmentMaxMinutes: 60,
  onvifEnabled: false,

  // Retention windows in days; 0 keeps captures/events forever.
  captureRetentionDays: 0,
  eventRetentionDays: 0,
  // Shared 0–365 bound for both retention number inputs (0 = forever).
  retentionMinDays: 0,
  retentionMaxDays: 365,

  // Overlay block (OverlaySettings.DEFAULT, embedded in StreamingSettingsDto)
  overlayEnabled: false,
  overlayPosition: 'TOP_LEFT',
  overlayTextColor: '#FFFFFF',
  overlayBackgroundColor: '#80000000',
  overlayFontSize: 28,
  overlayPadding: 8,
  overlayLineHeight: 4,

  // CameraSettingsDto. cameraIso/cameraExposureTime/cameraColorTemperature/
  // cameraFocusDistance/cameraSceneMode are the UI fallbacks for nullable DTO
  // fields (the Kotlin defaults are null), kept here so they live in one place.
  cameraExposureCompensation: 0,
  cameraIso: 800,
  cameraExposureTime: 10_000_000,
  cameraFocusMode: 'AUTO',
  cameraFocusDistance: 0,
  cameraWhiteBalance: 'AUTO',
  cameraColorTemperature: 5500,
  cameraZoomRatio: 1,
  // Known divergence: the Kotlin DTO default is StreamDefaults.STREAM_FPS (24)
  // while the web select historically falls back to 30; pinned by contract test.
  cameraFrameRate: 30,
  cameraResolution: 'FHD_1080P',
  cameraStabilization: true,
  cameraHdrMode: 'OFF',
  cameraSceneMode: '',
  cameraNightVisionMode: 'OFF',

  // Status fallbacks (used when /api/status has not loaded yet)
  clientCount: 0,
} as const

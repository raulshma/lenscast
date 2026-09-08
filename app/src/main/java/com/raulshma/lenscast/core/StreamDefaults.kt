package com.raulshma.lenscast.core

/**
 * Single home for stream configuration defaults and validation bounds.
 * SettingsDataStore, StreamingManager, the encoders, the watchdog and the
 * Web API DTOs all reference these — never re-copy a default literal or a
 * (min,max) bound, so changing one lands everywhere at once.
 */
object StreamDefaults {
    const val WEB_PORT = 8080
    const val RTSP_PORT = 8554
    const val JPEG_QUALITY = 70
    const val STREAM_FPS = 24
    const val AUDIO_BITRATE_KBPS = 128
    const val AUDIO_CHANNELS = 1
    const val WATCHDOG_MAX_RETRIES = 5
    const val WATCHDOG_CHECK_INTERVAL_SECONDS = 5

    // Validation bounds. The store coerces persisted values into these;
    // StreamingManager, NetworkQualityMonitor and the Web API DTO mapping
    // coerce runtime values with the same pairs.
    const val WEB_PORT_MIN = 1024
    const val WEB_PORT_MAX = 65535
    const val RTSP_PORT_MIN = 1024
    const val RTSP_PORT_MAX = 65535
    const val JPEG_QUALITY_MIN = 10
    const val JPEG_QUALITY_MAX = 100
    const val ADAPTIVE_JPEG_QUALITY_MIN = 15
    const val ADAPTIVE_JPEG_QUALITY_MAX = 95
    const val ADAPTIVE_FPS_MIN = 3
    const val ADAPTIVE_FPS_MAX = 30
    const val AUDIO_BITRATE_MIN_KBPS = 32
    const val AUDIO_BITRATE_MAX_KBPS = 320
    const val AUDIO_CHANNELS_MIN = 1
    const val AUDIO_CHANNELS_MAX = 2
    const val AUDIO_SAMPLE_RATE_HZ = 48_000
    const val WATCHDOG_MAX_RETRIES_MIN = 1
    const val WATCHDOG_MAX_RETRIES_MAX = 20
    const val WATCHDOG_CHECK_INTERVAL_MIN_SECONDS = 3
    const val WATCHDOG_CHECK_INTERVAL_MAX_SECONDS = 30

    // RTSP output bounds. VIDEO_BITRATE_* is the H.264 encoder ceiling;
    // RTSP_FPS_* is the encoder/RTP timestamp ceiling — the persisted frame
    // rate setting may range wider (CameraSettings.FRAME_RATE_MAX), the RTSP
    // output clamps to this.
    const val VIDEO_BITRATE_MIN = 500_000
    const val VIDEO_BITRATE_MAX = 8_000_000
    const val RTSP_FPS_MIN = 1
    const val RTSP_FPS_MAX = 60
    const val RTSP_VIDEO_WIDTH = 1280
    const val RTSP_VIDEO_HEIGHT = 720
    const val RTSP_VIDEO_BITRATE = 2_000_000

    // HTTP fan-out guard: one hotspot phone can't serve unlimited browsers.
    const val MAX_HTTP_CLIENTS = 8

    // Storage manager bounds.
    const val STORAGE_QUOTA_MB_MIN = 100
    const val STORAGE_QUOTA_MB_MAX = 32_768
    const val STORAGE_QUOTA_MB_DEFAULT = 2048
    const val STORAGE_LOW_SPACE_MIN_MB = 200

    // Motion & sound detection bounds. Sensitivity and sound threshold are
    // persisted as percents (1..100); the Settings Applier converts to
    // MotionDetector's 0..1 scale.
    const val MOTION_SENSITIVITY_MIN = 1
    const val MOTION_SENSITIVITY_MAX = 100
    const val MOTION_SENSITIVITY_PERCENT_DEFAULT = 50
    const val MOTION_POST_ROLL_MIN_SECONDS = 0
    const val MOTION_POST_ROLL_MAX_SECONDS = 120
    const val MOTION_POST_ROLL_SECONDS_DEFAULT = 10
    const val MINUTES_PER_DAY = 1_440
    const val MOTION_ARM_START_MINUTE_DEFAULT = 0
    const val MOTION_ARM_END_MINUTE_DEFAULT = 1_439
    const val SOUND_THRESHOLD_MIN = 1
    const val SOUND_THRESHOLD_MAX = 100
    const val SOUND_THRESHOLD_PERCENT_DEFAULT = 30

    // Webhook notification bounds — a URL the notifier will actually POST to.
    const val WEBHOOK_TIMEOUT_MS = 10_000

    // HLS segment pacing.
    const val HLS_SEGMENT_AUS = 48
}

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
}

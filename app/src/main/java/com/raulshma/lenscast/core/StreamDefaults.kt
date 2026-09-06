package com.raulshma.lenscast.core

/**
 * Single home for stream configuration defaults. SettingsDataStore,
 * StreamingManager, the encoders and the Web API DTOs all reference these —
 * never re-copy a default literal, so changing one lands everywhere at once.
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
}

package com.raulshma.lenscast.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.BackupTargetPolicy
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Plain value type for stream-auth settings. All crypto decisions live in
 * [com.raulshma.lenscast.core.StreamAuthCrypto] — the single home the RTSP
 * server and the Web Auth Gate also verify through.
 */
data class StreamAuthSettings(
    val enabled: Boolean = false,
    val username: String = "",
    val passwordHash: String = "",
    val rtspDigestHa1: String = "",
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_settings")

private object Keys {
    val EXPOSURE_COMPENSATION = intPreferencesKey("exposure_compensation")
    val ISO = intPreferencesKey("iso")
    val ISO_AUTO = stringPreferencesKey("iso_auto")
    val EXPOSURE_TIME = longPreferencesKey("exposure_time")
    val EXPOSURE_TIME_AUTO = stringPreferencesKey("exposure_time_auto")
    val FOCUS_MODE = stringPreferencesKey("focus_mode")
    val FOCUS_DISTANCE = floatPreferencesKey("focus_distance")
    val FOCUS_DISTANCE_NULL = stringPreferencesKey("focus_distance_null")
    val WHITE_BALANCE = stringPreferencesKey("white_balance")
    val COLOR_TEMPERATURE = intPreferencesKey("color_temperature")
    val COLOR_TEMPERATURE_NULL = stringPreferencesKey("color_temperature_null")
    val ZOOM_RATIO = floatPreferencesKey("zoom_ratio")
    val FRAME_RATE = intPreferencesKey("frame_rate")
    val RESOLUTION = stringPreferencesKey("resolution")
    val STABILIZATION = stringPreferencesKey("stabilization")
    val TORCH_ENABLED = stringPreferencesKey("torch_enabled")
    val HDR_MODE = stringPreferencesKey("hdr_mode")
    val SCENE_MODE = stringPreferencesKey("scene_mode")
    val STREAMING_PORT = intPreferencesKey("streaming_port")
    val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
    val STREAM_AUDIO_ENABLED = stringPreferencesKey("stream_audio_enabled")
    val STREAM_AUDIO_BITRATE_KBPS = intPreferencesKey("stream_audio_bitrate_kbps")
    val STREAM_AUDIO_CHANNELS = intPreferencesKey("stream_audio_channels")
    val STREAM_AUDIO_ECHO_CANCELLATION = stringPreferencesKey("stream_audio_echo_cancellation")
    val RECORDING_AUDIO_ENABLED = stringPreferencesKey("recording_audio_enabled")
    val WEB_STREAMING_ENABLED = stringPreferencesKey("web_streaming_enabled")
    val AUTH_ENABLED = stringPreferencesKey("auth_enabled")
    val AUTH_USERNAME = stringPreferencesKey("auth_username")
    val AUTH_PASSWORD_HASH = stringPreferencesKey("auth_password_hash")
    val AUTH_RTSP_DIGEST_HA1 = stringPreferencesKey("auth_rtsp_digest_ha1")
    val SHOW_PREVIEW = stringPreferencesKey("show_preview")
    val RTSP_ENABLED = stringPreferencesKey("rtsp_enabled")
    val RTSP_PORT = intPreferencesKey("rtsp_port")
    val RTSP_INPUT_FORMAT = stringPreferencesKey("rtsp_input_format")
    val ADAPTIVE_BITRATE_ENABLED = stringPreferencesKey("adaptive_bitrate_enabled")
    val MDNS_ENABLED = stringPreferencesKey("mdns_enabled")
    val MOTION_DETECTION_ENABLED = stringPreferencesKey("motion_detection_enabled")
    val NIGHT_VISION_MODE = stringPreferencesKey("night_vision_mode")
    val OVERLAY_ENABLED = stringPreferencesKey("overlay_enabled")
    val OVERLAY_SHOW_TIMESTAMP = stringPreferencesKey("overlay_show_timestamp")
    val OVERLAY_TIMESTAMP_FORMAT = stringPreferencesKey("overlay_timestamp_format")
    val OVERLAY_SHOW_BRANDING = stringPreferencesKey("overlay_show_branding")
    val OVERLAY_BRANDING_TEXT = stringPreferencesKey("overlay_branding_text")
    val OVERLAY_SHOW_STATUS = stringPreferencesKey("overlay_show_status")
    val OVERLAY_SHOW_CUSTOM_TEXT = stringPreferencesKey("overlay_show_custom_text")
    val OVERLAY_CUSTOM_TEXT = stringPreferencesKey("overlay_custom_text")
    val OVERLAY_POSITION = stringPreferencesKey("overlay_position")
    val OVERLAY_FONT_SIZE = intPreferencesKey("overlay_font_size")
    val OVERLAY_TEXT_COLOR = stringPreferencesKey("overlay_text_color")
    val OVERLAY_BG_COLOR = stringPreferencesKey("overlay_bg_color")
    val OVERLAY_PADDING = intPreferencesKey("overlay_padding")
    val OVERLAY_LINE_HEIGHT = intPreferencesKey("overlay_line_height")
    val MASKING_ENABLED = stringPreferencesKey("masking_enabled")
    val MASKING_ZONES = stringPreferencesKey("masking_zones")
    val WATCHDOG_ENABLED = stringPreferencesKey("watchdog_enabled")
    val WATCHDOG_MAX_RETRIES = intPreferencesKey("watchdog_max_retries")
    val WATCHDOG_CHECK_INTERVAL_SECONDS = intPreferencesKey("watchdog_check_interval_seconds")
    val UPDATE_AUTO_CHECK_ENABLED = stringPreferencesKey("update_auto_check_enabled")
    val UPDATE_LAST_CHECK_TIME = longPreferencesKey("update_last_check_time")
    val UPDATE_DISMISSED_VERSION = stringPreferencesKey("update_dismissed_version")
    val MOTION_SENSITIVITY = intPreferencesKey("motion_sensitivity_percent")
    val MOTION_ZONES = stringPreferencesKey("motion_zones")
    val MOTION_RECORDING_ENABLED = stringPreferencesKey("motion_recording_enabled")
    val MOTION_POST_ROLL_SECONDS = intPreferencesKey("motion_post_roll_seconds")
    val MOTION_ARM_SCHEDULE_ENABLED = stringPreferencesKey("motion_arm_schedule_enabled")
    val MOTION_ARM_START_MINUTE = intPreferencesKey("motion_arm_start_minute")
    val MOTION_ARM_END_MINUTE = intPreferencesKey("motion_arm_end_minute")
    val SOUND_DETECTION_ENABLED = stringPreferencesKey("sound_detection_enabled")
    val SOUND_THRESHOLD_PERCENT = intPreferencesKey("sound_threshold_percent")
    val WEBHOOK_ENABLED = stringPreferencesKey("webhook_enabled")
    val WEBHOOK_URL = stringPreferencesKey("webhook_url")
    val WEBHOOK_HEADERS = stringPreferencesKey("webhook_headers")
    val AUTO_SIREN = stringPreferencesKey("auto_siren")
    val AUTO_TORCH = stringPreferencesKey("auto_torch")
    val SIREN_DURATION_SECONDS = intPreferencesKey("siren_duration_seconds")
    val AUTO_DETERRENCE_COOLDOWN_SECONDS = intPreferencesKey("auto_deterrence_cooldown_seconds")
    val BACKUP_ENABLED = stringPreferencesKey("backup_enabled")
    val BACKUP_WIFI_ONLY = stringPreferencesKey("backup_wifi_only")
    val BACKUP_TARGET = stringPreferencesKey("backup_target")
    val BACKUP_WEBDAV_URL = stringPreferencesKey("backup_webdav_url")
    val BACKUP_WEBDAV_USERNAME = stringPreferencesKey("backup_webdav_username")
    val BACKUP_WEBDAV_PASSWORD = stringPreferencesKey("backup_webdav_password")
    val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
    val TELEGRAM_CHAT_ID = stringPreferencesKey("telegram_chat_id")
    val API_TOKEN_ENABLED = stringPreferencesKey("api_token_enabled")
    val API_TOKEN_HASH = stringPreferencesKey("api_token_hash")
    val HTTPS_ENABLED = stringPreferencesKey("https_enabled")
    val AUDIO_DEVICE_ID = stringPreferencesKey("audio_device_id")
    val RESUME_STREAMS_ON_BOOT = stringPreferencesKey("resume_streams_on_boot")
}

/**
 * Every persisted setting declared exactly once as a [SettingPref] descriptor:
 * key, decode convention, encode convention, and bounds live together, so a
 * key's read side, write side, and clamp can never drift. The SettingsDataStore
 * derives each public StateFlow and saver from one descriptor. The bounded
 * descriptors are internal so JVM tests can verify every clamp without a
 * Context or DataStore.
 */
internal val cameraSettingsPref = SettingPref(
    default = CameraSettings(),
    decode = ::decodeCameraSettings,
    encode = ::encodeCameraSettings,
)

internal val streamingPortPref = intPref(
    Keys.STREAMING_PORT,
    StreamDefaults.WEB_PORT,
    IntBounds(StreamDefaults.WEB_PORT_MIN, StreamDefaults.WEB_PORT_MAX),
)

internal val jpegQualityPref = intPref(
    Keys.JPEG_QUALITY,
    StreamDefaults.JPEG_QUALITY,
    IntBounds(StreamDefaults.JPEG_QUALITY_MIN, StreamDefaults.JPEG_QUALITY_MAX),
)

internal val showPreviewPref = boolPref(Keys.SHOW_PREVIEW, defaultTrue = true)

internal val streamAudioEnabledPref = boolPref(Keys.STREAM_AUDIO_ENABLED, defaultTrue = true)

internal val streamAudioBitrateKbpsPref = intPref(
    Keys.STREAM_AUDIO_BITRATE_KBPS,
    StreamDefaults.AUDIO_BITRATE_KBPS,
    IntBounds(StreamDefaults.AUDIO_BITRATE_MIN_KBPS, StreamDefaults.AUDIO_BITRATE_MAX_KBPS),
)

internal val streamAudioChannelsPref = intPref(
    Keys.STREAM_AUDIO_CHANNELS,
    StreamDefaults.AUDIO_CHANNELS,
    IntBounds(StreamDefaults.AUDIO_CHANNELS_MIN, StreamDefaults.AUDIO_CHANNELS_MAX),
)

internal val streamAudioEchoCancellationPref =
    boolPref(Keys.STREAM_AUDIO_ECHO_CANCELLATION, defaultTrue = true)

internal val recordingAudioEnabledPref = boolPref(Keys.RECORDING_AUDIO_ENABLED, defaultTrue = true)

internal val webStreamingEnabledPref = boolPref(Keys.WEB_STREAMING_ENABLED, defaultTrue = true)

internal val authSettingsPref = SettingPref(
    default = StreamAuthSettings(),
    decode = { prefs ->
        StreamAuthSettings(
            enabled = readBool(prefs, Keys.AUTH_ENABLED, defaultTrue = false),
            username = prefs[Keys.AUTH_USERNAME] ?: "",
            passwordHash = prefs[Keys.AUTH_PASSWORD_HASH] ?: "",
            rtspDigestHa1 = prefs[Keys.AUTH_RTSP_DIGEST_HA1] ?: "",
        )
    },
    encode = { prefs, settings ->
        writeBool(prefs, Keys.AUTH_ENABLED, settings.enabled)
        prefs[Keys.AUTH_USERNAME] = settings.username
        if (settings.passwordHash.isNotEmpty()) {
            prefs[Keys.AUTH_PASSWORD_HASH] = settings.passwordHash
        }
        if (settings.rtspDigestHa1.isNotEmpty()) {
            prefs[Keys.AUTH_RTSP_DIGEST_HA1] = settings.rtspDigestHa1
        } else {
            prefs.remove(Keys.AUTH_RTSP_DIGEST_HA1)
        }
    },
)

internal val rtspEnabledPref = boolPref(Keys.RTSP_ENABLED, defaultTrue = false)

internal val rtspPortPref = intPref(
    Keys.RTSP_PORT,
    StreamDefaults.RTSP_PORT,
    IntBounds(StreamDefaults.RTSP_PORT_MIN, StreamDefaults.RTSP_PORT_MAX),
)

internal val rtspInputFormatPref = enumPref(Keys.RTSP_INPUT_FORMAT, RtspInputFormat.AUTO)

internal val adaptiveBitrateEnabledPref = boolPref(Keys.ADAPTIVE_BITRATE_ENABLED, defaultTrue = false)

internal val mdnsEnabledPref = boolPref(Keys.MDNS_ENABLED, defaultTrue = true)

internal val motionDetectionEnabledPref = boolPref(Keys.MOTION_DETECTION_ENABLED, defaultTrue = false)

internal val motionSensitivityPref = intPref(
    Keys.MOTION_SENSITIVITY,
    StreamDefaults.MOTION_SENSITIVITY_PERCENT_DEFAULT,
    IntBounds(StreamDefaults.MOTION_SENSITIVITY_MIN, StreamDefaults.MOTION_SENSITIVITY_MAX),
)

internal val motionZonesPref = SettingPref(
    default = emptyList<MotionZone>(),
    decode = { prefs -> parseMotionZones(prefs[Keys.MOTION_ZONES]) },
    encode = { prefs, zones ->
        prefs[Keys.MOTION_ZONES] = serializeMotionZones(zones)
    },
)

internal val motionRecordingEnabledPref = boolPref(Keys.MOTION_RECORDING_ENABLED, defaultTrue = false)

internal val motionPostRollSecondsPref = intPref(
    Keys.MOTION_POST_ROLL_SECONDS,
    StreamDefaults.MOTION_POST_ROLL_SECONDS_DEFAULT,
    IntBounds(StreamDefaults.MOTION_POST_ROLL_MIN_SECONDS, StreamDefaults.MOTION_POST_ROLL_MAX_SECONDS),
)

internal val motionArmScheduleEnabledPref = boolPref(Keys.MOTION_ARM_SCHEDULE_ENABLED, defaultTrue = false)

internal val motionArmStartMinutePref = intPref(
    Keys.MOTION_ARM_START_MINUTE,
    StreamDefaults.MOTION_ARM_START_MINUTE_DEFAULT,
    IntBounds(0, StreamDefaults.MINUTES_PER_DAY - 1),
)

internal val motionArmEndMinutePref = intPref(
    Keys.MOTION_ARM_END_MINUTE,
    StreamDefaults.MOTION_ARM_END_MINUTE_DEFAULT,
    IntBounds(0, StreamDefaults.MINUTES_PER_DAY - 1),
)

internal val soundDetectionEnabledPref = boolPref(Keys.SOUND_DETECTION_ENABLED, defaultTrue = false)

internal val soundThresholdPercentPref = intPref(
    Keys.SOUND_THRESHOLD_PERCENT,
    StreamDefaults.SOUND_THRESHOLD_PERCENT_DEFAULT,
    IntBounds(StreamDefaults.SOUND_THRESHOLD_MIN, StreamDefaults.SOUND_THRESHOLD_MAX),
)

internal val webhookEnabledPref = boolPref(Keys.WEBHOOK_ENABLED, defaultTrue = false)

internal val webhookUrlPref = stringPref(Keys.WEBHOOK_URL, "")

/** Custom webhook POST headers as a JSON `{"Name": "value"}` map string. */
internal val webhookHeadersPref = stringPref(Keys.WEBHOOK_HEADERS, "")

internal val autoSirenPref = boolPref(Keys.AUTO_SIREN, defaultTrue = false)

internal val autoTorchPref = boolPref(Keys.AUTO_TORCH, defaultTrue = false)

internal val sirenDurationSecondsPref = intPref(
    Keys.SIREN_DURATION_SECONDS,
    StreamDefaults.SIREN_DURATION_SECONDS_DEFAULT,
    IntBounds(StreamDefaults.SIREN_DURATION_MIN_SECONDS, StreamDefaults.SIREN_DURATION_MAX_SECONDS),
)

internal val autoDeterrenceCooldownSecondsPref = intPref(
    Keys.AUTO_DETERRENCE_COOLDOWN_SECONDS,
    StreamDefaults.AUTO_DETERRENCE_COOLDOWN_SECONDS_DEFAULT,
    IntBounds(
        StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MIN_SECONDS,
        StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MAX_SECONDS,
    ),
)

internal val backupEnabledPref = boolPref(Keys.BACKUP_ENABLED, defaultTrue = false)

internal val backupWifiOnlyPref = boolPref(Keys.BACKUP_WIFI_ONLY, defaultTrue = true)

/** `"webdav"` (default) or `"telegram"`; anything else decodes back to webdav at the policy. */
internal val backupTargetPref = stringPref(Keys.BACKUP_TARGET, BackupTargetPolicy.DEFAULT_WIRE_NAME)

internal val backupWebdavUrlPref = stringPref(Keys.BACKUP_WEBDAV_URL, "")

internal val backupWebdavUsernamePref = stringPref(Keys.BACKUP_WEBDAV_USERNAME, "")

internal val backupWebdavPasswordPref = stringPref(Keys.BACKUP_WEBDAV_PASSWORD, "")

internal val telegramBotTokenPref = stringPref(Keys.TELEGRAM_BOT_TOKEN, "")

internal val telegramChatIdPref = stringPref(Keys.TELEGRAM_CHAT_ID, "")

/** Whether the read-only API token path is armed; the hash gates nothing while this is off. */
internal val apiTokenEnabledPref = boolPref(Keys.API_TOKEN_ENABLED, defaultTrue = false)

/** SHA-256 hex of the API token — the token itself is never persisted. */
internal val apiTokenHashPref = stringPref(Keys.API_TOKEN_HASH, "")

internal val httpsEnabledPref = boolPref(Keys.HTTPS_ENABLED, defaultTrue = false)

internal val audioDeviceIdPref = stringPref(Keys.AUDIO_DEVICE_ID, "")

internal val resumeStreamsOnBootPref = boolPref(Keys.RESUME_STREAMS_ON_BOOT, defaultTrue = false)


internal val watchdogEnabledPref = boolPref(Keys.WATCHDOG_ENABLED, defaultTrue = false)

internal val watchdogMaxRetriesPref = intPref(
    Keys.WATCHDOG_MAX_RETRIES,
    StreamDefaults.WATCHDOG_MAX_RETRIES,
    IntBounds(StreamDefaults.WATCHDOG_MAX_RETRIES_MIN, StreamDefaults.WATCHDOG_MAX_RETRIES_MAX),
)

internal val watchdogCheckIntervalSecondsPref = intPref(
    Keys.WATCHDOG_CHECK_INTERVAL_SECONDS,
    StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS,
    IntBounds(
        StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS,
        StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS,
    ),
)

internal val overlaySettingsPref = SettingPref(
    default = OverlaySettings.DEFAULT,
    decode = ::decodeOverlaySettings,
    encode = ::encodeOverlaySettings,
)

internal val updateAutoCheckEnabledPref = boolPref(Keys.UPDATE_AUTO_CHECK_ENABLED, defaultTrue = true)

internal val updateLastCheckTimePref = longPref(Keys.UPDATE_LAST_CHECK_TIME, default = 0L)

internal val updateDismissedVersionPref = stringPref(Keys.UPDATE_DISMISSED_VERSION, default = "")

/** The composite camera-settings decode: every key keeps its exact read convention. */
private fun decodeCameraSettings(prefs: Preferences): CameraSettings = CameraSettings(
    exposureCompensation = prefs[Keys.EXPOSURE_COMPENSATION] ?: 0,
    iso = if (prefs[Keys.ISO_AUTO] == "false") prefs[Keys.ISO] else null,
    exposureTime = if (prefs[Keys.EXPOSURE_TIME_AUTO] == "false") prefs[Keys.EXPOSURE_TIME] else null,
    focusMode = parseEnum(prefs[Keys.FOCUS_MODE], FocusMode.AUTO),
    focusDistance = if (prefs[Keys.FOCUS_DISTANCE_NULL] != "true") prefs[Keys.FOCUS_DISTANCE] else null,
    whiteBalance = parseEnum(prefs[Keys.WHITE_BALANCE], WhiteBalance.AUTO),
    colorTemperature = if (prefs[Keys.COLOR_TEMPERATURE_NULL] != "true") prefs[Keys.COLOR_TEMPERATURE] else null,
    zoomRatio = prefs[Keys.ZOOM_RATIO] ?: 1.0f,
    frameRate = prefs[Keys.FRAME_RATE] ?: StreamDefaults.STREAM_FPS,
    resolution = parseEnum(prefs[Keys.RESOLUTION], Resolution.FHD_1080P),
    stabilization = readBool(prefs, Keys.STABILIZATION, defaultTrue = true),
    hdrMode = parseEnum(prefs[Keys.HDR_MODE], HdrMode.OFF),
    sceneMode = prefs[Keys.SCENE_MODE],
    nightVisionMode = parseEnum(prefs[Keys.NIGHT_VISION_MODE], NightVisionMode.OFF),
    torchEnabled = readBool(prefs, Keys.TORCH_ENABLED, defaultTrue = false),
)

private fun encodeCameraSettings(prefs: MutablePreferences, settings: CameraSettings) {
    prefs[Keys.EXPOSURE_COMPENSATION] = settings.exposureCompensation
    if (settings.iso != null) {
        prefs[Keys.ISO] = settings.iso
        prefs[Keys.ISO_AUTO] = "false"
    } else {
        prefs[Keys.ISO_AUTO] = "true"
    }
    if (settings.exposureTime != null) {
        prefs[Keys.EXPOSURE_TIME] = settings.exposureTime
        prefs[Keys.EXPOSURE_TIME_AUTO] = "false"
    } else {
        prefs[Keys.EXPOSURE_TIME_AUTO] = "true"
    }
    prefs[Keys.FOCUS_MODE] = settings.focusMode.name
    if (settings.focusDistance != null) {
        prefs[Keys.FOCUS_DISTANCE] = settings.focusDistance
        prefs[Keys.FOCUS_DISTANCE_NULL] = "false"
    } else {
        prefs[Keys.FOCUS_DISTANCE_NULL] = "true"
    }
    prefs[Keys.WHITE_BALANCE] = settings.whiteBalance.name
    if (settings.colorTemperature != null) {
        prefs[Keys.COLOR_TEMPERATURE] = settings.colorTemperature
        prefs[Keys.COLOR_TEMPERATURE_NULL] = "false"
    } else {
        prefs[Keys.COLOR_TEMPERATURE_NULL] = "true"
    }
    prefs[Keys.ZOOM_RATIO] = settings.zoomRatio
    prefs[Keys.FRAME_RATE] = settings.frameRate
    prefs[Keys.RESOLUTION] = settings.resolution.name
    writeBool(prefs, Keys.STABILIZATION, settings.stabilization)
    prefs[Keys.HDR_MODE] = settings.hdrMode.name
    if (settings.sceneMode != null) {
        prefs[Keys.SCENE_MODE] = settings.sceneMode
    } else {
        prefs.remove(Keys.SCENE_MODE)
    }
    prefs[Keys.NIGHT_VISION_MODE] = settings.nightVisionMode.name
    writeBool(prefs, Keys.TORCH_ENABLED, settings.torchEnabled)
}

private fun decodeOverlaySettings(prefs: Preferences): OverlaySettings {
    val defaults = OverlaySettings.DEFAULT
    return OverlaySettings(
        enabled = readBool(prefs, Keys.OVERLAY_ENABLED, defaultTrue = false),
        showTimestamp = readBool(prefs, Keys.OVERLAY_SHOW_TIMESTAMP, defaultTrue = true),
        timestampFormat = prefs[Keys.OVERLAY_TIMESTAMP_FORMAT] ?: defaults.timestampFormat,
        showBranding = readBool(prefs, Keys.OVERLAY_SHOW_BRANDING, defaultTrue = false),
        brandingText = prefs[Keys.OVERLAY_BRANDING_TEXT] ?: defaults.brandingText,
        showStatus = readBool(prefs, Keys.OVERLAY_SHOW_STATUS, defaultTrue = false),
        showCustomText = readBool(prefs, Keys.OVERLAY_SHOW_CUSTOM_TEXT, defaultTrue = false),
        customText = prefs[Keys.OVERLAY_CUSTOM_TEXT] ?: defaults.customText,
        position = parseEnum(prefs[Keys.OVERLAY_POSITION], OverlayPosition.TOP_LEFT),
        fontSize = prefs[Keys.OVERLAY_FONT_SIZE] ?: defaults.fontSize,
        textColor = prefs[Keys.OVERLAY_TEXT_COLOR] ?: defaults.textColor,
        backgroundColor = prefs[Keys.OVERLAY_BG_COLOR] ?: defaults.backgroundColor,
        padding = prefs[Keys.OVERLAY_PADDING] ?: defaults.padding,
        lineHeight = prefs[Keys.OVERLAY_LINE_HEIGHT] ?: defaults.lineHeight,
        maskingEnabled = readBool(prefs, Keys.MASKING_ENABLED, defaultTrue = false),
        maskingZones = parseMaskingZones(prefs[Keys.MASKING_ZONES]),
    )
}

private fun encodeOverlaySettings(prefs: MutablePreferences, settings: OverlaySettings) {
    // The save-side clamp owner: out-of-range overlay/masking values are
    // coerced here, whatever the writer (settings screen or Web API).
    val normalized = OverlaySettings.normalized(settings)
    writeBool(prefs, Keys.OVERLAY_ENABLED, normalized.enabled)
    writeBool(prefs, Keys.OVERLAY_SHOW_TIMESTAMP, normalized.showTimestamp)
    prefs[Keys.OVERLAY_TIMESTAMP_FORMAT] = normalized.timestampFormat
    writeBool(prefs, Keys.OVERLAY_SHOW_BRANDING, normalized.showBranding)
    prefs[Keys.OVERLAY_BRANDING_TEXT] = normalized.brandingText
    writeBool(prefs, Keys.OVERLAY_SHOW_STATUS, normalized.showStatus)
    writeBool(prefs, Keys.OVERLAY_SHOW_CUSTOM_TEXT, normalized.showCustomText)
    prefs[Keys.OVERLAY_CUSTOM_TEXT] = normalized.customText
    prefs[Keys.OVERLAY_POSITION] = normalized.position.name
    prefs[Keys.OVERLAY_FONT_SIZE] = normalized.fontSize
    prefs[Keys.OVERLAY_TEXT_COLOR] = normalized.textColor
    prefs[Keys.OVERLAY_BG_COLOR] = normalized.backgroundColor
    prefs[Keys.OVERLAY_PADDING] = normalized.padding
    prefs[Keys.OVERLAY_LINE_HEIGHT] = normalized.lineHeight
    writeBool(prefs, Keys.MASKING_ENABLED, normalized.maskingEnabled)
    prefs[Keys.MASKING_ZONES] = serializeMaskingZones(normalized.maskingZones)
}

/**
 * The persisted masking-zone JSON shape, behind [AppJson]'s one Moshi
 * instance. The field names, types, and declaration order mirror the original
 * hand-typed org.json writer byte for byte — they are frozen so
 * previously persisted DataStore values keep decoding. Every field is
 * nullable so an absent key can fold back to its documented per-field
 * fallback exactly as the old `opt*` reads did (DEFAULT values, fresh UUID
 * for an absent id) — never make these fields non-nullable, or old payloads
 * with partial zones stop decoding.
 */
private data class MaskingZoneJson(
    val id: String? = null,
    val label: String? = null,
    val enabled: Boolean? = null,
    val type: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val pixelateSize: Int? = null,
    val blurRadius: Double? = null,
)

private val maskingZonesType = Types.newParameterizedType(
    List::class.java,
    MaskingZoneJson::class.java,
)

private val maskingZonesAdapter by lazy {
    AppJson.moshi.adapter<List<MaskingZoneJson>>(maskingZonesType)
}

/**
 * The masking-zone persistence codec. Decode is total: a null/empty string
 * decodes to an empty list, any malformed payload logs and falls back to an
 * empty list, and absent fields fold to their fallbacks. Internal so JVM
 * tests can pin the persisted shape and the legacy/malformed handling
 * without a Context or DataStore.
 */
internal fun parseMaskingZones(jsonString: String?): List<MaskingZone> {
    if (jsonString.isNullOrEmpty()) return emptyList()
    return try {
        val defaults = MaskingZone.DEFAULT
        val dtos = maskingZonesAdapter.fromJson(jsonString) ?: return emptyList()
        dtos.map { dto ->
            MaskingZone(
                id = dto.id ?: java.util.UUID.randomUUID().toString(),
                label = dto.label ?: defaults.label,
                enabled = dto.enabled ?: defaults.enabled,
                type = dto.type?.let { parseEnum(it, MaskingType.BLACKOUT) } ?: MaskingType.BLACKOUT,
                x = (dto.x ?: defaults.x.toDouble()).toFloat(),
                y = (dto.y ?: defaults.y.toDouble()).toFloat(),
                width = (dto.width ?: defaults.width.toDouble()).toFloat(),
                height = (dto.height ?: defaults.height.toDouble()).toFloat(),
                pixelateSize = dto.pixelateSize ?: defaults.pixelateSize,
                blurRadius = (dto.blurRadius ?: defaults.blurRadius.toDouble()).toFloat(),
            )
        }
    } catch (e: Exception) {
        Log.e("SettingsDataStore", "Failed to parse masking zones", e)
        emptyList()
    }
}

internal fun serializeMaskingZones(zones: List<MaskingZone>): String {
    return try {
        val dtos = zones.map { zone ->
            MaskingZoneJson(
                id = zone.id,
                label = zone.label,
                enabled = zone.enabled,
                type = zone.type.name,
                x = zone.x.toDouble(),
                y = zone.y.toDouble(),
                width = zone.width.toDouble(),
                height = zone.height.toDouble(),
                pixelateSize = zone.pixelateSize,
                blurRadius = zone.blurRadius.toDouble(),
            )
        }
        maskingZonesAdapter.toJson(dtos)
    } catch (e: Exception) {
        Log.e("SettingsDataStore", "Failed to serialize masking zones", e)
        "[]"
    }
}

/**
 * The persisted motion-zone JSON shape. New persistence (no legacy payload to
 * stay decode-compatible with), but the same nullable-field + total-decode
 * conventions as the masking codec, so a malformed payload degrades to an
 * empty zone list instead of killing the whole store decode.
 */
private data class MotionZoneJson(
    val id: String? = null,
    val label: String? = null,
    val enabled: Boolean? = null,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
)

private val motionZonesType = Types.newParameterizedType(
    List::class.java,
    MotionZoneJson::class.java,
)

private val motionZonesAdapter by lazy {
    AppJson.moshi.adapter<List<MotionZoneJson>>(motionZonesType)
}

internal fun parseMotionZones(jsonString: String?): List<MotionZone> {
    if (jsonString.isNullOrEmpty()) return emptyList()
    return try {
        val defaults = MotionZone.DEFAULT
        val dtos = motionZonesAdapter.fromJson(jsonString) ?: return emptyList()
        dtos.map { dto ->
            MotionZone.normalized(
                MotionZone(
                    id = dto.id ?: java.util.UUID.randomUUID().toString(),
                    label = dto.label ?: defaults.label,
                    enabled = dto.enabled ?: defaults.enabled,
                    x = (dto.x ?: defaults.x.toDouble()).toFloat(),
                    y = (dto.y ?: defaults.y.toDouble()).toFloat(),
                    width = (dto.width ?: defaults.width.toDouble()).toFloat(),
                    height = (dto.height ?: defaults.height.toDouble()).toFloat(),
                ),
            )
        }
    } catch (e: Exception) {
        Log.e("SettingsDataStore", "Failed to parse motion zones", e)
        emptyList()
    }
}

internal fun serializeMotionZones(zones: List<MotionZone>): String {
    return try {
        val dtos = zones.map { zone ->
            val normalized = MotionZone.normalized(zone)
            MotionZoneJson(
                id = normalized.id,
                label = normalized.label,
                enabled = normalized.enabled,
                x = normalized.x.toDouble(),
                y = normalized.y.toDouble(),
                width = normalized.width.toDouble(),
                height = normalized.height.toDouble(),
            )
        }
        motionZonesAdapter.toJson(dtos)
    } catch (e: Exception) {
        Log.e("SettingsDataStore", "Failed to serialize motion zones", e)
        "[]"
    }
}

class SettingsDataStore(
    private val context: Context,
    private val shareInScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    // Every setting is shared exactly once, derived from its descriptor.
    // Consumers (SettingsViewModel, Web API handlers, SettingsApplier) read
    // these StateFlows directly instead of re-wrapping the flows with
    // hand-retyped initial values.
    private fun <T> SettingPref<T>.shared(): StateFlow<T> = context.dataStore.data.map(decode)
        .stateIn(shareInScope, SharingStarted.Eagerly, default)

    /**
     * The current disk value of one setting, suspending until DataStore's
     * first read lands. The shared StateFlows above start at the descriptor
     * default, so an immediate `.value` read from a fresh process (the boot
     * receiver) can race the disk — startup-critical reads go through here.
     */
    private suspend fun <T> SettingPref<T>.diskValue(): T =
        context.dataStore.data.map(decode).first()

    private suspend fun <T> SettingPref<T>.save(value: T) {
        context.dataStore.edit { prefs -> encode(prefs, value) }
    }

    val settings: StateFlow<CameraSettings> = cameraSettingsPref.shared()

    val streamingPort: StateFlow<Int> = streamingPortPref.shared()

    val jpegQuality: StateFlow<Int> = jpegQualityPref.shared()

    val showPreview: StateFlow<Boolean> = showPreviewPref.shared()

    val streamAudioEnabled: StateFlow<Boolean> = streamAudioEnabledPref.shared()

    val streamAudioBitrateKbps: StateFlow<Int> = streamAudioBitrateKbpsPref.shared()

    val streamAudioChannels: StateFlow<Int> = streamAudioChannelsPref.shared()

    val streamAudioEchoCancellation: StateFlow<Boolean> = streamAudioEchoCancellationPref.shared()

    val recordingAudioEnabled: StateFlow<Boolean> = recordingAudioEnabledPref.shared()

    val webStreamingEnabled: StateFlow<Boolean> = webStreamingEnabledPref.shared()

    val authSettings: StateFlow<StreamAuthSettings> = authSettingsPref.shared()

    val rtspEnabled: StateFlow<Boolean> = rtspEnabledPref.shared()

    val rtspPort: StateFlow<Int> = rtspPortPref.shared()

    val rtspInputFormat: StateFlow<RtspInputFormat> = rtspInputFormatPref.shared()

    val adaptiveBitrateEnabled: StateFlow<Boolean> = adaptiveBitrateEnabledPref.shared()

    val mdnsEnabled: StateFlow<Boolean> = mdnsEnabledPref.shared()

    val motionDetectionEnabled: StateFlow<Boolean> = motionDetectionEnabledPref.shared()

    val motionSensitivity: StateFlow<Int> = motionSensitivityPref.shared()

    val motionZones: StateFlow<List<MotionZone>> = motionZonesPref.shared()

    val motionRecordingEnabled: StateFlow<Boolean> = motionRecordingEnabledPref.shared()

    val motionPostRollSeconds: StateFlow<Int> = motionPostRollSecondsPref.shared()

    val motionArmScheduleEnabled: StateFlow<Boolean> = motionArmScheduleEnabledPref.shared()

    val motionArmStartMinute: StateFlow<Int> = motionArmStartMinutePref.shared()

    val motionArmEndMinute: StateFlow<Int> = motionArmEndMinutePref.shared()

    val soundDetectionEnabled: StateFlow<Boolean> = soundDetectionEnabledPref.shared()

    val soundThresholdPercent: StateFlow<Int> = soundThresholdPercentPref.shared()

    val webhookEnabled: StateFlow<Boolean> = webhookEnabledPref.shared()

    val webhookUrl: StateFlow<String> = webhookUrlPref.shared()

    val webhookHeaders: StateFlow<String> = webhookHeadersPref.shared()

    val autoSiren: StateFlow<Boolean> = autoSirenPref.shared()

    val autoTorch: StateFlow<Boolean> = autoTorchPref.shared()

    val sirenDurationSeconds: StateFlow<Int> = sirenDurationSecondsPref.shared()

    val autoDeterrenceCooldownSeconds: StateFlow<Int> = autoDeterrenceCooldownSecondsPref.shared()

    val backupEnabled: StateFlow<Boolean> = backupEnabledPref.shared()

    val backupWifiOnly: StateFlow<Boolean> = backupWifiOnlyPref.shared()

    val backupTarget: StateFlow<String> = backupTargetPref.shared()

    val backupWebdavUrl: StateFlow<String> = backupWebdavUrlPref.shared()

    val backupWebdavUsername: StateFlow<String> = backupWebdavUsernamePref.shared()

    val backupWebdavPassword: StateFlow<String> = backupWebdavPasswordPref.shared()

    val telegramBotToken: StateFlow<String> = telegramBotTokenPref.shared()

    val telegramChatId: StateFlow<String> = telegramChatIdPref.shared()

    val apiTokenEnabled: StateFlow<Boolean> = apiTokenEnabledPref.shared()

    val apiTokenHash: StateFlow<String> = apiTokenHashPref.shared()

    val httpsEnabled: StateFlow<Boolean> = httpsEnabledPref.shared()

    val audioDeviceId: StateFlow<String> = audioDeviceIdPref.shared()

    val resumeStreamsOnBoot: StateFlow<Boolean> = resumeStreamsOnBootPref.shared()

    /** Startup-critical: suspends on the disk value, immune to the flow's default-first race. */
    suspend fun resumeStreamsOnBootNow(): Boolean = resumeStreamsOnBootPref.diskValue()


    val watchdogEnabled: StateFlow<Boolean> = watchdogEnabledPref.shared()

    val watchdogMaxRetries: StateFlow<Int> = watchdogMaxRetriesPref.shared()

    val watchdogCheckIntervalSeconds: StateFlow<Int> = watchdogCheckIntervalSecondsPref.shared()

    val overlaySettings: StateFlow<OverlaySettings> = overlaySettingsPref.shared()

    val updateAutoCheckEnabled: StateFlow<Boolean> = updateAutoCheckEnabledPref.shared()

    val updateLastCheckTime: StateFlow<Long> = updateLastCheckTimePref.shared()

    val updateDismissedVersion: StateFlow<String> = updateDismissedVersionPref.shared()

    suspend fun saveSettings(settings: CameraSettings) = cameraSettingsPref.save(settings)

    suspend fun saveStreamingPort(port: Int) = streamingPortPref.save(port)

    suspend fun saveJpegQuality(quality: Int) = jpegQualityPref.save(quality)

    suspend fun saveShowPreview(show: Boolean) = showPreviewPref.save(show)

    suspend fun saveStreamAudioEnabled(enabled: Boolean) = streamAudioEnabledPref.save(enabled)

    suspend fun saveStreamAudioBitrateKbps(bitrateKbps: Int) = streamAudioBitrateKbpsPref.save(bitrateKbps)

    suspend fun saveStreamAudioChannels(channels: Int) = streamAudioChannelsPref.save(channels)

    suspend fun saveStreamAudioEchoCancellation(enabled: Boolean) =
        streamAudioEchoCancellationPref.save(enabled)

    suspend fun saveRecordingAudioEnabled(enabled: Boolean) = recordingAudioEnabledPref.save(enabled)

    suspend fun saveWebStreamingEnabled(enabled: Boolean) = webStreamingEnabledPref.save(enabled)

    suspend fun saveAuthSettings(settings: StreamAuthSettings) = authSettingsPref.save(settings)

    suspend fun saveRtspEnabled(enabled: Boolean) = rtspEnabledPref.save(enabled)

    suspend fun saveRtspPort(port: Int) = rtspPortPref.save(port)

    suspend fun saveRtspInputFormat(format: RtspInputFormat) = rtspInputFormatPref.save(format)

    suspend fun saveAdaptiveBitrateEnabled(enabled: Boolean) = adaptiveBitrateEnabledPref.save(enabled)

    suspend fun saveWatchdogEnabled(enabled: Boolean) = watchdogEnabledPref.save(enabled)

    suspend fun saveWatchdogMaxRetries(maxRetries: Int) = watchdogMaxRetriesPref.save(maxRetries)

    suspend fun saveWatchdogCheckIntervalSeconds(seconds: Int) =
        watchdogCheckIntervalSecondsPref.save(seconds)

    suspend fun saveMdnsEnabled(enabled: Boolean) = mdnsEnabledPref.save(enabled)

    suspend fun saveMotionDetectionEnabled(enabled: Boolean) = motionDetectionEnabledPref.save(enabled)

    suspend fun saveMotionSensitivity(percent: Int) = motionSensitivityPref.save(percent)

    suspend fun saveMotionZones(zones: List<MotionZone>) = motionZonesPref.save(zones)

    suspend fun saveMotionRecordingEnabled(enabled: Boolean) = motionRecordingEnabledPref.save(enabled)

    suspend fun saveMotionPostRollSeconds(seconds: Int) = motionPostRollSecondsPref.save(seconds)

    suspend fun saveMotionArmScheduleEnabled(enabled: Boolean) = motionArmScheduleEnabledPref.save(enabled)

    suspend fun saveMotionArmStartMinute(minute: Int) = motionArmStartMinutePref.save(minute)

    suspend fun saveMotionArmEndMinute(minute: Int) = motionArmEndMinutePref.save(minute)

    suspend fun saveSoundDetectionEnabled(enabled: Boolean) = soundDetectionEnabledPref.save(enabled)

    suspend fun saveSoundThresholdPercent(percent: Int) = soundThresholdPercentPref.save(percent)

    suspend fun saveWebhookEnabled(enabled: Boolean) = webhookEnabledPref.save(enabled)

    suspend fun saveWebhookUrl(url: String) = webhookUrlPref.save(url)

    suspend fun saveWebhookHeaders(headersJson: String) = webhookHeadersPref.save(headersJson)

    suspend fun saveAutoSiren(enabled: Boolean) = autoSirenPref.save(enabled)

    suspend fun saveAutoTorch(enabled: Boolean) = autoTorchPref.save(enabled)

    suspend fun saveSirenDurationSeconds(seconds: Int) = sirenDurationSecondsPref.save(seconds)

    suspend fun saveAutoDeterrenceCooldownSeconds(seconds: Int) =
        autoDeterrenceCooldownSecondsPref.save(seconds)

    suspend fun saveBackupEnabled(enabled: Boolean) = backupEnabledPref.save(enabled)

    suspend fun saveBackupWifiOnly(wifiOnly: Boolean) = backupWifiOnlyPref.save(wifiOnly)

    suspend fun saveBackupTarget(target: String) = backupTargetPref.save(target)

    suspend fun saveBackupWebdavUrl(url: String) = backupWebdavUrlPref.save(url)

    suspend fun saveBackupWebdavUsername(username: String) = backupWebdavUsernamePref.save(username)

    suspend fun saveBackupWebdavPassword(password: String) = backupWebdavPasswordPref.save(password)

    suspend fun saveTelegramBotToken(token: String) = telegramBotTokenPref.save(token)

    suspend fun saveTelegramChatId(chatId: String) = telegramChatIdPref.save(chatId)

    suspend fun saveApiTokenEnabled(enabled: Boolean) = apiTokenEnabledPref.save(enabled)

    suspend fun saveApiTokenHash(hash: String) = apiTokenHashPref.save(hash)

    suspend fun saveHttpsEnabled(enabled: Boolean) = httpsEnabledPref.save(enabled)

    suspend fun saveAudioDeviceId(id: String) = audioDeviceIdPref.save(id)

    suspend fun saveResumeStreamsOnBoot(enabled: Boolean) = resumeStreamsOnBootPref.save(enabled)

    suspend fun saveOverlaySettings(settings: OverlaySettings) = overlaySettingsPref.save(settings)

    suspend fun saveUpdateAutoCheckEnabled(enabled: Boolean) = updateAutoCheckEnabledPref.save(enabled)

    suspend fun saveUpdateLastCheckTime(timeMs: Long) = updateLastCheckTimePref.save(timeMs)

    suspend fun saveUpdateDismissedVersion(version: String) = updateDismissedVersionPref.save(version)
}

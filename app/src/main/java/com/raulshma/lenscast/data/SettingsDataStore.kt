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
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

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

/** No saver: the frame rate persists through [cameraSettingsPref]. */
internal val frameRatePref = intPref(Keys.FRAME_RATE, StreamDefaults.STREAM_FPS)

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

internal val nightVisionModePref = enumPref(Keys.NIGHT_VISION_MODE, NightVisionMode.OFF)

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

private fun parseMaskingZones(jsonString: String?): List<MaskingZone> {
    if (jsonString.isNullOrEmpty()) return emptyList()
    return try {
        val array = JSONArray(jsonString)
        val defaults = MaskingZone.DEFAULT
        List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            MaskingZone(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                label = obj.optString("label", defaults.label),
                enabled = obj.optBoolean("enabled", defaults.enabled),
                type = parseEnum(obj.optString("type", MaskingType.BLACKOUT.name), MaskingType.BLACKOUT),
                x = obj.optDouble("x", defaults.x.toDouble()).toFloat(),
                y = obj.optDouble("y", defaults.y.toDouble()).toFloat(),
                width = obj.optDouble("width", defaults.width.toDouble()).toFloat(),
                height = obj.optDouble("height", defaults.height.toDouble()).toFloat(),
                pixelateSize = obj.optInt("pixelateSize", defaults.pixelateSize),
                blurRadius = obj.optDouble("blurRadius", defaults.blurRadius.toDouble()).toFloat(),
            )
        }
    } catch (e: Exception) {
        Log.e("SettingsDataStore", "Failed to parse masking zones", e)
        emptyList()
    }
}

private fun serializeMaskingZones(zones: List<MaskingZone>): String {
    return try {
        val array = JSONArray()
        for (zone in zones) {
            val obj = JSONObject()
            obj.put("id", zone.id)
            obj.put("label", zone.label)
            obj.put("enabled", zone.enabled)
            obj.put("type", zone.type.name)
            obj.put("x", zone.x.toDouble())
            obj.put("y", zone.y.toDouble())
            obj.put("width", zone.width.toDouble())
            obj.put("height", zone.height.toDouble())
            obj.put("pixelateSize", zone.pixelateSize)
            obj.put("blurRadius", zone.blurRadius.toDouble())
            array.put(obj)
        }
        array.toString()
    } catch (e: Exception) {
        Log.e("SettingsDataStore", "Failed to serialize masking zones", e)
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

    private suspend fun <T> SettingPref<T>.save(value: T) {
        context.dataStore.edit { prefs -> encode(prefs, value) }
    }

    val settings: StateFlow<CameraSettings> = cameraSettingsPref.shared()

    val streamingPort: StateFlow<Int> = streamingPortPref.shared()

    val frameRate: StateFlow<Int> = frameRatePref.shared()

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

    val watchdogEnabled: StateFlow<Boolean> = watchdogEnabledPref.shared()

    val watchdogMaxRetries: StateFlow<Int> = watchdogMaxRetriesPref.shared()

    val watchdogCheckIntervalSeconds: StateFlow<Int> = watchdogCheckIntervalSecondsPref.shared()

    val nightVisionMode: StateFlow<NightVisionMode> = nightVisionModePref.shared()

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

    suspend fun saveNightVisionMode(mode: NightVisionMode) = nightVisionModePref.save(mode)

    suspend fun saveOverlaySettings(settings: OverlaySettings) = overlaySettingsPref.save(settings)

    suspend fun saveUpdateAutoCheckEnabled(enabled: Boolean) = updateAutoCheckEnabledPref.save(enabled)

    suspend fun saveUpdateLastCheckTime(timeMs: Long) = updateLastCheckTimePref.save(timeMs)

    suspend fun saveUpdateDismissedVersion(version: String) = updateDismissedVersionPref.save(version)
}

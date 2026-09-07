package com.raulshma.lenscast.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
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

class SettingsDataStore(
    private val context: Context,
    private val shareInScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

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

    // Every setting is shared exactly once, here. Consumers (SettingsViewModel,
    // Web API handlers, SettingsApplier) read these StateFlows directly instead
    // of re-wrapping the flows with hand-retyped initial values.
    private fun <T> shared(
        default: T,
        transform: (Preferences) -> T,
    ): StateFlow<T> = context.dataStore.data.map(transform)
        .stateIn(shareInScope, SharingStarted.Eagerly, default)

    val settings: StateFlow<CameraSettings> = shared(CameraSettings()) { prefs ->
        CameraSettings(
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
            stabilization = prefs[Keys.STABILIZATION] != "false",
            hdrMode = parseEnum(prefs[Keys.HDR_MODE], HdrMode.OFF),
            sceneMode = prefs[Keys.SCENE_MODE],
            nightVisionMode = parseEnum(prefs[Keys.NIGHT_VISION_MODE], NightVisionMode.OFF),
        )
    }

    val streamingPort: StateFlow<Int> = shared(StreamDefaults.WEB_PORT) { prefs ->
        prefs[Keys.STREAMING_PORT] ?: StreamDefaults.WEB_PORT
    }

    val frameRate: StateFlow<Int> = shared(StreamDefaults.STREAM_FPS) { prefs ->
        prefs[Keys.FRAME_RATE] ?: StreamDefaults.STREAM_FPS
    }

    val jpegQuality: StateFlow<Int> = shared(StreamDefaults.JPEG_QUALITY) { prefs ->
        prefs[Keys.JPEG_QUALITY] ?: StreamDefaults.JPEG_QUALITY
    }

    val showPreview: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.SHOW_PREVIEW] != "false"
    }

    val streamAudioEnabled: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.STREAM_AUDIO_ENABLED] != "false"
    }

    val streamAudioBitrateKbps: StateFlow<Int> = shared(StreamDefaults.AUDIO_BITRATE_KBPS) { prefs ->
        prefs[Keys.STREAM_AUDIO_BITRATE_KBPS] ?: StreamDefaults.AUDIO_BITRATE_KBPS
    }

    val streamAudioChannels: StateFlow<Int> = shared(StreamDefaults.AUDIO_CHANNELS) { prefs ->
        prefs[Keys.STREAM_AUDIO_CHANNELS] ?: StreamDefaults.AUDIO_CHANNELS
    }

    val streamAudioEchoCancellation: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.STREAM_AUDIO_ECHO_CANCELLATION] != "false"
    }

    val recordingAudioEnabled: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.RECORDING_AUDIO_ENABLED] != "false"
    }

    val webStreamingEnabled: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.WEB_STREAMING_ENABLED] != "false"
    }

    val authSettings: StateFlow<StreamAuthSettings> = shared(StreamAuthSettings()) { prefs ->
        StreamAuthSettings(
            enabled = prefs[Keys.AUTH_ENABLED] == "true",
            username = prefs[Keys.AUTH_USERNAME] ?: "",
            passwordHash = prefs[Keys.AUTH_PASSWORD_HASH] ?: "",
            rtspDigestHa1 = prefs[Keys.AUTH_RTSP_DIGEST_HA1] ?: "",
        )
    }

    val rtspEnabled: StateFlow<Boolean> = shared(false) { prefs ->
        prefs[Keys.RTSP_ENABLED] == "true"
    }

    val rtspPort: StateFlow<Int> = shared(StreamDefaults.RTSP_PORT) { prefs ->
        prefs[Keys.RTSP_PORT] ?: StreamDefaults.RTSP_PORT
    }

    val rtspInputFormat: StateFlow<RtspInputFormat> = shared(RtspInputFormat.AUTO) { prefs ->
        parseEnum(prefs[Keys.RTSP_INPUT_FORMAT], RtspInputFormat.AUTO)
    }

    val adaptiveBitrateEnabled: StateFlow<Boolean> = shared(false) { prefs ->
        prefs[Keys.ADAPTIVE_BITRATE_ENABLED] == "true"
    }

    val mdnsEnabled: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.MDNS_ENABLED] != "false"
    }

    val watchdogEnabled: StateFlow<Boolean> = shared(false) { prefs ->
        prefs[Keys.WATCHDOG_ENABLED] == "true"
    }

    val watchdogMaxRetries: StateFlow<Int> = shared(StreamDefaults.WATCHDOG_MAX_RETRIES) { prefs ->
        prefs[Keys.WATCHDOG_MAX_RETRIES] ?: StreamDefaults.WATCHDOG_MAX_RETRIES
    }

    val watchdogCheckIntervalSeconds: StateFlow<Int> = shared(StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS) { prefs ->
        prefs[Keys.WATCHDOG_CHECK_INTERVAL_SECONDS] ?: StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS
    }

    val nightVisionMode: StateFlow<NightVisionMode> = shared(NightVisionMode.OFF) { prefs ->
        parseEnum(prefs[Keys.NIGHT_VISION_MODE], NightVisionMode.OFF)
    }

    val overlaySettings: StateFlow<OverlaySettings> = shared(OverlaySettings.DEFAULT) { prefs ->
        val defaults = OverlaySettings.DEFAULT
        OverlaySettings(
            enabled = prefs[Keys.OVERLAY_ENABLED] == "true",
            showTimestamp = prefs[Keys.OVERLAY_SHOW_TIMESTAMP] != "false",
            timestampFormat = prefs[Keys.OVERLAY_TIMESTAMP_FORMAT] ?: defaults.timestampFormat,
            showBranding = prefs[Keys.OVERLAY_SHOW_BRANDING] == "true",
            brandingText = prefs[Keys.OVERLAY_BRANDING_TEXT] ?: defaults.brandingText,
            showStatus = prefs[Keys.OVERLAY_SHOW_STATUS] == "true",
            showCustomText = prefs[Keys.OVERLAY_SHOW_CUSTOM_TEXT] == "true",
            customText = prefs[Keys.OVERLAY_CUSTOM_TEXT] ?: defaults.customText,
            position = parseEnum(prefs[Keys.OVERLAY_POSITION], OverlayPosition.TOP_LEFT),
            fontSize = prefs[Keys.OVERLAY_FONT_SIZE] ?: defaults.fontSize,
            textColor = prefs[Keys.OVERLAY_TEXT_COLOR] ?: defaults.textColor,
            backgroundColor = prefs[Keys.OVERLAY_BG_COLOR] ?: defaults.backgroundColor,
            padding = prefs[Keys.OVERLAY_PADDING] ?: defaults.padding,
            lineHeight = prefs[Keys.OVERLAY_LINE_HEIGHT] ?: defaults.lineHeight,
            maskingEnabled = prefs[Keys.MASKING_ENABLED] == "true",
            maskingZones = parseMaskingZones(prefs[Keys.MASKING_ZONES]),
        )
    }

    val updateAutoCheckEnabled: StateFlow<Boolean> = shared(true) { prefs ->
        prefs[Keys.UPDATE_AUTO_CHECK_ENABLED] != "false"
    }

    val updateLastCheckTime: StateFlow<Long> = shared(0L) { prefs ->
        prefs[Keys.UPDATE_LAST_CHECK_TIME] ?: 0L
    }

    val updateDismissedVersion: StateFlow<String> = shared("") { prefs ->
        prefs[Keys.UPDATE_DISMISSED_VERSION] ?: ""
    }

    suspend fun saveSettings(settings: CameraSettings) {
        context.dataStore.edit { prefs ->
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
            prefs[Keys.STABILIZATION] = if (settings.stabilization) "true" else "false"
            prefs[Keys.HDR_MODE] = settings.hdrMode.name
            if (settings.sceneMode != null) {
                prefs[Keys.SCENE_MODE] = settings.sceneMode
            } else {
                prefs.remove(Keys.SCENE_MODE)
            }
            prefs[Keys.NIGHT_VISION_MODE] = settings.nightVisionMode.name
        }
    }

    suspend fun saveStreamingPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STREAMING_PORT] = port.coerceIn(
                StreamDefaults.WEB_PORT_MIN,
                StreamDefaults.WEB_PORT_MAX,
            )
        }
    }

    suspend fun saveJpegQuality(quality: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.JPEG_QUALITY] = quality.coerceIn(
                StreamDefaults.JPEG_QUALITY_MIN,
                StreamDefaults.JPEG_QUALITY_MAX,
            )
        }
    }

    suspend fun saveShowPreview(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHOW_PREVIEW] = if (show) "true" else "false"
        }
    }

    suspend fun saveStreamAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STREAM_AUDIO_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveStreamAudioBitrateKbps(bitrateKbps: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STREAM_AUDIO_BITRATE_KBPS] = bitrateKbps.coerceIn(
                StreamDefaults.AUDIO_BITRATE_MIN_KBPS,
                StreamDefaults.AUDIO_BITRATE_MAX_KBPS,
            )
        }
    }

    suspend fun saveStreamAudioChannels(channels: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STREAM_AUDIO_CHANNELS] = channels.coerceIn(
                StreamDefaults.AUDIO_CHANNELS_MIN,
                StreamDefaults.AUDIO_CHANNELS_MAX,
            )
        }
    }

    suspend fun saveStreamAudioEchoCancellation(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STREAM_AUDIO_ECHO_CANCELLATION] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveRecordingAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RECORDING_AUDIO_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveWebStreamingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WEB_STREAMING_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveAuthSettings(settings: StreamAuthSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_ENABLED] = if (settings.enabled) "true" else "false"
            prefs[Keys.AUTH_USERNAME] = settings.username
            if (settings.passwordHash.isNotEmpty()) {
                prefs[Keys.AUTH_PASSWORD_HASH] = settings.passwordHash
            }
            if (settings.rtspDigestHa1.isNotEmpty()) {
                prefs[Keys.AUTH_RTSP_DIGEST_HA1] = settings.rtspDigestHa1
            } else {
                prefs.remove(Keys.AUTH_RTSP_DIGEST_HA1)
            }
        }
    }

    suspend fun saveRtspEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RTSP_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveRtspPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RTSP_PORT] = port.coerceIn(
                StreamDefaults.RTSP_PORT_MIN,
                StreamDefaults.RTSP_PORT_MAX,
            )
        }
    }

    suspend fun saveRtspInputFormat(format: RtspInputFormat) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RTSP_INPUT_FORMAT] = format.name
        }
    }

    suspend fun saveAdaptiveBitrateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ADAPTIVE_BITRATE_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveWatchdogEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WATCHDOG_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveWatchdogMaxRetries(maxRetries: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WATCHDOG_MAX_RETRIES] = maxRetries.coerceIn(
                StreamDefaults.WATCHDOG_MAX_RETRIES_MIN,
                StreamDefaults.WATCHDOG_MAX_RETRIES_MAX,
            )
        }
    }

    suspend fun saveWatchdogCheckIntervalSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WATCHDOG_CHECK_INTERVAL_SECONDS] = seconds.coerceIn(
                StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS,
                StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS,
            )
        }
    }

    suspend fun saveMdnsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MDNS_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveNightVisionMode(mode: NightVisionMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NIGHT_VISION_MODE] = mode.name
        }
    }

    suspend fun saveOverlaySettings(settings: OverlaySettings) {
        // The save-side clamp owner: out-of-range overlay/masking values are
        // coerced here, whatever the writer (settings screen or Web API).
        val normalized = OverlaySettings.normalized(settings)
        context.dataStore.edit { prefs ->
            prefs[Keys.OVERLAY_ENABLED] = if (normalized.enabled) "true" else "false"
            prefs[Keys.OVERLAY_SHOW_TIMESTAMP] = if (normalized.showTimestamp) "true" else "false"
            prefs[Keys.OVERLAY_TIMESTAMP_FORMAT] = normalized.timestampFormat
            prefs[Keys.OVERLAY_SHOW_BRANDING] = if (normalized.showBranding) "true" else "false"
            prefs[Keys.OVERLAY_BRANDING_TEXT] = normalized.brandingText
            prefs[Keys.OVERLAY_SHOW_STATUS] = if (normalized.showStatus) "true" else "false"
            prefs[Keys.OVERLAY_SHOW_CUSTOM_TEXT] = if (normalized.showCustomText) "true" else "false"
            prefs[Keys.OVERLAY_CUSTOM_TEXT] = normalized.customText
            prefs[Keys.OVERLAY_POSITION] = normalized.position.name
            prefs[Keys.OVERLAY_FONT_SIZE] = normalized.fontSize
            prefs[Keys.OVERLAY_TEXT_COLOR] = normalized.textColor
            prefs[Keys.OVERLAY_BG_COLOR] = normalized.backgroundColor
            prefs[Keys.OVERLAY_PADDING] = normalized.padding
            prefs[Keys.OVERLAY_LINE_HEIGHT] = normalized.lineHeight
            prefs[Keys.MASKING_ENABLED] = if (normalized.maskingEnabled) "true" else "false"
            prefs[Keys.MASKING_ZONES] = serializeMaskingZones(normalized.maskingZones)
        }
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

    suspend fun saveUpdateAutoCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_AUTO_CHECK_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveUpdateLastCheckTime(timeMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_LAST_CHECK_TIME] = timeMs
        }
    }

    suspend fun saveUpdateDismissedVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_DISMISSED_VERSION] = version
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
}

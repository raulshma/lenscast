package com.raulshma.lenscast.data

import android.content.Context
import android.util.Base64
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
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class StreamAuthSettings(
    val enabled: Boolean = false,
    val username: String = "",
    val passwordHash: String = "",
    val rtspDigestHa1: String = "",
) {
    companion object {
        private const val HASH_PREFIX = "pbkdf2_sha256"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        private const val RTSP_DIGEST_REALM = "LensCast RTSP"

        fun hashPassword(password: String): String {
            if (password.isEmpty()) return ""
            val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
            val derived = derivePassword(password, salt, PBKDF2_ITERATIONS)
            val saltEncoded = Base64.encodeToString(salt, Base64.NO_WRAP)
            val hashEncoded = Base64.encodeToString(derived, Base64.NO_WRAP)
            return "$HASH_PREFIX$$PBKDF2_ITERATIONS$$saltEncoded$$hashEncoded"
        }

        fun verifyPassword(password: String, storedHash: String): Boolean {
            if (password.isEmpty() || storedHash.isEmpty()) return false

            val parts = storedHash.split("$")
            if (parts.size == 4 && parts[0] == HASH_PREFIX) {
                val iterations = parts[1].toIntOrNull() ?: return false
                val salt = decodeBase64(parts[2]) ?: return false
                val expected = decodeBase64(parts[3]) ?: return false
                val candidate = derivePassword(password, salt, iterations)
                return MessageDigest.isEqual(candidate, expected)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val legacyHash = digest.digest(password.toByteArray(Charsets.UTF_8))
            val expectedLegacy = storedHash.toByteArray(Charsets.UTF_8)
            val candidateLegacy = Base64.encodeToString(legacyHash, Base64.NO_WRAP)
                .toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(candidateLegacy, expectedLegacy)
        }

        fun computeRtspDigestHa1(
            username: String,
            password: String,
            realm: String = RTSP_DIGEST_REALM,
        ): String {
            if (username.isEmpty() || password.isEmpty()) return ""
            val input = "$username:$realm:$password"
            return md5Hex(input)
        }

        private fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun derivePassword(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun decodeBase64(value: String): ByteArray? {
            return runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_settings")

class SettingsDataStore(private val context: Context) {

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
    }

    val settings: Flow<CameraSettings> = context.dataStore.data.map { prefs ->
        CameraSettings(
            exposureCompensation = prefs[Keys.EXPOSURE_COMPENSATION] ?: 0,
            iso = if (prefs[Keys.ISO_AUTO] == "false") prefs[Keys.ISO] else null,
            exposureTime = if (prefs[Keys.EXPOSURE_TIME_AUTO] == "false") prefs[Keys.EXPOSURE_TIME] else null,
            focusMode = try {
                FocusMode.valueOf(prefs[Keys.FOCUS_MODE] ?: FocusMode.AUTO.name)
            } catch (_: Exception) {
                FocusMode.AUTO
            },
            focusDistance = if (prefs[Keys.FOCUS_DISTANCE_NULL] != "true") prefs[Keys.FOCUS_DISTANCE] else null,
            whiteBalance = try {
                WhiteBalance.valueOf(prefs[Keys.WHITE_BALANCE] ?: WhiteBalance.AUTO.name)
            } catch (_: Exception) {
                WhiteBalance.AUTO
            },
            colorTemperature = if (prefs[Keys.COLOR_TEMPERATURE_NULL] != "true") prefs[Keys.COLOR_TEMPERATURE] else null,
            zoomRatio = prefs[Keys.ZOOM_RATIO] ?: 1.0f,
            frameRate = prefs[Keys.FRAME_RATE] ?: 24,
            resolution = try {
                Resolution.valueOf(prefs[Keys.RESOLUTION] ?: Resolution.FHD_1080P.name)
            } catch (_: Exception) {
                Resolution.FHD_1080P
            },
            stabilization = prefs[Keys.STABILIZATION] != "false",
            hdrMode = try {
                HdrMode.valueOf(prefs[Keys.HDR_MODE] ?: HdrMode.OFF.name)
            } catch (_: Exception) {
                HdrMode.OFF
            },
            sceneMode = prefs[Keys.SCENE_MODE],
            nightVisionMode = try {
                NightVisionMode.valueOf(prefs[Keys.NIGHT_VISION_MODE] ?: NightVisionMode.OFF.name)
            } catch (_: Exception) {
                NightVisionMode.OFF
            },
        )
    }

    val streamingPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.STREAMING_PORT] ?: 8080
    }

    val jpegQuality: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.JPEG_QUALITY] ?: 70
    }

    val showPreview: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_PREVIEW] != "false"
    }

    val streamAudioEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STREAM_AUDIO_ENABLED] != "false"
    }

    val streamAudioBitrateKbps: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.STREAM_AUDIO_BITRATE_KBPS] ?: 128
    }

    val streamAudioChannels: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.STREAM_AUDIO_CHANNELS] ?: 1
    }

    val streamAudioEchoCancellation: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STREAM_AUDIO_ECHO_CANCELLATION] != "false"
    }

    val recordingAudioEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECORDING_AUDIO_ENABLED] != "false"
    }

    val webStreamingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WEB_STREAMING_ENABLED] != "false"
    }

    val authSettings: Flow<StreamAuthSettings> = context.dataStore.data.map { prefs ->
        StreamAuthSettings(
            enabled = prefs[Keys.AUTH_ENABLED] == "true",
            username = prefs[Keys.AUTH_USERNAME] ?: "",
            passwordHash = prefs[Keys.AUTH_PASSWORD_HASH] ?: "",
            rtspDigestHa1 = prefs[Keys.AUTH_RTSP_DIGEST_HA1] ?: "",
        )
    }

    val rtspEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.RTSP_ENABLED] == "true"
    }

    val rtspPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.RTSP_PORT] ?: 8554
    }

    val rtspInputFormat: Flow<RtspInputFormat> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.RTSP_INPUT_FORMAT] ?: RtspInputFormat.AUTO.name
        try {
            RtspInputFormat.valueOf(raw)
        } catch (_: Exception) {
            RtspInputFormat.AUTO
        }
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
            prefs[Keys.STREAMING_PORT] = port
        }
    }

    suspend fun saveJpegQuality(quality: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.JPEG_QUALITY] = quality
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
            prefs[Keys.STREAM_AUDIO_BITRATE_KBPS] = bitrateKbps.coerceIn(32, 320)
        }
    }

    suspend fun saveStreamAudioChannels(channels: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STREAM_AUDIO_CHANNELS] = channels.coerceIn(1, 2)
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
            prefs[Keys.RTSP_PORT] = port
        }
    }

    suspend fun saveRtspInputFormat(format: RtspInputFormat) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RTSP_INPUT_FORMAT] = format.name
        }
    }

    val adaptiveBitrateEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ADAPTIVE_BITRATE_ENABLED] == "true"
    }

    val mdnsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.MDNS_ENABLED] != "false"
    }

    suspend fun saveAdaptiveBitrateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ADAPTIVE_BITRATE_ENABLED] = if (enabled) "true" else "false"
        }
    }

    suspend fun saveMdnsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MDNS_ENABLED] = if (enabled) "true" else "false"
        }
    }

    val nightVisionMode: Flow<NightVisionMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.NIGHT_VISION_MODE] ?: NightVisionMode.OFF.name
        try {
            NightVisionMode.valueOf(raw)
        } catch (_: Exception) {
            NightVisionMode.OFF
        }
    }

    suspend fun saveNightVisionMode(mode: NightVisionMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NIGHT_VISION_MODE] = mode.name
        }
    }

    val overlaySettings: Flow<OverlaySettings> = context.dataStore.data.map { prefs ->
        OverlaySettings(
            enabled = prefs[Keys.OVERLAY_ENABLED] == "true",
            showTimestamp = prefs[Keys.OVERLAY_SHOW_TIMESTAMP] != "false",
            timestampFormat = prefs[Keys.OVERLAY_TIMESTAMP_FORMAT] ?: "yyyy-MM-dd HH:mm:ss",
            showBranding = prefs[Keys.OVERLAY_SHOW_BRANDING] == "true",
            brandingText = prefs[Keys.OVERLAY_BRANDING_TEXT] ?: "LensCast",
            showStatus = prefs[Keys.OVERLAY_SHOW_STATUS] == "true",
            showCustomText = prefs[Keys.OVERLAY_SHOW_CUSTOM_TEXT] == "true",
            customText = prefs[Keys.OVERLAY_CUSTOM_TEXT] ?: "",
            position = try {
                OverlayPosition.valueOf(prefs[Keys.OVERLAY_POSITION] ?: OverlayPosition.TOP_LEFT.name)
            } catch (_: Exception) {
                OverlayPosition.TOP_LEFT
            },
            fontSize = prefs[Keys.OVERLAY_FONT_SIZE] ?: 28,
            textColor = prefs[Keys.OVERLAY_TEXT_COLOR] ?: "#FFFFFF",
            backgroundColor = prefs[Keys.OVERLAY_BG_COLOR] ?: "#80000000",
            padding = prefs[Keys.OVERLAY_PADDING] ?: 8,
            lineHeight = prefs[Keys.OVERLAY_LINE_HEIGHT] ?: 4,
            maskingEnabled = prefs[Keys.MASKING_ENABLED] == "true",
            maskingZones = parseMaskingZones(prefs[Keys.MASKING_ZONES]),
        )
    }

    suspend fun saveOverlaySettings(settings: OverlaySettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OVERLAY_ENABLED] = if (settings.enabled) "true" else "false"
            prefs[Keys.OVERLAY_SHOW_TIMESTAMP] = if (settings.showTimestamp) "true" else "false"
            prefs[Keys.OVERLAY_TIMESTAMP_FORMAT] = settings.timestampFormat
            prefs[Keys.OVERLAY_SHOW_BRANDING] = if (settings.showBranding) "true" else "false"
            prefs[Keys.OVERLAY_BRANDING_TEXT] = settings.brandingText
            prefs[Keys.OVERLAY_SHOW_STATUS] = if (settings.showStatus) "true" else "false"
            prefs[Keys.OVERLAY_SHOW_CUSTOM_TEXT] = if (settings.showCustomText) "true" else "false"
            prefs[Keys.OVERLAY_CUSTOM_TEXT] = settings.customText
            prefs[Keys.OVERLAY_POSITION] = settings.position.name
            prefs[Keys.OVERLAY_FONT_SIZE] = settings.fontSize
            prefs[Keys.OVERLAY_TEXT_COLOR] = settings.textColor
            prefs[Keys.OVERLAY_BG_COLOR] = settings.backgroundColor
            prefs[Keys.OVERLAY_PADDING] = settings.padding
            prefs[Keys.OVERLAY_LINE_HEIGHT] = settings.lineHeight
            prefs[Keys.MASKING_ENABLED] = if (settings.maskingEnabled) "true" else "false"
            prefs[Keys.MASKING_ZONES] = serializeMaskingZones(settings.maskingZones)
        }
    }

    private fun parseMaskingZones(jsonString: String?): List<MaskingZone> {
        if (jsonString.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(jsonString)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                MaskingZone(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    label = obj.optString("label", ""),
                    enabled = obj.optBoolean("enabled", true),
                    type = try {
                        MaskingType.valueOf(obj.optString("type", MaskingType.BLACKOUT.name))
                    } catch (_: Exception) {
                        MaskingType.BLACKOUT
                    },
                    x = obj.optDouble("x", 0.0).toFloat(),
                    y = obj.optDouble("y", 0.0).toFloat(),
                    width = obj.optDouble("width", 0.2).toFloat(),
                    height = obj.optDouble("height", 0.2).toFloat(),
                    pixelateSize = obj.optInt("pixelateSize", 16),
                    blurRadius = obj.optDouble("blurRadius", 10.0).toFloat(),
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
}

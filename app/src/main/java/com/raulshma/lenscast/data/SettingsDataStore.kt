package com.raulshma.lenscast.data

import android.content.Context
import android.util.Base64
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
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
}

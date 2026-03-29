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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

data class StreamAuthSettings(
    val enabled: Boolean = false,
    val username: String = "",
    val passwordHash: String = "",
) {
    companion object {
        fun hashPassword(password: String): String {
            if (password.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(hash, Base64.NO_WRAP)
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
        val AUTH_ENABLED = stringPreferencesKey("auth_enabled")
        val AUTH_USERNAME = stringPreferencesKey("auth_username")
        val AUTH_PASSWORD_HASH = stringPreferencesKey("auth_password_hash")
        val SHOW_PREVIEW = stringPreferencesKey("show_preview")
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

    val authSettings: Flow<StreamAuthSettings> = context.dataStore.data.map { prefs ->
        StreamAuthSettings(
            enabled = prefs[Keys.AUTH_ENABLED] == "true",
            username = prefs[Keys.AUTH_USERNAME] ?: "",
            passwordHash = prefs[Keys.AUTH_PASSWORD_HASH] ?: "",
        )
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

    suspend fun saveAuthSettings(settings: StreamAuthSettings, rawPassword: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_ENABLED] = if (settings.enabled) "true" else "false"
            prefs[Keys.AUTH_USERNAME] = settings.username
            if (rawPassword != null) {
                prefs[Keys.AUTH_PASSWORD_HASH] = StreamAuthSettings.hashPassword(rawPassword)
            }
        }
    }
}

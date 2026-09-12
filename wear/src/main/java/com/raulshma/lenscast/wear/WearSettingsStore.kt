package com.raulshma.lenscast.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The watch-side settings persistence: host, port, and credentials for the
 * paired phone's HTTP API, stored in this app's own Preferences DataStore.
 *
 * Zero coupling with the phone app by design — no Wear Data Layer, no
 * shared prefs namespace: whatever the user types on the watch stays on the
 * watch (app-private storage), which is what makes the plain-HTTP design
 * work across APK signatures and store channels.
 *
 * Reads ride the cold [settings] Flow. DataStore keeps its parsed state in
 * memory after the first load, so the API client's per-request
 * `settings.first()` snapshot is a cheap map read — and a settings save
 * takes effect on the very next request by construction.
 */
private val Context.wearDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_settings")

private object Keys {
    val HOST = stringPreferencesKey("host")
    val PORT = intPreferencesKey("port")
    val AUTH_MODE = stringPreferencesKey("auth_mode")
    val USERNAME = stringPreferencesKey("username")
    val PASSWORD = stringPreferencesKey("password")
    val API_TOKEN = stringPreferencesKey("api_token")
    val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
}

class WearSettingsStore(private val context: Context) {

    /** The typed settings stream; the single source every reader consults. */
    val settings: Flow<WearSettings> = context.wearDataStore.data.map { prefs ->
        WearSettings(
            host = prefs[Keys.HOST].orEmpty(),
            port = prefs[Keys.PORT] ?: WearSettings.DEFAULT_PORT,
            authMode = parseAuthMode(prefs[Keys.AUTH_MODE]),
            username = prefs[Keys.USERNAME].orEmpty(),
            password = prefs[Keys.PASSWORD].orEmpty(),
            apiToken = prefs[Keys.API_TOKEN].orEmpty(),
            alertsEnabled = prefs[Keys.ALERTS_ENABLED] ?: true,
        )
    }

    /** The current snapshot in suspend contexts (polls, commands, the UI load). */
    suspend fun current(): WearSettings = settings.first()

    /** Persist the whole settings object in one atomic edit. */
    suspend fun save(settings: WearSettings) {
        context.wearDataStore.edit { prefs ->
            prefs[Keys.HOST] = settings.host.trim()
            prefs[Keys.PORT] = settings.port
            prefs[Keys.AUTH_MODE] = settings.authMode.name
            prefs[Keys.USERNAME] = settings.username.trim()
            prefs[Keys.PASSWORD] = settings.password
            prefs[Keys.API_TOKEN] = settings.apiToken.trim()
            prefs[Keys.ALERTS_ENABLED] = settings.alertsEnabled
        }
    }

    /** Tolerant auth-mode decode: an unknown stored value falls back to the token path. */
    private fun parseAuthMode(raw: String?): AuthMode =
        raw?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() } ?: AuthMode.API_TOKEN
}

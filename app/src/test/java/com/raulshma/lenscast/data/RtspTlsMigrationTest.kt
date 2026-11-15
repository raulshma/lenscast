package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The RTSP-TLS setting's one-time migration (issue #18): before the
 * `rtsp_tls` key existed the RTSP scheme was implied by the HTTPS flag, so a
 * preference map whose key is absent must seed it once from the stored HTTPS
 * value — an existing rtsps user keeps their scheme across the update — and
 * a present key is never touched, so the two toggles are fully independent
 * from then on. Pure over DataStore maps, exactly as the store's init applies
 * them (`context.dataStore.edit(::migrateRtspTlsPrefs)`); the keys mirror the
 * store's (the file-private Keys object is the production spelling). The
 * pairs are explicit `Pair(...)` calls — DataStore's own infix `to` produces
 * its `Preferences.Pair`, not the stdlib pair this helper takes.
 */
class RtspTlsMigrationTest {

    private val rtspTlsKey = stringPreferencesKey("rtsp_tls")
    private val httpsKey = stringPreferencesKey("https_enabled")

    private fun migrate(vararg pairs: Pair<Preferences.Key<String>, String>): Preferences =
        emptyPreferences().toMutablePreferences().also { prefs ->
            pairs.forEach { (key, value) -> prefs[key] = value }
            migrateRtspTlsPrefs(prefs)
        }

    @Test
    fun `absent key seeds from the stored https value`() {
        // HTTPS on → the seeded RTSP TLS value is on: the pre-migration
        // rtsps user keeps their scheme.
        assertEquals("true", migrate(Pair(httpsKey, "true"))[rtspTlsKey])
        // HTTPS off (or absent entirely — the flag's own default) → off.
        assertEquals("false", migrate()[rtspTlsKey])
        assertEquals("false", migrate(Pair(httpsKey, "false"))[rtspTlsKey])
    }

    @Test
    fun `present key is never touched — the toggles stay independent`() {
        // A stored rtsp_tls=false survives an HTTPS-on map: HTTPS no longer
        // forces rtsps://.
        assertEquals("false", migrate(Pair(rtspTlsKey, "false"), Pair(httpsKey, "true"))[rtspTlsKey])
        // And the reverse: rtsp_tls=true survives an HTTPS-off map.
        assertEquals("true", migrate(Pair(rtspTlsKey, "true"))[rtspTlsKey])
    }
}

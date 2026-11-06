package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.emptyPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep-awake toggle's boolean convention: absence decodes ON — the
 * security-phone posture is the default and quitting is the explicit act —
 * the mirror of the automation master toggle's fresh-install conservatism.
 */
class KeepAwakePrefTest {

    private fun <T> SettingPref<T>.encodeTo(value: T) =
        emptyPreferences().toMutablePreferences().also { encode(it, value) }

    private fun <T> SettingPref<T>.roundTrip(value: T) = decode(encodeTo(value))

    @Test
    fun `keep-awake decodes absence as on and round trips`() {
        assertTrue(keepAwakeEnabledPref.decode(emptyPreferences()))
        assertTrue(keepAwakeEnabledPref.roundTrip(true))
        assertFalse(keepAwakeEnabledPref.roundTrip(false))
    }
}

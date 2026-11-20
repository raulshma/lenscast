package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.emptyPreferences
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The section-atomic save's write-side payload ([SettingEntry]): each entry
 * encodes through its descriptor's own encode — the identical clamp and
 * normalize the per-setting saver applies — into ONE shared
 * MutablePreferences, and never touches a key outside its descriptor. Pure
 * JVM, same shape as SettingPrefTest.
 */
class SettingEntryTest {

    @Test
    fun `entry encode applies the descriptor clamp`() {
        val prefs = emptyPreferences().toMutablePreferences()
        SettingEntry(rtspPortPref, 70_000).encode(prefs)
        assertEquals(StreamDefaults.RTSP_PORT_MAX, rtspPortPref.decode(prefs))
    }

    @Test
    fun `entry encode applies the descriptor normalize`() {
        val prefs = emptyPreferences().toMutablePreferences()
        SettingEntry(mqttDiscoveryPrefixPref, " home/assist/ ").encode(prefs)
        assertEquals("home/assist", mqttDiscoveryPrefixPref.decode(prefs))
    }

    @Test
    fun `entries of mixed setting types share one preference map`() {
        val prefs = emptyPreferences().toMutablePreferences()
        SettingEntry(rtspPortPref, 8555).encode(prefs)
        SettingEntry(rtspEnabledPref, true).encode(prefs)
        SettingEntry(soundTriggerClassesPref, setOf("Dog", "bogus-class")).encode(prefs)
        assertEquals(8555, rtspPortPref.decode(prefs))
        assertEquals(true, rtspEnabledPref.decode(prefs))
        // The string-set descriptor normalized on encode, as its saver does:
        // the unknown spelling drops out.
        assertEquals(setOf("Dog"), soundTriggerClassesPref.decode(prefs))
        // And nothing outside the three descriptors' keys was written.
        assertEquals(
            setOf("rtsp_port", "rtsp_enabled", "sound_trigger_classes"),
            prefs.asMap().keys.map { it.name }.toSet(),
        )
    }
}

package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.raulshma.lenscast.camera.model.PhotoAspectRatio
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.capture.model.SoundClassPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and fallback conventions for the capture-facing pref
 * descriptors: the shutter flash mode, the photo aspect, the auto-timelapse
 * toggle, and the sound-trigger class set (the store side of the sound-class
 * trigger path the DetectionCoordinator reads). Key assertions read through
 * fresh string keys with the same names — the store's key objects are
 * file-private by design.
 */
class CaptureSettingsPrefsTest {

    private val flashKey = stringPreferencesKey("flash_mode")
    private val aspectKey = stringPreferencesKey("photo_aspect_ratio")
    private val autoTimelapseKey = stringPreferencesKey("auto_timelapse_enabled")
    private val triggerClassesKey = stringSetPreferencesKey("sound_trigger_classes")

    private fun <T> SettingPref<T>.encodeTo(value: T) =
        emptyPreferences().toMutablePreferences().also { encode(it, value) }

    @Test
    fun `flash mode round trips and unknown values fold to OFF`() {
        assertEquals(FlashMode.OFF, flashModePref.decode(emptyPreferences()))
        FlashMode.entries.forEach { mode ->
            assertEquals(mode, flashModePref.decode(preferencesOf(flashKey to mode.name)))
        }
        assertEquals(FlashMode.OFF, flashModePref.decode(preferencesOf(flashKey to "STROBE")))
        assertEquals("ON", flashModePref.encodeTo(FlashMode.ON)[flashKey])
    }

    @Test
    fun `photo aspect round trips and unknown values fold to 16x9`() {
        assertEquals(PhotoAspectRatio.R16_9, photoAspectRatioPref.decode(emptyPreferences()))
        PhotoAspectRatio.entries.forEach { aspect ->
            assertEquals(aspect, photoAspectRatioPref.decode(preferencesOf(aspectKey to aspect.name)))
        }
        assertEquals(
            PhotoAspectRatio.R16_9,
            photoAspectRatioPref.decode(preferencesOf(aspectKey to "1:1")),
        )
        assertEquals("R4_3", photoAspectRatioPref.encodeTo(PhotoAspectRatio.R4_3)[aspectKey])
    }

    @Test
    fun `auto timelapse defaults on and round trips the default-true convention`() {
        // Default-true convention: absence reads on; only the literal "false" reads off.
        assertTrue(autoTimelapseEnabledPref.decode(emptyPreferences()))
        assertEquals(false, autoTimelapseEnabledPref.decode(preferencesOf(autoTimelapseKey to "false")))
        assertEquals("false", autoTimelapseEnabledPref.encodeTo(false)[autoTimelapseKey])
    }

    @Test
    fun `sound trigger classes default to the curated trigger set`() {
        assertEquals(
            SoundClassPolicy.DEFAULT_TRIGGER_CLASSES,
            soundTriggerClassesPref.decode(emptyPreferences()),
        )
    }

    @Test
    fun `sound trigger classes normalize through the policy on both sides`() {
        // Decode drops the unknown spelling (persist a valid value).
        val persisted = preferencesOf(triggerClassesKey to setOf("Glass", "bogus-label"))
        assertEquals(setOf("Glass"), soundTriggerClassesPref.decode(persisted))
        // Encode persists the normalized set; an all-off save folds back to
        // the curated default so the chips narrow, never disarm.
        val encoded = soundTriggerClassesPref.encodeTo(setOf("Siren"))
        assertEquals(setOf("Siren"), encoded[triggerClassesKey])
        val folded = soundTriggerClassesPref.encodeTo(emptySet())
        assertEquals(SoundClassPolicy.DEFAULT_TRIGGER_CLASSES, folded[triggerClassesKey])
    }
}

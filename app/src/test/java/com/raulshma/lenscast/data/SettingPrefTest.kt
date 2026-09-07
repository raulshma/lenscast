package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings Store's per-setting descriptors: round-trips, the two boolean
 * conventions, and every bounded field's clamp — all pure, no Context or
 * DataStore required.
 */
class SettingPrefTest {

    private val boolKey = stringPreferencesKey("test_bool")
    private val intKey = intPreferencesKey("test_int")
    private val longKey = longPreferencesKey("test_long")
    private val floatKey = floatPreferencesKey("test_float")
    private val stringKey = stringPreferencesKey("test_string")

    private fun <T> SettingPref<T>.encodeTo(value: T): Preferences =
        emptyPreferences().toMutablePreferences().also { encode(it, value) }

    private fun <T> SettingPref<T>.roundTrip(value: T): T = decode(encodeTo(value))

    // ── Boolean conventions ──

    @Test
    fun `default-true keys decode absence and any non-false string as on`() {
        val pref = boolPref(boolKey, defaultTrue = true)
        assertTrue(pref.decode(emptyPreferences()))
        assertTrue(pref.decode(preferencesOf(boolKey to "true")))
        assertFalse(pref.decode(preferencesOf(boolKey to "false")))
        assertTrue(pref.decode(preferencesOf(boolKey to "garbage")))
    }

    @Test
    fun `default-false keys decode absence and any non-true string as off`() {
        val pref = boolPref(boolKey, defaultTrue = false)
        assertFalse(pref.decode(emptyPreferences()))
        assertTrue(pref.decode(preferencesOf(boolKey to "true")))
        assertFalse(pref.decode(preferencesOf(boolKey to "false")))
        assertFalse(pref.decode(preferencesOf(boolKey to "garbage")))
    }

    @Test
    fun `both boolean conventions round trip`() {
        val defaultTrue = boolPref(boolKey, defaultTrue = true)
        val defaultFalse = boolPref(boolKey, defaultTrue = false)
        assertTrue(defaultTrue.roundTrip(true))
        assertFalse(defaultTrue.roundTrip(false))
        assertTrue(defaultFalse.roundTrip(true))
        assertFalse(defaultFalse.roundTrip(false))
    }

    // ── Numeric prefs: round trip + clamp ──

    @Test
    fun `int pref decodes its default when absent and round trips`() {
        val pref = intPref(intKey, default = 7)
        assertEquals(7, pref.decode(emptyPreferences()))
        assertEquals(7, pref.default)
        assertEquals(42, pref.roundTrip(42))
    }

    @Test
    fun `long and string prefs round trip`() {
        assertEquals(123L, longPref(longKey, 1L).roundTrip(123L))
        assertEquals("abc", stringPref(stringKey, "").roundTrip("abc"))
    }

    @Test
    fun `unbounded int pref stores raw values`() {
        val pref = intPref(intKey, default = 0)
        assertEquals(-5, pref.roundTrip(-5))
        assertEquals(99999, pref.roundTrip(99999))
    }

    @Test
    fun `bounded int pref clamps below, inside and above the range`() {
        val pref = intPref(intKey, default = 5, bounds = IntBounds(10, 20))
        assertEquals(10, pref.roundTrip(3))
        assertEquals(15, pref.roundTrip(15))
        assertEquals(20, pref.roundTrip(99))
        // The flow default is never clamped — it is the documented default.
        assertEquals(5, pref.decode(emptyPreferences()))
    }

    // ── Enum prefs ──

    @Test
    fun `enum pref decodes names, falls back on unknown, and round trips`() {
        val pref = enumPref(stringKey, NightVisionMode.OFF)
        assertEquals(NightVisionMode.OFF, pref.decode(emptyPreferences()))
        assertEquals(NightVisionMode.OFF, pref.decode(preferencesOf(stringKey to "GARBAGE")))
        assertEquals(NightVisionMode.ON, pref.roundTrip(NightVisionMode.ON))
    }

    // ── Every bounded field of the store, wired descriptor by descriptor ──

    @Test
    fun `streaming port saver clamps to the web port bounds`() {
        assertEquals(1024, streamingPortPref.roundTrip(80))
        assertEquals(8080, streamingPortPref.roundTrip(StreamDefaults.WEB_PORT))
        assertEquals(65535, streamingPortPref.roundTrip(70000))
    }

    @Test
    fun `jpeg quality saver clamps to the quality bounds`() {
        assertEquals(10, jpegQualityPref.roundTrip(0))
        assertEquals(70, jpegQualityPref.roundTrip(StreamDefaults.JPEG_QUALITY))
        assertEquals(100, jpegQualityPref.roundTrip(150))
    }

    @Test
    fun `audio bitrate saver clamps to the audio bitrate bounds`() {
        assertEquals(32, streamAudioBitrateKbpsPref.roundTrip(8))
        assertEquals(128, streamAudioBitrateKbpsPref.roundTrip(StreamDefaults.AUDIO_BITRATE_KBPS))
        assertEquals(320, streamAudioBitrateKbpsPref.roundTrip(1000))
    }

    @Test
    fun `audio channels saver clamps to the channel bounds`() {
        assertEquals(1, streamAudioChannelsPref.roundTrip(0))
        assertEquals(2, streamAudioChannelsPref.roundTrip(6))
    }

    @Test
    fun `rtsp port saver clamps to the rtsp port bounds`() {
        assertEquals(1024, rtspPortPref.roundTrip(1))
        assertEquals(8554, rtspPortPref.roundTrip(StreamDefaults.RTSP_PORT))
        assertEquals(65535, rtspPortPref.roundTrip(70000))
    }

    @Test
    fun `watchdog max retries saver clamps to the retry bounds`() {
        assertEquals(1, watchdogMaxRetriesPref.roundTrip(0))
        assertEquals(5, watchdogMaxRetriesPref.roundTrip(StreamDefaults.WATCHDOG_MAX_RETRIES))
        assertEquals(20, watchdogMaxRetriesPref.roundTrip(100))
    }

    @Test
    fun `watchdog check interval saver clamps to the interval bounds`() {
        assertEquals(3, watchdogCheckIntervalSecondsPref.roundTrip(1))
        assertEquals(5, watchdogCheckIntervalSecondsPref.roundTrip(StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS))
        assertEquals(30, watchdogCheckIntervalSecondsPref.roundTrip(120))
    }

    // ── Composite descriptors keep their defaults ──

    @Test
    fun `camera settings descriptor decodes the default CameraSettings from an empty map`() {
        assertEquals(CameraSettings(), cameraSettingsPref.decode(emptyPreferences()))
    }

    @Test
    fun `auth settings descriptor decodes a disabled auth from an empty map`() {
        assertEquals(StreamAuthSettings(), authSettingsPref.decode(emptyPreferences()))
    }

    @Test
    fun `overlay settings descriptor decodes the DEFAULT overlay from an empty map`() {
        assertEquals(OverlaySettings.DEFAULT, overlaySettingsPref.decode(emptyPreferences()))
    }

    @Test
    fun `rtsp input format descriptor round trips`() {
        assertEquals(RtspInputFormat.AUTO, rtspInputFormatPref.decode(emptyPreferences()))
    }
}

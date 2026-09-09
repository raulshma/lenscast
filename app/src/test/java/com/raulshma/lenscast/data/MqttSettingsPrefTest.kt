package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MQTT settings descriptors' own conventions: the save side normalizes
 * the broker host and discovery prefix ("persist a valid value" is the
 * store's invariant), and every decode folds back to its default. Key
 * assertions read through fresh string keys with the same names — the
 * descriptor's key objects are private to the store by design.
 */
class MqttSettingsPrefTest {

    private val hostKey = stringPreferencesKey("mqtt_broker_host")
    private val prefixKey = stringPreferencesKey("mqtt_discovery_prefix")

    private fun <T> SettingPref<T>.encodeTo(value: T) =
        emptyPreferences().toMutablePreferences().also { encode(it, value) }

    @Test
    fun `broker host save strips surrounding whitespace`() {
        val prefs = mqttBrokerHostPref.encodeTo("  broker.local  ")
        assertEquals("broker.local", prefs[hostKey])
    }

    @Test
    fun `discovery prefix save strips whitespace and trailing slash`() {
        val prefs = mqttDiscoveryPrefixPref.encodeTo(" home/ ")
        assertEquals("home", prefs[prefixKey])
    }

    @Test
    fun `absent keys decode to defaults`() {
        val prefs = emptyPreferences()
        assertEquals("", mqttBrokerHostPref.decode(prefs))
        assertEquals(
            com.raulshma.lenscast.core.StreamDefaults.MQTT_DISCOVERY_PREFIX_DEFAULT,
            mqttDiscoveryPrefixPref.decode(prefs),
        )
        assertEquals(false, mqttEnabledPref.decode(prefs))
        assertEquals(true, detectionNotificationsEnabledPref.decode(prefs))
        assertEquals(false, tamperDetectionEnabledPref.decode(prefs))
    }
}

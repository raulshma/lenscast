package com.raulshma.lenscast.core.mqtt

import com.raulshma.lenscast.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure MQTT alert surface: go/no-go verdict, topic layout, discovery
 * payload shape, and the endpoint derivation — no socket anywhere.
 */
class MqttAlertPublisherPolicyTest {

    private val topics = MqttTopics.entityTopics("homeassistant", "device1")

    @Test
    fun `willDispatch requires enabled and a host`() {
        assertTrue(
            MqttAlertPublisher.willDispatch(
                MqttAlertPublisher.Config(true, MqttAlertPublisher.Broker("broker.local", 1883, "", "", false), "homeassistant"),
            ),
        )
        assertFalse(
            MqttAlertPublisher.willDispatch(
                MqttAlertPublisher.Config(false, MqttAlertPublisher.Broker("broker.local", 1883, "", "", false), "homeassistant"),
            ),
        )
        assertFalse(
            MqttAlertPublisher.willDispatch(
                MqttAlertPublisher.Config(true, MqttAlertPublisher.Broker("  ", 1883, "", "", false), "homeassistant"),
            ),
        )
        assertFalse(MqttAlertPublisher.willDispatch(null))
    }

    @Test
    fun `entity topics derive from prefix and device id`() {
        assertEquals("homeassistant/lenscast/device1/status", topics.availability)
        assertEquals(
            "homeassistant/lenscast/device1/motion/state",
            topics.stateTopicFor(MqttTopics.SensorKind.MOTION),
        )
        assertEquals(
            "homeassistant/lenscast/device1/sound/state",
            topics.stateTopicFor(MqttTopics.SensorKind.SOUND),
        )
        assertEquals(
            "homeassistant/lenscast/device1/tamper/state",
            topics.stateTopicFor(MqttTopics.SensorKind.TAMPER),
        )
        assertEquals("homeassistant/lenscast/device1/event", topics.event)
        assertEquals(
            "homeassistant/binary_sensor/lenscast_device1_motion/config",
            topics.discoveryTopicFor(MqttTopics.SensorKind.MOTION),
        )
        assertEquals(
            "homeassistant/binary_sensor/lenscast_device1_tamper/config",
            topics.discoveryTopicFor(MqttTopics.SensorKind.TAMPER),
        )
    }

    @Test
    fun `discovery prefix tolerates a trailing slash`() {
        val trimmed = MqttTopics.entityTopics("home/", "d")
        assertTrue(trimmed.availability.startsWith("home/lenscast/d"))
        assertTrue(
            trimmed.discoveryTopicFor(MqttTopics.SensorKind.MOTION).startsWith("home/binary_sensor/"),
        )
    }

    @Test
    fun `state topic resolves per detection event kind`() {
        for (kind in MqttTopics.SensorKind.entries) {
            assertEquals(kind, MqttTopics.SensorKind.fromOrNull(kind.eventKind))
            assertEquals(
                "homeassistant/lenscast/device1/${kind.eventKind.wireName}/state",
                topics.stateTopicFor(kind),
            )
            assertEquals(
                "homeassistant/binary_sensor/lenscast_device1_${kind.eventKind.wireName}/config",
                topics.discoveryTopicFor(kind),
            )
        }
    }

    @Test
    fun `off_delay values are the documented pulse windows and ride every discovery payload`() {
        // nvr-integration.md: 30 s motion, 5 s sound, 60 s tamper.
        assertEquals(30, MqttTopics.SensorKind.MOTION.offDelaySeconds)
        assertEquals(5, MqttTopics.SensorKind.SOUND.offDelaySeconds)
        assertEquals(60, MqttTopics.SensorKind.TAMPER.offDelaySeconds)
        for (kind in MqttTopics.SensorKind.entries) {
            val json = MqttTopics.discoveryPayload(topics, "device1", "Pixel 9", kind)
            val parsed = AppJson.moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
            assertEquals(kind.offDelaySeconds.toDouble(), parsed["off_delay"])
        }
    }

    @Test
    fun `endpoint credentials travel as a pair and the will is retained offline`() {
        fun config(user: String, password: String) = MqttAlertPublisher.Config(
            true,
            MqttAlertPublisher.Broker("broker.local", 1883, user, password, true),
            "homeassistant",
        )

        val both = MqttAlertPublisher.endpointOf(config("u", "p"), topics, "device1")
        assertEquals("u", both.username)
        assertEquals("p", both.password)
        assertEquals("lenscast_device1", both.clientId)
        assertEquals(topics.availability, both.willTopic)
        assertEquals("offline", String(both.willMessage!!, Charsets.UTF_8))
        assertTrue(both.willRetain)

        // MQTT 3.1.2-22: a password without a username is spec-invalid, so a
        // password-only config must drop both — never send the password flag
        // alone.
        val passwordOnly = MqttAlertPublisher.endpointOf(config("", "p"), topics, "device1")
        assertEquals(null, passwordOnly.username)
        assertEquals(null, passwordOnly.password)
    }

    @Test
    fun `discovery payload is the HA binary_sensor config`() {
        val json = MqttTopics.discoveryPayload(topics, "device1", "Pixel 9", MqttTopics.SensorKind.MOTION)
        val parsed = AppJson.moshi.adapter(Map::class.java).fromJson(json) as Map<*, *>
        assertEquals("LensCast Motion", parsed["name"])
        assertEquals("lenscast_device1_motion", parsed["unique_id"])
        assertEquals(topics.stateTopicFor(MqttTopics.SensorKind.MOTION), parsed["state_topic"])
        assertEquals(topics.availability, parsed["availability_topic"])
        assertEquals("motion", parsed["device_class"])
        assertEquals("ON", parsed["payload_on"])
        assertEquals("OFF", parsed["payload_off"])
        assertEquals(30.0, parsed["off_delay"])
        val device = parsed["device"] as Map<*, *>
        assertEquals(listOf("lenscast_device1"), device["identifiers"])
        assertEquals("Pixel 9", device["name"])
    }

    @Test
    fun `device id special characters are sanitized in entity ids only`() {
        val odd = MqttTopics.entityTopics("ha", "a b/c")
        assertEquals(
            "ha/binary_sensor/lenscast_a_b_c_motion/config",
            odd.discoveryTopicFor(MqttTopics.SensorKind.MOTION),
        )
        // The state base keeps the raw id — it is a topic path, not an entity id.
        assertEquals("ha/lenscast/a b/c/motion/state", odd.stateTopicFor(MqttTopics.SensorKind.MOTION))
    }
}

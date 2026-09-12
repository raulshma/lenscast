package com.raulshma.lenscast.core.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MQTT telemetry wire contract: the stream-state, sensor-state, sensor-
 * discovery, and client-event topics/payloads derived from
 * ([discoveryPrefix], [deviceId], [deviceName]) alone, plus the throttle and
 * tick gates. Pure — the broker never sees a string the tests have not
 * pinned first.
 */
class MqttTelemetryPolicyTest {

    private val topics = MqttTopics.entityTopics("homeassistant/", "dev1")

    @Test
    fun `stream state topics follow the output vocabulary`() {
        assertEquals("homeassistant/lenscast/dev1/stream/web/state", topics.streamStateTopicFor(MqttTopics.StreamOutput.WEB))
        assertEquals("homeassistant/lenscast/dev1/stream/rtsp/state", topics.streamStateTopicFor(MqttTopics.StreamOutput.RTSP))
        assertEquals("homeassistant/lenscast/dev1/stream/rtmp/state", topics.streamStateTopicFor(MqttTopics.StreamOutput.RTMP))
        assertEquals("homeassistant/lenscast/dev1/stream/whip/state", topics.streamStateTopicFor(MqttTopics.StreamOutput.WHIP))
        assertEquals("homeassistant/lenscast/dev1/stream/srt/state", topics.streamStateTopicFor(MqttTopics.StreamOutput.SRT))
    }

    @Test
    fun `the prefix keeps one trailing slash and the base layout holds`() {
        // Trailing slash cleaned at the assembly seam, exactly like the
        // binary_sensor topics.
        assertEquals("homeassistant/lenscast/dev1/status", topics.availability)
    }

    @Test
    fun `sensor state topics sit beside the binary sensor states`() {
        assertEquals("homeassistant/lenscast/dev1/battery/state", topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.BATTERY))
        assertEquals("homeassistant/lenscast/dev1/thermal/state", topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.THERMAL))
        assertEquals("homeassistant/lenscast/dev1/bitrate/state", topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.ENCODED_BITRATE))
        assertEquals("homeassistant/lenscast/dev1/clients/state", topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.CLIENTS))
    }

    @Test
    fun `the sensor discovery payload carries the measurement class and unit`() {
        val payload = MqttTopics.telemetryDiscoveryPayload(
            topics,
            deviceId = "dev1",
            deviceName = "Test Phone",
            sensor = MqttTopics.TelemetrySensor.BATTERY,
        )
        assertTrue(payload.contains("\"device_class\":\"battery\""))
        assertTrue(payload.contains("\"state_class\":\"measurement\""))
        assertTrue(payload.contains("\"unit_of_measurement\":\"%\""))
        assertTrue(payload.contains("\"state_topic\":\"homeassistant/lenscast/dev1/battery/state\""))
        assertTrue(payload.contains("\"unique_id\":\"lenscast_dev1_battery\""))
        assertTrue(payload.contains("\"availability_topic\":\"homeassistant/lenscast/dev1/status\""))
        // A string sensor carries an icon and no unit or state class.
        val thermal = MqttTopics.telemetryDiscoveryPayload(topics, "dev1", "Test Phone", MqttTopics.TelemetrySensor.THERMAL)
        assertTrue(thermal.contains("\"icon\":\"mdi:thermometer\""))
        assertFalse(thermal.contains("unit_of_measurement"))
        assertFalse(thermal.contains("state_class"))
    }

    @Test
    fun `client event payloads are connected-disconnected json`() {
        val connected = String(
            MqttTopics.clientEventPayload(MqttTopics.ClientKind.MJPEG, connected = true, activeCount = 2, timestampMs = 1234),
            Charsets.UTF_8,
        )
        assertTrue(connected.contains("\"kind\":\"mjpeg\""))
        assertTrue(connected.contains("\"event\":\"connected\""))
        assertTrue(connected.contains("\"count\":2"))
        assertTrue(connected.contains("\"timestampMs\":1234"))
        val disconnected = String(
            MqttTopics.clientEventPayload(MqttTopics.ClientKind.RTSP, connected = false, activeCount = 0, timestampMs = 1234),
            Charsets.UTF_8,
        )
        assertTrue(disconnected.contains("\"event\":\"disconnected\""))
    }

    @Test
    fun `client event topics are kind-scoped`() {
        assertEquals("homeassistant/lenscast/dev1/clients/mjpeg/event", topics.clientEventTopicFor(MqttTopics.ClientKind.MJPEG))
        assertEquals("homeassistant/lenscast/dev1/clients/rtsp/event", topics.clientEventTopicFor(MqttTopics.ClientKind.RTSP))
    }

    // ── the pure policy ──

    @Test
    fun `telemetry publishes only when alerts are enabled hosted and the toggle is on`() {
        assertTrue(MqttTelemetryPolicy.shouldPublishTelemetry(alertsEnabled = true, hosted = true, telemetryEnabled = true))
        assertFalse(MqttTelemetryPolicy.shouldPublishTelemetry(alertsEnabled = false, hosted = true, telemetryEnabled = true))
        assertFalse(MqttTelemetryPolicy.shouldPublishTelemetry(alertsEnabled = true, hosted = false, telemetryEnabled = true))
        assertFalse(MqttTelemetryPolicy.shouldPublishTelemetry(alertsEnabled = true, hosted = true, telemetryEnabled = false))
    }

    @Test
    fun `the client event throttle rate-limits per kind`() {
        val sent = HashMap<String, Long>()
        // First event of a kind: the verdict is the stamp to record.
        assertEquals(1_000L, MqttTelemetryPolicy.clientEventVerdict(sent["mjpeg"], nowMs = 1_000).also { stamp ->
            if (stamp != null) sent["mjpeg"] = stamp
        })
        // Same kind inside the window is throttled…
        assertNull(MqttTelemetryPolicy.clientEventVerdict(sent["mjpeg"], nowMs = 1_500))
        // …a different kind is not…
        assertEquals(1_500L, MqttTelemetryPolicy.clientEventVerdict(sent["rtsp"], nowMs = 1_500).also { stamp ->
            if (stamp != null) sent["rtsp"] = stamp
        })
        // …and the window passes.
        assertEquals(
            (1_000L + MqttTelemetryPolicy.CLIENT_EVENT_MIN_INTERVAL_MS),
            MqttTelemetryPolicy.clientEventVerdict(sent["mjpeg"], nowMs = 1_000 + MqttTelemetryPolicy.CLIENT_EVENT_MIN_INTERVAL_MS),
        )
        assertEquals(2, sent.size)
    }

    @Test
    fun `a legacy sensor kind lookup stays intact`() {
        assertNull(MqttTopics.SensorKind.fromOrNull(com.raulshma.lenscast.core.EventKind.TEST))
    }
}

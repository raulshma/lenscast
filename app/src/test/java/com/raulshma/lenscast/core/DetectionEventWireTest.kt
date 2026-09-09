package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-pinning tests for the shared detection-event payload — the one JSON
 * body both remote sinks (webhook and the MQTT event topic) publish, per
 * docs/nvr-integration.md's "Payload shape": exact field names, zones
 * attribution, and the null-omission rule for unknown battery level and
 * missing snapshot.
 */
class DetectionEventWireTest {

    private fun encodeToJson(alert: DetectionAlert): String =
        DetectionEventWire.encode(alert).toString(Charsets.UTF_8)

    @Test
    fun `full motion event carries zone labels, battery and snapshot`() {
        val alert = DetectionAlert(
            kind = EventKind.MOTION,
            value = 12.34,
            zones = listOf("Doorway", "Porch"),
            batteryPercent = 87,
            snapshotJpegBase64 = "aGVsbG8=",
            timestampMs = 1_725_800_000_000,
        )
        val json = encodeToJson(alert)
        assertTrue(json.contains("\"type\":\"motion\""))
        assertTrue(json.contains("\"value\":12.34"))
        assertTrue(json.contains("\"timestampMs\":1725800000000"))
        assertTrue(json.contains("\"source\":\"lenscast\""))
        assertTrue(json.contains("\"zones\":[\"Doorway\",\"Porch\"]"))
        assertTrue(json.contains("\"batteryPercent\":87"))
        assertTrue(json.contains("\"snapshotJpeg\":\"aGVsbG8=\""))
    }

    @Test
    fun `non-motion event ships empty zones, not a missing field`() {
        val alert = DetectionAlert(
            kind = EventKind.TAMPER,
            value = 87.0,
            timestampMs = 1_725_800_000_000,
        )
        val json = encodeToJson(alert)
        assertTrue(json.contains("\"type\":\"tamper\""))
        // The doc promises `[]` for whole-frame or non-motion events, so
        // automations can key on the field's presence unconditionally.
        assertTrue(json.contains("\"zones\":[]"))
    }

    @Test
    fun `unknown battery level and missing snapshot are omitted, not null`() {
        val alert = DetectionAlert(
            kind = EventKind.SOUND,
            value = 41.5,
            timestampMs = 1_725_800_000_000,
        )
        val json = encodeToJson(alert)
        // "omitted when unknown" — a JSON null would break that contract.
        assertFalse(json.contains("\"batteryPercent\""))
        assertFalse(json.contains("\"snapshotJpeg\""))
        assertTrue(json.contains("\"type\":\"sound\""))
    }

    @Test
    fun `payload field set is exactly the documented one`() {
        val alert = DetectionAlert(
            kind = EventKind.MOTION,
            value = 1.0,
            zones = listOf("Doorway"),
            batteryPercent = 50,
            snapshotJpegBase64 = "dg==",
            timestampMs = 0,
        )
        val adapter = AppJson.moshi.adapter(Map::class.java)
        val fields = adapter.fromJson(encodeToJson(alert))!!.keys
        assertEquals(
            setOf("type", "value", "timestampMs", "source", "zones", "batteryPercent", "snapshotJpeg"),
            fields,
        )
    }
}

package com.raulshma.lenscast.data

import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The masking-zone persistence codec behind AppJson's one Moshi instance:
 * the round trip, the frozen legacy wire shape (org.json-era DataStore
 * payloads keep decoding), the per-field fallbacks the old `opt*` reads
 * provided, and the malformed-input fallback to an empty list — all pure,
 * no Context or DataStore required.
 */
class MaskingZoneCodecTest {

    @Test
    fun `zones round trip through the persisted json`() {
        val zones = listOf(
            MaskingZone(
                id = "zone-1",
                label = "Face",
                enabled = true,
                type = MaskingType.PIXELATE,
                x = 0.1f,
                y = 0.25f,
                width = 0.5f,
                height = 0.75f,
                pixelateSize = 32,
                blurRadius = 12.5f,
            ),
            MaskingZone(
                id = "zone-2",
                label = "Plate",
                enabled = false,
                type = MaskingType.BLUR,
                x = 0.5f,
                y = 0.5f,
                width = 0.25f,
                height = 0.25f,
                pixelateSize = 8,
                blurRadius = 50f,
            ),
            MaskingZone(id = "zone-3", type = MaskingType.BLACKOUT),
        )
        assertEquals(zones, parseMaskingZones(serializeMaskingZones(zones)))
    }

    @Test
    fun `empty zones serialize to an empty array and decode back`() {
        assertEquals("[]", serializeMaskingZones(emptyList()))
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones("[]"))
    }

    @Test
    fun `null and empty strings decode to an empty list`() {
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones(null))
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones(""))
    }

    @Test
    fun `the legacy org-json wire shape keeps decoding`() {
        // The pre-Moshi org.json writer's shape: all ten keys, the float
        // fields encoded as doubles (full float precision preserved), and an
        // integral double written without a decimal point.
        val legacy =
            """[{"id":"zone-1","label":"Face","enabled":true,"type":"PIXELATE",""" +
                """"x":0.5,"y":0.25,"width":0.20000000298023224,"height":0.3,""" +
                """"pixelateSize":16,"blurRadius":10}]"""
        val expected = MaskingZone(
            id = "zone-1",
            label = "Face",
            enabled = true,
            type = MaskingType.PIXELATE,
            x = 0.5f,
            y = 0.25f,
            width = 0.2f,
            height = 0.3f,
            pixelateSize = 16,
            blurRadius = 10f,
        )
        assertEquals(listOf(expected), parseMaskingZones(legacy))
    }

    @Test
    fun `absent fields fold to their defaults exactly as the opt reads did`() {
        val zone = parseMaskingZones("""[{"label":"Only"}]""").single()
        assertEquals("Only", zone.label)
        assertEquals(MaskingZone.DEFAULT.enabled, zone.enabled)
        assertEquals(MaskingZone.DEFAULT.type, zone.type)
        assertEquals(MaskingZone.DEFAULT.x, zone.x)
        assertEquals(MaskingZone.DEFAULT.y, zone.y)
        assertEquals(MaskingZone.DEFAULT.width, zone.width)
        assertEquals(MaskingZone.DEFAULT.height, zone.height)
        assertEquals(MaskingZone.DEFAULT.pixelateSize, zone.pixelateSize)
        assertEquals(MaskingZone.DEFAULT.blurRadius, zone.blurRadius)
        // An absent id still mints a fresh UUID, so the zone stays addressable.
        UUID.fromString(zone.id)
        assertNotEquals(zone.id, parseMaskingZones("""[{"label":"Only"}]""").single().id)
    }

    @Test
    fun `unknown type names fall back to blackout`() {
        val zone = parseMaskingZones("""[{"type":"GARBAGE"}]""").single()
        assertEquals(MaskingType.BLACKOUT, zone.type)
    }

    @Test
    fun `unknown keys are ignored and non-object elements take the empty-list fallback`() {
        // Extra keys never broke the old parser and must not break this one.
        val zones = parseMaskingZones("""[{"label":"X","futureField":1}]""")
        assertEquals("X", zones.single().label)
        // A non-object element made the old getJSONObject read throw into
        // the empty-list catch — pinned, not newly tolerated.
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones("[42]"))
    }

    @Test
    fun `malformed payloads log and fall back to an empty list`() {
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones("not json at all"))
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones("""[{"id":}"""))
        assertEquals(emptyList<MaskingZone>(), parseMaskingZones("""{"id":"not-an-array"}"""))
    }

    @Test
    fun `unserializable values fall back to an empty array`() {
        // The old org.json writer threw for NaN doubles and wrote "[]";
        // Moshi throws the same way through the catch.
        assertEquals("[]", serializeMaskingZones(listOf(MaskingZone(blurRadius = Float.NaN))))
    }

    @Test
    fun `serialized output stays inside the frozen field set`() {
        // The persisted shape is a compatibility contract: exactly the ten
        // documented keys, in order, with the legacy types.
        val json = serializeMaskingZones(listOf(MaskingZone(id = "zone-1")))
        val expectedKeys = listOf(
            "id", "label", "enabled", "type", "x", "y", "width", "height",
            "pixelateSize", "blurRadius",
        )
        val first = expectedKeys.first()
        val last = expectedKeys.last()
        assertTrue(json.indexOf("\"$first\"") < json.indexOf("\"$last\""))
        expectedKeys.forEach { key -> assertTrue("missing key $key", json.contains("\"$key\":")) }
    }
}

package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.model.WebUiColumnDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutPresetDto
import com.raulshma.lenscast.streaming.model.WebUiSectionPlacementDto
import com.raulshma.lenscast.streaming.web.WebUiLayoutPolicy
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composite `web_ui_layout_json` preference, without a Context or DataStore. */
class WebUiLayoutPrefTest {

    private val key = stringPreferencesKey("web_ui_layout_json")
    private val adapter = AppJson.moshi.adapter(WebUiLayoutDto::class.java)

    private fun <T> SettingPref<T>.encodeTo(value: T): Preferences =
        emptyPreferences().toMutablePreferences().also { encode(it, value) }

    private fun <T> SettingPref<T>.roundTrip(value: T): T = decode(encodeTo(value))

    @Test
    fun `absence decodes to the exact default under the contracted key`() {
        assertEquals(WebUiLayoutPolicy.defaultLayout(), webUiLayoutPref.default)
        assertEquals(WebUiLayoutPolicy.defaultLayout(), webUiLayoutPref.decode(emptyPreferences()))
        assertFalse(emptyPreferences().asMap().keys.any { it.name == "web_ui_layout_json" })

        val encoded = webUiLayoutPref.encodeTo(WebUiLayoutPolicy.defaultLayout())
        assertEquals(setOf("web_ui_layout_json"), encoded.asMap().keys.map { it.name }.toSet())
        assertNotNull(encoded[key])
        assertEquals(0L, requireNotNull(adapter.fromJson(encoded[key]!!)).updatedAtMs)
    }

    @Test
    fun `null empty and corrupt JSON fold to the default`() {
        assertEquals(WebUiLayoutPolicy.defaultLayout(), parseWebUiLayout(null))
        listOf(
            "",
            "not json",
            "{",
            "null",
            "[]",
            "42",
            """"a string"""",
            """{"column":null}""",
            """{"column":[]}""",
            """{"sections":"not-a-list"}""",
            """{"sections":[null]}""",
            """{"presets":[{"id":"p","name":"Night","sections":[42]}]}""",
        ).forEach { json ->
            assertEquals(
                "corrupt payload: $json",
                WebUiLayoutPolicy.defaultLayout(),
                webUiLayoutPref.decode(preferencesOf(key to json)),
            )
        }
    }

    @Test
    fun `decode sanitizes a valid but hostile persisted document`() {
        val json = """{
            "schemaVersion":99,
            "updatedAtMs":-10,
            "activePresetId":"  custom  ",
            "column":{"widthPx":999,"widthPreset":"bogus","collapsed":true},
            "sections":[
                {"id":"","kind":"builtin","sectionId":" lens ","items":["ignored"]},
                {"id":"","kind":"builtin","sectionId":"lens"},
                {"id":"","kind":"builtin","sectionId":"   "},
                {"id":"c","kind":"custom","sectionId":"","customTitle":"  Quick  ",
                 "items":["iso","iso","ev"],"hiddenItems":["ignored"]}
            ],
            "presets":[{"id":"","name":"  Night  ","column":{"widthPx":1},
                        "sections":[{"id":"","kind":"builtin","sectionId":"channels"}]}]
        }"""
        val decoded = webUiLayoutPref.decode(preferencesOf(key to json))

        assertEquals(StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(0L, decoded.updatedAtMs)
        assertEquals("custom", decoded.activePresetId)
        assertEquals(WebUiColumnDto(560, "custom", true), decoded.column)
        assertEquals(2, decoded.sections.size)
        UUID.fromString(decoded.sections.first().id)
        assertEquals("lens", decoded.sections.first().sectionId)
        assertTrue(decoded.sections.first().items.isEmpty())
        assertEquals(listOf("iso", "ev"), decoded.sections.last().items)
        assertTrue(decoded.sections.last().hiddenItems.isEmpty())
        assertEquals("Quick", decoded.sections.last().customTitle)
        val preset = decoded.presets.single()
        assertEquals("Night", preset.name)
        assertEquals(260, preset.column.widthPx)
        UUID.fromString(preset.id)
        UUID.fromString(preset.sections.single().id)
    }

    @Test
    fun `a valid layout round trips without changing its server timestamp`() {
        val layout = WebUiLayoutDto(
            schemaVersion = 50,
            updatedAtMs = 1_700_000_000_123L,
            activePresetId = " preset-a ",
            column = WebUiColumnDto(380, "custom", true),
            sections = listOf(
                WebUiSectionPlacementDto(
                    id = "lens-placement",
                    kind = "builtin",
                    sectionId = "lens",
                    visible = false,
                    collapsed = true,
                    hiddenItems = listOf("lens.focus", "lens.focus"),
                    customTitle = "  Optics  ",
                ),
                WebUiSectionPlacementDto(
                    id = "custom-placement",
                    kind = "custom",
                    sectionId = "custom-custom-placement",
                    items = listOf("camera.iso", "camera.ev"),
                ),
            ),
            presets = listOf(
                WebUiLayoutPresetDto(
                    id = "preset-a",
                    name = "  Night Watch  ",
                    column = WebUiColumnDto(420, "wide"),
                    sections = listOf(
                        WebUiSectionPlacementDto(id = "channels", kind = "builtin", sectionId = "channels"),
                    ),
                ),
            ),
        )
        val expected = WebUiLayoutPolicy.sanitize(layout)
        val actual = webUiLayoutPref.roundTrip(layout)
        assertEquals(expected, actual)
        assertEquals(1_700_000_000_123L, actual.updatedAtMs)
        assertEquals("preset-a", actual.activePresetId)
        assertEquals("Night Watch", actual.presets.single().name)
    }

    @Test
    fun `encode persists minted identities so later decodes never re-roll them`() {
        val encoded = webUiLayoutPref.encodeTo(
            WebUiLayoutDto(
                sections = listOf(
                    WebUiSectionPlacementDto(id = "", kind = "builtin", sectionId = "lens"),
                    WebUiSectionPlacementDto(id = "same", kind = "custom", sectionId = "one"),
                    WebUiSectionPlacementDto(id = "same", kind = "custom", sectionId = "two"),
                ),
                presets = listOf(WebUiLayoutPresetDto(id = "", name = "Night")),
            ),
        )
        val persisted = requireNotNull(adapter.fromJson(encoded[key]!!))
        assertEquals(3, persisted.sections.size)
        assertEquals(3, persisted.sections.map { it.id }.toSet().size)
        UUID.fromString(persisted.sections[0].id)
        assertEquals("same", persisted.sections[1].id)
        UUID.fromString(persisted.sections[2].id)
        UUID.fromString(persisted.presets.single().id)

        // The UUIDs are in the JSON itself, not merely supplied by a transient
        // decode: repeated reads and a second encode keep the same identities.
        assertEquals(persisted, webUiLayoutPref.decode(encoded))
        assertEquals(persisted, webUiLayoutPref.decode(encoded))
        assertEquals(persisted.sections.map { it.id }, webUiLayoutPref.roundTrip(persisted).sections.map { it.id })
    }

    @Test
    fun `encode is the save-side sanitizer and preserves explicit empty lists`() {
        val raw = WebUiLayoutDto(
            schemaVersion = 99,
            updatedAtMs = -1,
            column = WebUiColumnDto(Int.MAX_VALUE, "invalid"),
            sections = List(60) { index ->
                WebUiSectionPlacementDto(
                    id = "s$index",
                    kind = "custom",
                    sectionId = "custom-$index",
                    items = List(30) { item -> "i$index-$item" },
                )
            },
            presets = List(20) { index -> WebUiLayoutPresetDto(id = "p$index", name = "P$index") },
        )
        val encoded = webUiLayoutPref.encodeTo(raw)
        val decoded = webUiLayoutPref.decode(encoded)
        assertEquals(StreamDefaults.MAX_SECTIONS, decoded.sections.size)
        assertEquals(StreamDefaults.MAX_CUSTOM_PRESETS, decoded.presets.size)
        assertEquals(560, decoded.column.widthPx)
        assertEquals(0L, decoded.updatedAtMs)
        assertTrue(decoded.sections.all { it.items.size == StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION })

        val empty = WebUiLayoutPolicy.defaultLayout().copy(sections = emptyList(), presets = emptyList())
        assertEquals(empty, webUiLayoutPref.roundTrip(empty))
    }
}

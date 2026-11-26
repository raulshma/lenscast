package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.model.WebUiColumnDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutPresetDto
import com.raulshma.lenscast.streaming.model.WebUiSectionPlacementDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure inspector-layout contract: exact defaults, bounds and idempotence. */
class WebUiLayoutPolicyTest {

    private val defaultSectionIds = listOf(
        "channels",
        "storage",
        "clients",
        "masking-quick",
        "system-info",
    )

    @Test
    fun `feature constants and default layout match the contract exactly`() {
        assertEquals(1, StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION)
        assertEquals(260, StreamDefaults.COLUMN_WIDTH_MIN_PX)
        assertEquals(320, StreamDefaults.COLUMN_WIDTH_DEFAULT_PX)
        assertEquals(560, StreamDefaults.COLUMN_WIDTH_MAX_PX)
        assertEquals(48, StreamDefaults.MAX_SECTIONS)
        assertEquals(12, StreamDefaults.MAX_CUSTOM_PRESETS)
        assertEquals(24, StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION)
        assertEquals(48, StreamDefaults.MAX_TITLE_LENGTH)
        assertEquals(40, StreamDefaults.MAX_PRESET_NAME_LENGTH)

        val layout = WebUiLayoutPolicy.defaultLayout()
        assertEquals(StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION, layout.schemaVersion)
        assertEquals(0L, layout.updatedAtMs)
        assertEquals("default", layout.activePresetId)
        assertEquals(WebUiColumnDto(320, "default", false), layout.column)
        assertEquals(defaultSectionIds, layout.sections.map { it.id })
        assertEquals(defaultSectionIds, layout.sections.map { it.sectionId })
        assertTrue(layout.presets.isEmpty())
        layout.sections.forEach { section ->
            assertEquals("builtin", section.kind)
            assertTrue(section.visible)
            assertFalse(section.collapsed)
            assertTrue(section.hiddenItems.isEmpty())
            assertNull(section.customTitle)
            assertTrue(section.items.isEmpty())
        }
        assertEquals(layout, WebUiLayoutPolicy.sanitize(layout))
        assertEquals(layout, WebUiLayoutDto())
    }

    @Test
    fun `missing DTO fields decode to usable constructor defaults`() {
        val adapter = AppJson.moshi.adapter(WebUiLayoutDto::class.java)
        val decoded = requireNotNull(adapter.fromJson("{}"))
        assertEquals(WebUiLayoutPolicy.defaultLayout(), WebUiLayoutPolicy.sanitize(decoded))

        // Preset snapshots have the same partial-decode behavior as the root.
        val withPreset = requireNotNull(
            adapter.fromJson("""{"presets":[{"id":"p","name":"Night"}]}"""),
        )
        assertEquals(defaultSectionIds, withPreset.presets.single().sections.map { it.sectionId })
        assertEquals(WebUiColumnDto(), withPreset.presets.single().column)
        assertTrue(WebUiSectionPlacementDto().visible)
        assertFalse(WebUiSectionPlacementDto().collapsed)
    }

    @Test
    fun `sanitize normalizes the envelope and clamps every column boundary`() {
        val raw = WebUiLayoutDto(
            schemaVersion = Int.MAX_VALUE,
            updatedAtMs = -1L,
            activePresetId = "  ${"p".repeat(100)}  ",
            column = WebUiColumnDto(Int.MIN_VALUE, "unknown", collapsed = true),
        )
        val sanitized = WebUiLayoutPolicy.sanitize(raw)
        assertEquals(StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION, sanitized.schemaVersion)
        assertEquals(0L, sanitized.updatedAtMs)
        assertEquals("p".repeat(64), sanitized.activePresetId)
        assertTrue(sanitized.column.collapsed)

        val cases = listOf(
            Triple(Int.MIN_VALUE, "bogus", 260 to "custom"),
            Triple(260, "bogus", 260 to "custom"),
            Triple(280, "bogus", 280 to "compact"),
            Triple(320, "bogus", 320 to "default"),
            Triple(420, "bogus", 420 to "wide"),
            Triple(400, "bogus", 400 to "custom"),
            Triple(560, "bogus", 560 to "custom"),
            Triple(Int.MAX_VALUE, "bogus", 560 to "custom"),
            Triple(999, "wide", 560 to "wide"),
        )
        cases.forEach { (width, preset, expected) ->
            val column = WebUiLayoutPolicy.sanitize(
                WebUiLayoutDto(column = WebUiColumnDto(width, preset)),
            ).column
            assertEquals("width $width/$preset", expected.first, column.widthPx)
            assertEquals("width $width/$preset", expected.second, column.widthPreset)
        }
        listOf("compact", "default", "wide", "custom").forEach { preset ->
            assertEquals(
                preset,
                WebUiLayoutPolicy.sanitize(
                    WebUiLayoutDto(column = WebUiColumnDto(333, preset)),
                ).column.widthPreset,
            )
        }
        assertEquals(
            "default",
            WebUiLayoutPolicy.sanitize(WebUiLayoutDto(activePresetId = "   ")).activePresetId,
        )
    }

    @Test
    fun `sections validate kind mint UUIDs and dedupe ids and builtins`() {
        val sections = listOf(
            WebUiSectionPlacementDto(id = " ", kind = "builtin", sectionId = " channels "),
            WebUiSectionPlacementDto(id = "dup", kind = "builtin", sectionId = "clients"),
            WebUiSectionPlacementDto(id = "dup", kind = "builtin", sectionId = "storage"),
            WebUiSectionPlacementDto(id = "again", kind = "builtin", sectionId = "channels"),
            WebUiSectionPlacementDto(id = "blank", kind = "builtin", sectionId = "   "),
            WebUiSectionPlacementDto(id = "future", kind = "builtin", sectionId = "future-panel"),
            WebUiSectionPlacementDto(id = "custom-a", kind = "custom", sectionId = "shared"),
            WebUiSectionPlacementDto(id = "custom-b", kind = "custom", sectionId = "shared"),
            WebUiSectionPlacementDto(id = "custom-blank", kind = "custom", sectionId = ""),
            WebUiSectionPlacementDto(id = "unknown", kind = "not-a-kind", sectionId = "future-unknown"),
        )
        val result = WebUiLayoutPolicy.sanitize(WebUiLayoutDto(sections = sections)).sections

        assertEquals(8, result.size)
        assertEquals(defaultSectionIds.size + 3, result.size) // five built-ins + three custom
        assertEquals(result.size, result.map { it.id }.toSet().size)
        assertTrue(result.all { it.id.isNotBlank() })
        UUID.fromString(result.first().id) // blank placement id
        UUID.fromString(result[2].id) // duplicate placement id
        assertEquals("channels", result.first().sectionId)
        assertEquals(
            listOf("channels", "clients", "storage", "future-panel", "future-unknown"),
            result.filter { it.kind == "builtin" }.map { it.sectionId },
        )
        assertEquals(listOf("custom-a", "custom-b", "custom-blank"), result.filter { it.kind == "custom" }.map { it.id })
        val derived = result.single { it.id == "custom-blank" }
        assertEquals("custom-${derived.id}", derived.sectionId)
        // An unknown kind validates to the safe built-in shape, while its
        // nonblank forward-compatible section id remains usable.
        assertEquals("builtin", result.single { it.id == "unknown" }.kind)
        assertEquals("future-unknown", result.single { it.id == "unknown" }.sectionId)
    }

    @Test
    fun `item lists and titles trim dedupe cap and stay kind-specific`() {
        val longItem = "  item-${"x".repeat(120)}  "
        val customItems = listOf("", "   ", " camera.iso ", "camera.iso", longItem) +
            List(40) { "bulk-$it" }
        val hiddenItems = List(40) { " hidden-$it " } + listOf("hidden-0", " ")
        val result = WebUiLayoutPolicy.sanitize(
            WebUiLayoutDto(
                sections = listOf(
                    WebUiSectionPlacementDto(
                        id = "custom",
                        kind = "custom",
                        sectionId = "custom-section",
                        hiddenItems = hiddenItems,
                        customTitle = "  ${"t".repeat(80)}  ",
                        items = customItems,
                    ),
                    WebUiSectionPlacementDto(
                        id = "builtin",
                        kind = "builtin",
                        sectionId = "lens",
                        hiddenItems = hiddenItems,
                        customTitle = "   ",
                        items = customItems,
                    ),
                ),
            ),
        ).sections

        val custom = result.single { it.kind == "custom" }
        assertEquals("t".repeat(StreamDefaults.MAX_TITLE_LENGTH), custom.customTitle)
        assertEquals(StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION, custom.items.size)
        assertEquals("camera.iso", custom.items.first())
        assertEquals(96, custom.items[1].length)
        assertTrue(custom.items.all { it.isNotBlank() })
        assertEquals(custom.items.size, custom.items.toSet().size)
        assertTrue(custom.hiddenItems.isEmpty())

        val builtin = result.single { it.kind == "builtin" }
        assertNull(builtin.customTitle)
        assertTrue(builtin.items.isEmpty())
        assertEquals(StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION, builtin.hiddenItems.size)
        assertEquals("hidden-0", builtin.hiddenItems.first())
        assertEquals(builtin.hiddenItems.size, builtin.hiddenItems.toSet().size)
    }

    @Test
    fun `sections presets and every nested snapshot are capped`() {
        val sections = List(60) { index ->
            WebUiSectionPlacementDto(
                id = "s$index",
                kind = "custom",
                sectionId = "shared-custom-id",
                items = List(40) { item -> "i$index-$item" },
            )
        }
        val presets = List(20) { index ->
            WebUiLayoutPresetDto(
                id = "p$index",
                name = "  Preset $index  ",
                column = WebUiColumnDto(999, "invalid"),
                sections = sections,
            )
        }
        val result = WebUiLayoutPolicy.sanitize(WebUiLayoutDto(sections = sections, presets = presets))

        assertEquals(StreamDefaults.MAX_SECTIONS, result.sections.size)
        assertEquals("s0", result.sections.first().id)
        assertEquals("s47", result.sections.last().id)
        assertEquals(StreamDefaults.MAX_CUSTOM_PRESETS, result.presets.size)
        assertEquals("Preset 0", result.presets.first().name)
        assertEquals("Preset 11", result.presets.last().name)
        result.presets.forEach { preset ->
            assertEquals(560, preset.column.widthPx)
            assertEquals("custom", preset.column.widthPreset)
            assertEquals(StreamDefaults.MAX_SECTIONS, preset.sections.size)
            assertTrue(preset.sections.all { it.items.size == StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION })
        }
    }

    @Test
    fun `custom presets require names and recursively sanitize unique identities`() {
        val longName = "  ${"n".repeat(80)}  "
        val result = WebUiLayoutPolicy.sanitize(
            WebUiLayoutDto(
                presets = listOf(
                    WebUiLayoutPresetDto(id = "drop", name = "   "),
                    WebUiLayoutPresetDto(
                        id = "same",
                        name = longName,
                        column = WebUiColumnDto(-10, "bogus", true),
                        sections = listOf(
                            WebUiSectionPlacementDto(id = "", kind = "builtin", sectionId = ""),
                            WebUiSectionPlacementDto(
                                id = "nested",
                                kind = "custom",
                                sectionId = "",
                                customTitle = " Nested ",
                                items = List(30) { "item-$it" },
                            ),
                        ),
                    ),
                    WebUiLayoutPresetDto(id = "same", name = " Other "),
                    WebUiLayoutPresetDto(id = "", name = "Third"),
                ),
            ),
        ).presets

        assertEquals(3, result.size)
        assertEquals("n".repeat(StreamDefaults.MAX_PRESET_NAME_LENGTH), result[0].name)
        assertEquals(260, result[0].column.widthPx)
        assertEquals("custom", result[0].column.widthPreset)
        assertTrue(result[0].column.collapsed)
        assertEquals(1, result[0].sections.size)
        assertEquals("nested", result[0].sections.single().id)
        assertEquals("Nested", result[0].sections.single().customTitle)
        assertEquals(StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION, result[0].sections.single().items.size)
        assertEquals("Other", result[1].name)
        assertEquals("Third", result[2].name)
        UUID.fromString(result[1].id) // duplicate preset id
        UUID.fromString(result[2].id) // blank preset id
        assertEquals(3, result.map { it.id }.toSet().size)
    }

    @Test
    fun `an explicit empty placement list stays empty and sanitizing is idempotent`() {
        val empty = WebUiLayoutPolicy.sanitize(WebUiLayoutDto(sections = emptyList()))
        assertTrue(empty.sections.isEmpty())

        val raw = WebUiLayoutDto(
            schemaVersion = 88,
            updatedAtMs = -9,
            activePresetId = " custom ",
            column = WebUiColumnDto(900, "invalid", true),
            sections = listOf(
                WebUiSectionPlacementDto(id = "", kind = "builtin", sectionId = "lens"),
                WebUiSectionPlacementDto(id = "", kind = "custom", sectionId = "", items = listOf("iso", "iso")),
            ),
            presets = listOf(
                WebUiLayoutPresetDto(id = "", name = " Night ", sections = rawSections()),
            ),
        )
        val once = WebUiLayoutPolicy.sanitize(raw)
        val twice = WebUiLayoutPolicy.sanitize(once)
        assertEquals(once, twice)
        assertEquals(once, WebUiLayoutPolicy.sanitize(raw)) // minted identities are reproducible
        assertEquals("", raw.sections.first().id) // the input graph was not mutated
        assertEquals(listOf("iso", "iso"), raw.sections.last().items)
    }

    private fun rawSections(): List<WebUiSectionPlacementDto> = listOf(
        WebUiSectionPlacementDto(id = "dup", kind = "builtin", sectionId = "channels"),
        WebUiSectionPlacementDto(id = "dup", kind = "builtin", sectionId = "clients"),
    )
}

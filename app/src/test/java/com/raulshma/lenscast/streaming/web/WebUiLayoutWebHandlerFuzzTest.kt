package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.parkedDispatcher
import com.raulshma.lenscast.streaming.model.WebUiColumnDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutPresetDto
import com.raulshma.lenscast.streaming.model.WebUiSectionPlacementDto
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deterministic hostile corpus for PUT /api/ui/layout. Every input either
 * rejects with [IllegalArgumentException] before a write or returns the same
 * bounded document a fresh GET reads. Only the share-in scope is parked; JSON
 * parsing, sanitization, persistence and replies all use production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WebUiLayoutWebHandlerFuzzTest.PlainApplication::class)
class WebUiLayoutWebHandlerFuzzTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: WebUiLayoutWebHandler
    private val adapter = AppJson.moshi.adapter(WebUiLayoutDto::class.java)
    private val serverNowMs = 1_700_000_000_123L

    @Before
    fun setUp() {
        runBlocking {
            store = SettingsDataStore(
                RuntimeEnvironment.getApplication(),
                CoroutineScope(SupervisorJob() + parkedDispatcher()),
            )
            handler = WebUiLayoutWebHandler(store, nowMs = { serverNowMs })
            handler.reset()
        }
    }

    private fun decode(json: String): WebUiLayoutDto = requireNotNull(adapter.fromJson(json))

    private fun assertRejected(body: String) {
        val error = assertThrows("payload should be rejected: $body", IllegalArgumentException::class.java) {
            runBlocking { handler.put(body) }
        }
        assertTrue(error.message?.contains("Invalid UI layout JSON") == true)
    }

    /** Returns null for a rejected body; successful bodies must be fully sanitized disk truth. */
    private suspend fun putHostile(body: String): WebUiLayoutDto? {
        val before = store.webUiLayoutFromDisk()
        val json = try {
            handler.put(body)
        } catch (expected: IllegalArgumentException) {
            assertEquals("rejected payload changed disk truth", before, store.webUiLayoutFromDisk())
            return null
        }
        return decode(json).also { layout ->
            assertSanitized(layout)
            assertEquals("PUT response differs from persisted truth", layout, store.webUiLayoutFromDisk())
        }
    }

    private fun assertColumn(column: WebUiColumnDto) {
        assertTrue(column.widthPx in StreamDefaults.COLUMN_WIDTH_MIN_PX..StreamDefaults.COLUMN_WIDTH_MAX_PX)
        assertTrue(column.widthPreset in setOf("compact", "default", "wide", "custom"))
    }

    private fun assertSections(sections: List<WebUiSectionPlacementDto>) {
        assertTrue(sections.size <= StreamDefaults.MAX_SECTIONS)
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        sections.forEach { section ->
            assertTrue(section.id.isNotBlank())
            assertTrue(section.id.length <= 96)
            assertTrue(section.kind == "builtin" || section.kind == "custom")
            assertTrue(section.sectionId.isNotBlank())
            assertTrue(section.sectionId.length <= 96)
            assertTrue(section.customTitle == null || section.customTitle!!.length <= StreamDefaults.MAX_TITLE_LENGTH)
            assertItems(section.hiddenItems)
            assertItems(section.items)
            when (section.kind) {
                "builtin" -> {
                    assertTrue(section.items.isEmpty())
                    assertTrue(section.sectionId == section.sectionId.trim())
                }
                "custom" -> assertTrue(section.hiddenItems.isEmpty())
            }
        }
        assertEquals(
            sections.count { it.kind == "builtin" },
            sections.filter { it.kind == "builtin" }.map { it.sectionId }.toSet().size,
        )
    }

    private fun assertItems(items: List<String>) {
        assertTrue(items.size <= StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION)
        assertEquals(items.size, items.toSet().size)
        items.forEach { item ->
            assertTrue(item.isNotBlank())
            assertTrue(item.length <= 96)
            assertEquals(item, item.trim())
        }
    }

    private fun assertSanitized(layout: WebUiLayoutDto) {
        assertEquals(StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION, layout.schemaVersion)
        assertEquals("client timestamp survived PUT", serverNowMs, layout.updatedAtMs)
        assertEquals("sanitization is not idempotent", layout, WebUiLayoutPolicy.sanitize(layout))
        assertTrue(layout.activePresetId.isNotBlank())
        assertTrue(layout.activePresetId.length <= 64)
        assertColumn(layout.column)
        assertSections(layout.sections)
        assertTrue(layout.presets.size <= StreamDefaults.MAX_CUSTOM_PRESETS)
        assertEquals(layout.presets.size, layout.presets.map { it.id }.toSet().size)
        layout.presets.forEach { preset ->
            assertTrue(preset.id.isNotBlank())
            assertTrue(preset.id.length <= 96)
            assertTrue(preset.name.isNotBlank())
            assertTrue(preset.name.length <= StreamDefaults.MAX_PRESET_NAME_LENGTH)
            assertColumn(preset.column)
            assertSections(preset.sections)
        }
    }

    @Test(timeout = 15_000)
    fun `a valid document is the positive control and GET equals PUT`() = runBlocking {
        val body = """{
            "schemaVersion":1,"updatedAtMs":0,"activePresetId":"default",
            "column":{"widthPx":320,"widthPreset":"default","collapsed":false},
            "sections":[
                {"id":"channels","kind":"builtin","sectionId":"channels","visible":true,
                 "collapsed":false,"hiddenItems":["channels.status"],"customTitle":null,"items":[]},
                {"id":"custom_rig","kind":"custom","sectionId":"custom-custom_rig","visible":true,
                 "collapsed":false,"hiddenItems":[],"customTitle":"Rig","items":["camera.iso","camera.ev"]}
            ],
            "presets":[{"id":"preset_night","name":"Night","column":{"widthPx":380,"widthPreset":"custom"},
                        "sections":[{"id":"clients","kind":"builtin","sectionId":"clients","collapsed":true}]}]
        }"""
        val response = putHostile(body)
        assertNotNull(response)
        assertEquals("camera.ev", response!!.sections.last().items.last())
        assertEquals(response, decode(handler.get()))
    }

    @Test(timeout = 15_000)
    fun `every truncation of a valid document rejects before changing disk`() = runBlocking {
        val saved = decode(handler.get())
        val valid = """{"schemaVersion":1,"column":{"widthPx":320,"widthPreset":"default"},"sections":[],"presets":[]}"""
        for (cut in valid.indices) {
            assertRejected(valid.substring(0, cut))
        }
        assertEquals("truncated PUTs changed disk truth", saved, decode(handler.get()))
        val accepted = decode(handler.put(valid))
        assertSanitized(accepted)
        assertEquals(accepted, decode(handler.get()))
    }

    @Test(timeout = 20_000)
    fun `wrong roots field types huge values duplicate keys and surrogates never leak`() = runBlocking {
        val huge = "x".repeat(1_000_000)
        val corpus = listOf(
            "null", "true", "false", "42", "-2147483649", "1e400", """"a string"""", "[]", "[{}]", "{}", "",
            """{"schemaVersion":null}""",
            """{"schemaVersion":"1"}""",
            """{"schemaVersion":1.5}""",
            """{"schemaVersion":2147483648}""",
            """{"updatedAtMs":"now"}""",
            """{"updatedAtMs":1e400}""",
            """{"activePresetId":[]}""",
            """{"column":null}""",
            """{"column":42}""",
            """{"column":{"widthPx":"320"}}""",
            """{"column":{"widthPx":2147483648}}""",
            """{"column":{"widthPx":1e400}}""",
            """{"column":{"widthPreset":null}}""",
            """{"column":{"collapsed":"false"}}""",
            """{"sections":null}""",
            """{"sections":{}}""",
            """{"sections":[null]}""",
            """{"sections":[[]]}""",
            """{"sections":[{"id":42}]}""",
            """{"sections":[{"kind":null}]}""",
            """{"sections":[{"visible":"yes"}]}""",
            """{"sections":[{"hiddenItems":"iso"}]}""",
            """{"sections":[{"hiddenItems":[null]}]}""",
            """{"sections":[{"items":[42]}]}""",
            """{"sections":[{"customTitle":42}]}""",
            """{"presets":null}""",
            """{"presets":{}}""",
            """{"presets":[null]}""",
            """{"presets":[{"id":"p","name":null}]}""",
            """{"presets":[{"id":"p","name":"Night","column":null}]}""",
            """{"presets":[{"id":"p","name":"Night","sections":[null]}]}""",
            """{"activePresetId":"first","activePresetId":"last"}""",
            """{"column":{"widthPx":1},"column":{"widthPx":400}}""",
            """{"sections":[{"id":"x","kind":"builtin","sectionId":"lens"}],"sections":null}""",
            """{"activePresetId":"${"a".repeat(1_000_000)}"}""",
            """{"sections":[{"id":"x","kind":"custom","customTitle":"${"t".repeat(1_000_000)}","items":["${"i".repeat(1_000_000)}"]}]}""",
            """{"presets":[{"id":"p","name":"${"n".repeat(1_000_000)}"}]}""",
            """{"activePresetId":"lone-${"\uD800"}-surrogate"}""",
            """{"sections":[{"id":"x","kind":"custom","customTitle":"${"\uD800"}"}]}""",
        )
        corpus.forEach { body -> putHostile(body) }
        assertSanitized(decode(handler.get()))
    }

    @Test(timeout = 20_000)
    fun `one hundred thousand levels of nesting reject without leaking StackOverflowError`() = runBlocking {
        val deepArrays = "[".repeat(100_000) + "]".repeat(100_000)
        val deepObjects = buildString {
            repeat(100_000) { append("""{"a":""") }
            append("1")
            repeat(100_000) { append("}") }
        }
        listOf(
            deepArrays,
            deepObjects,
            """{"unknown":$deepArrays}""",
            """{"unknown":$deepObjects}""",
            """{"sections":$deepArrays}""",
            """{"presets":$deepObjects}""",
        ).forEach { body -> assertRejected(body) }
    }

    @Test(timeout = 20_000)
    fun `oversized valid graphs are capped recursively before persistence`() = runBlocking {
        val sections = List(60) { index ->
            WebUiSectionPlacementDto(
                id = "s$index",
                kind = "custom",
                sectionId = "shared-custom-id",
                customTitle = "  ${"t".repeat(80)}  ",
                items = List(40) { item -> " i$index-$item " },
            )
        }
        val presets = List(20) { index ->
            WebUiLayoutPresetDto(
                id = "p$index",
                name = "  ${"n".repeat(80)}  ",
                column = WebUiColumnDto(Int.MAX_VALUE, "invalid"),
                sections = sections,
            )
        }
        val response = putHostile(adapter.toJson(WebUiLayoutDto(schemaVersion = 99, sections = sections, presets = presets)))
        assertNotNull(response)
        assertEquals(StreamDefaults.MAX_SECTIONS, response!!.sections.size)
        assertEquals(StreamDefaults.MAX_CUSTOM_PRESETS, response.presets.size)
        response.presets.forEach { preset ->
            assertEquals(StreamDefaults.MAX_SECTIONS, preset.sections.size)
            assertTrue(preset.sections.all { it.items.size == StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION })
        }
        assertEquals(response, decode(handler.get()))
    }

    @Test(timeout = 15_000)
    fun `seeded garbage either rejects cleanly or returns sanitized disk truth`() = runBlocking {
        val random = Random(0x571A90F7)
        val alphabet = """{}[]",:0123456789.eE+-truefalsn\/ """
        repeat(1_000) { trial ->
            val body = buildString {
                append(if (random.nextBoolean()) "[" else "{")
                repeat(random.nextInt(160)) { append(alphabet[random.nextInt(alphabet.length)]) }
                if (random.nextBoolean()) append(if (random.nextBoolean()) "}" else "]")
            }
            val response = putHostile(body)
            if (response != null) assertEquals("successful trial $trial", response, decode(handler.get()))
        }
        assertSanitized(decode(handler.get()))
    }

    @Test
    fun `UUID minting always yields parseable unique identities under duplicate pressure`() {
        val minted = List(1_000) { index ->
            WebUiLayoutPolicy.sanitize(
                WebUiLayoutDto(
                    sections = listOf(
                        WebUiSectionPlacementDto(id = "duplicate", kind = "custom", sectionId = "same"),
                        WebUiSectionPlacementDto(id = "", kind = "custom", sectionId = "same-$index"),
                    ),
                    presets = listOf(
                        WebUiLayoutPresetDto(id = "duplicate", name = "P$index"),
                        WebUiLayoutPresetDto(id = "", name = "Q$index"),
                    ),
                ),
            )
        }
        minted.forEach { layout ->
            assertSections(layout.sections)
            layout.sections.forEach { section ->
                if (section.id != "duplicate") UUID.fromString(section.id)
            }
            layout.presets.forEach { preset ->
                if (preset.id != "duplicate") UUID.fromString(preset.id)
            }
        }
    }
}

package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.parkedDispatcher
import com.raulshma.lenscast.streaming.HttpAuthFilter
import com.raulshma.lenscast.streaming.SessionRole
import com.raulshma.lenscast.streaming.WebAuthGate
import com.raulshma.lenscast.streaming.model.WebUiColumnDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutPresetDto
import com.raulshma.lenscast.streaming.model.WebUiSectionPlacementDto
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * GET/PUT/DELETE against the real Preferences DataStore. The share-in dispatcher
 * is parked, so the eagerly-shared StateFlow can never catch up: a reply that
 * contains the saved layout is necessarily a fresh disk read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WebUiLayoutWebHandlerTest.PlainApplication::class)
class WebUiLayoutWebHandlerTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: WebUiLayoutWebHandler
    private val adapter = AppJson.moshi.adapter(WebUiLayoutDto::class.java)
    private var serverNowMs = 1_700_000_000_123L

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

    @Test
    fun `get starts at the exact default and reset restores it`() = runBlocking {
        assertEquals(WebUiLayoutPolicy.defaultLayout(), decode(handler.get()))

        handler.put("""{"activePresetId":"night","column":{"widthPx":500},"sections":[]}""")
        val reset = decode(handler.reset())
        assertEquals(WebUiLayoutPolicy.defaultLayout(), reset)
        assertEquals(0L, reset.updatedAtMs)
        assertEquals(reset, decode(handler.get()))
        assertEquals(reset, store.webUiLayoutFromDisk())
    }

    @Test
    fun `put stamps server time sanitizes and answers from disk while the shared flow lags`() = runBlocking {
        val response = decode(
            handler.put(
                """{
                    "schemaVersion":99,
                    "updatedAtMs":1,
                    "activePresetId":"  night  ",
                    "column":{"widthPx":9999,"widthPreset":"wide","collapsed":true},
                    "sections":[
                        {"id":"","kind":"builtin","sectionId":" lens ","hiddenItems":["iso","iso"]},
                        {"id":"same","kind":"custom","sectionId":"","customTitle":"  Rig  ","items":["ev","ev"]},
                        {"id":"same","kind":"custom","sectionId":"","customTitle":"Other"}
                    ],
                    "presets":[{"id":"","name":" Night ","column":{"widthPx":1}}]
                }""",
            ),
        )
        assertEquals(serverNowMs, response.updatedAtMs)
        assertNotEquals(1L, response.updatedAtMs)
        assertEquals(StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION, response.schemaVersion)
        assertEquals("night", response.activePresetId)
        assertEquals(WebUiColumnDto(StreamDefaults.COLUMN_WIDTH_MAX_PX, "wide", true), response.column)
        assertEquals(3, response.sections.size)
        assertEquals(3, response.sections.map { it.id }.toSet().size)
        UUID.fromString(response.sections[0].id)
        assertEquals("lens", response.sections[0].sectionId)
        assertEquals(listOf("iso"), response.sections[0].hiddenItems)
        assertEquals("Rig", response.sections[1].customTitle)
        assertEquals(listOf("ev"), response.sections[1].items)
        UUID.fromString(response.sections[2].id)
        val preset = response.presets.single()
        assertEquals("Night", preset.name)
        assertEquals(StreamDefaults.COLUMN_WIDTH_MIN_PX, preset.column.widthPx)
        UUID.fromString(preset.id)

        // The parked collector proves this cannot be the shared StateFlow.
        assertEquals(WebUiLayoutPolicy.defaultLayout(), store.webUiLayout.value)
        assertEquals(response, store.webUiLayoutFromDisk())
        assertEquals(response, decode(handler.get()))
        assertEquals(response, decode(WebUiLayoutWebHandler(store).get()))
    }

    @Test
    fun `put replaces every client timestamp with the server clock`() = runBlocking {
        for (claimed in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
            serverNowMs += 1L
            val response = decode(handler.put("""{"updatedAtMs":$claimed,"sections":[]}"""))
            assertEquals("client timestamp $claimed survived", serverNowMs, response.updatedAtMs)
            assertEquals(response, store.webUiLayoutFromDisk())
        }
    }

    @Test
    fun `empty object receives default placements while explicit empty stays empty`() = runBlocking {
        val defaulted = decode(handler.put("{}"))
        assertEquals(
            WebUiLayoutDto.defaultSections(),
            defaulted.sections,
        )
        assertTrue(defaulted.updatedAtMs > 0L)

        val empty = decode(handler.put("""{"sections":[],"presets":[]}"""))
        assertTrue(empty.sections.isEmpty())
        assertTrue(empty.presets.isEmpty())
        assertEquals(empty, decode(handler.get()))
    }

    @Test
    fun `blank identities are persisted once and survive a client re-save`() = runBlocking {
        val body = """{
            "sections":[{"id":"","kind":"builtin","sectionId":"lens"}],
            "presets":[{"id":"","name":"Night","sections":[]}]
        }"""
        val first = decode(handler.put(body))
        val second = decode(handler.put(adapter.toJson(first)))

        assertEquals(first.sections.map { it.id }, second.sections.map { it.id })
        assertEquals(first.presets.map { it.id }, second.presets.map { it.id })
        assertEquals(first.sections.single().id, decode(handler.get()).sections.single().id)
        assertEquals(first.presets.single().id, decode(handler.get()).presets.single().id)
    }

    @Test
    fun `viewer GET is allowed while admin sessions own PUT and DELETE and tokens stay read-only`() = runBlocking {
        val apiToken = "layout-api-token"
        val gate = WebAuthGate().apply {
            setCredentials("admin", StreamAuthCrypto.hashPassword("admin-pw"))
            setViewerCredentials("door", StreamAuthCrypto.hashPassword("viewer-pw"))
            setApiTokenProvider {
                WebAuthGate.ApiTokenConfig(enabled = true, hash = StreamAuthCrypto.sha256Hex(apiToken))
            }
        }
        val filter = HttpAuthFilter(gate, port = 8080)
        val path = "/api/ui/layout"
        val saved = decode(handler.put("""{"activePresetId":"keep","sections":[]}"""))

        val viewerLogin = gate.login("1.2.3.4", "door", "viewer-pw")
        assertEquals(SessionRole.VIEWER, viewerLogin.role)
        val viewerHeaders = mapOf(
            "cookie" to "${WebAuthGate.COOKIE_NAME}=${viewerLogin.token}",
            "x-requested-with" to "XMLHttpRequest",
        )
        assertNull(filter.authorize("GET", path, viewerHeaders))
        assertNull(filter.authorize("HEAD", path, viewerHeaders))
        for (method in listOf("POST", "PUT", "DELETE")) {
            val denial = requireNotNull(filter.authorize(method, path, viewerHeaders))
            assertEquals("viewer $method $path", 403, denial.statusCode)
        }
        assertEquals("a denied viewer write reached the store", saved, store.webUiLayoutFromDisk())

        val adminLogin = gate.login("1.2.3.4", "admin", "admin-pw")
        assertEquals(SessionRole.ADMIN, adminLogin.role)
        val adminHeaders = mapOf(
            "cookie" to "${WebAuthGate.COOKIE_NAME}=${adminLogin.token}",
            "x-requested-with" to "XMLHttpRequest",
        )
        for (method in listOf("GET", "HEAD", "PUT", "DELETE")) {
            assertNull("admin $method $path", filter.authorize(method, path, adminHeaders))
        }

        val tokenHeaders = mapOf("authorization" to "Bearer $apiToken")
        assertNull(filter.authorize("GET", path, tokenHeaders))
        assertNull(filter.authorize("HEAD", "$path?source=automation", tokenHeaders))
        for (method in listOf("POST", "PUT", "DELETE")) {
            val denial = requireNotNull(filter.authorize(method, path, tokenHeaders))
            assertEquals("token $method $path", 401, denial.statusCode)
        }
        assertEquals("a denied token write reached the store", saved, store.webUiLayoutFromDisk())
    }

    @Test
    fun `malformed null and wrong-typed JSON throw IllegalArgumentException without overwriting`() = runBlocking {
        val saved = decode(
            handler.put("""{"activePresetId":"keep","sections":[{"id":"lens","sectionId":"lens"}]}"""),
        )
        val invalid = listOf(
            "",
            "not json",
            "{",
            "null",
            "[]",
            "42",
            """"a string"""",
            """{"schemaVersion":"one"}""",
            """{"schemaVersion":2147483648}""",
            """{"updatedAtMs":"now"}""",
            """{"column":null}""",
            """{"column":[]}""",
            """{"column":{"widthPx":"wide"}}""",
            """{"column":{"collapsed":"yes"}}""",
            """{"sections":null}""",
            """{"sections":{}}""",
            """{"sections":[null]}""",
            """{"sections":[42]}""",
            """{"sections":[{"visible":"yes"}]}""",
            """{"sections":[{"id":"lens","sectionId":"lens","hiddenItems":[null]}]}""",
            """{"sections":[{"id":"custom","kind":"custom","items":[null]}]}""",
            """{"presets":null}""",
            """{"presets":[null]}""",
            """{"presets":[{"id":"p","name":"Night","sections":[null]}]}""",
        )
        invalid.forEach { body ->
            val error = assertThrows("body should be rejected: $body", IllegalArgumentException::class.java) {
                runBlocking { handler.put(body) }
            }
            assertTrue(error.message?.contains("Invalid UI layout JSON") == true)
            assertEquals("rejected PUT changed disk truth", saved, decode(handler.get()))
        }
    }

    @Test
    fun `response seam serializes the supplied layout and does not read disk`() = runBlocking {
        val fixture = decode(
            handler.put("""{"activePresetId":"night","column":{"widthPx":380,"widthPreset":"custom"}}"""),
        )
        assertEquals(fixture, decode(WebUiLayoutWebHandler.response(fixture)))
        val supplied = WebUiLayoutPolicy.defaultLayout().copy(updatedAtMs = 123L)
        assertEquals(supplied, decode(WebUiLayoutWebHandler.response(supplied)))
        assertNotEquals(supplied, decode(handler.get()))
    }
}

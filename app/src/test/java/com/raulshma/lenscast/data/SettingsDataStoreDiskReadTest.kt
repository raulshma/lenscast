package com.raulshma.lenscast.data

import android.app.Application
import androidx.datastore.preferences.core.stringPreferencesKey
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.model.WebUiColumnDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutPresetDto
import com.raulshma.lenscast.streaming.model.WebUiSectionPlacementDto
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.rtspAuthSpecFromPrefs
import com.raulshma.lenscast.streaming.web.WebUiLayoutPolicy
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The disk-truth seam ([SettingsDataStore.disk]) that every `...FromDisk()` /
 * `...Now()` twin delegates to: it reads the stored value straight from the
 * DataStore file even while the shared StateFlows still hold their
 * descriptor defaults — the fresh-process race window every startup-critical
 * read exists for, held open deterministically by parking the share-in
 * scope's dispatcher (the eager collectors never run, so the flows can never
 * converge mid-test). Real DataStore, real file; only the share-in scope is
 * frozen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsDataStoreDiskReadTest.PlainApplication::class)
class SettingsDataStoreDiskReadTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore

    @Before
    fun setUp() {
        store = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            // Parked dispatcher (ParkedDispatcher.kt): the eagerly-shared
            // collectors never run, so the flows can never converge mid-test.
            CoroutineScope(SupervisorJob() + parkedDispatcher()),
        )
    }

    @Test
    fun `disk reads stored truth for every descriptor shape while flows still hold defaults`() = runBlocking {
        // Seed through the store's own savers — they edit the file directly
        // and never wait on the parked share-in collectors. Each saved value
        // must DISCRIMINATE from the descriptor default, or a converged flow
        // could pass for the disk read.
        store.saveWebStreamingEnabled(false) // bool (default-true key): truth false, default true
        store.saveRtspPort(70_000) // int with clamp: the saver coerces to the max
        store.saveRtmpUrl("  rtmp://edge/live  ") // string with normalize: trimmed on save
        store.saveRtspInputFormat(RtspInputFormat.NV12) // enum stored as its name

        // The flows are still at the descriptor defaults — the parked
        // dispatcher guarantees it, and the defaults are asserted to prove
        // the disk assertions below cannot be answering from them.
        assertEquals(true, store.webStreamingEnabled.value)
        assertEquals(StreamDefaults.RTSP_PORT, store.rtspPort.value)
        assertEquals("", store.rtmpUrl.value)
        assertEquals(RtspInputFormat.AUTO, store.rtspInputFormat.value)

        // The disk truth, straight through the descriptors.
        assertEquals(false, store.disk(webStreamingEnabledPref))
        assertEquals(StreamDefaults.RTSP_PORT_MAX, store.disk(rtspPortPref))
        assertEquals("rtmp://edge/live", store.disk(rtmpUrlPref))
        assertEquals(RtspInputFormat.NV12, store.disk(rtspInputFormatPref))
    }

    @Test
    fun `layout save sanitizes once and the disk twin survives a fresh store while its flow placeholders`() = runBlocking {
        val raw = WebUiLayoutDto(
            schemaVersion = 99,
            updatedAtMs = 1_700_000_000_123L,
            activePresetId = "  night  ",
            column = WebUiColumnDto(StreamDefaults.COLUMN_WIDTH_MAX_PX + 100, "unknown", true),
            sections = listOf(
                WebUiSectionPlacementDto(
                    id = "",
                    kind = "builtin",
                    sectionId = " lens ",
                    hiddenItems = listOf(" lens.iso ", "lens.iso"),
                ),
                WebUiSectionPlacementDto(
                    id = "custom",
                    kind = "custom",
                    customTitle = "  Rig  ",
                    items = listOf("camera.iso", "camera.iso"),
                ),
            ),
            presets = listOf(
                WebUiLayoutPresetDto(
                    id = "",
                    name = "  Night  ",
                    column = WebUiColumnDto(1, "bogus"),
                    sections = listOf(WebUiSectionPlacementDto(id = "", kind = "builtin", sectionId = "clients")),
                ),
            ),
        )
        store.saveWebUiLayout(raw)
        val persisted = store.webUiLayoutFromDisk()

        // The parked share-in still exposes the default-first placeholder;
        // both the disk twin and the bytes on disk carry the saved truth.
        assertEquals(WebUiLayoutPolicy.defaultLayout(), store.webUiLayout.value)
        assertEquals(StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION, persisted.schemaVersion)
        assertEquals(1_700_000_000_123L, persisted.updatedAtMs)
        assertEquals("night", persisted.activePresetId)
        assertEquals(WebUiColumnDto(StreamDefaults.COLUMN_WIDTH_MAX_PX, "custom", true), persisted.column)
        assertEquals(2, persisted.sections.size)
        assertEquals(2, persisted.sections.map { it.id }.toSet().size)
        assertEquals(listOf("lens", "custom-custom"), persisted.sections.map { it.sectionId })
        UUID.fromString(persisted.sections[0].id)
        assertEquals(listOf("lens.iso"), persisted.sections[0].hiddenItems)
        assertEquals(emptyList<String>(), persisted.sections[0].items)
        assertEquals("custom", persisted.sections[1].id)
        assertEquals("Rig", persisted.sections[1].customTitle)
        assertEquals(listOf("camera.iso"), persisted.sections[1].items)
        assertEquals(emptyList<String>(), persisted.sections[1].hiddenItems)
        val preset = persisted.presets.single()
        assertEquals("Night", preset.name)
        assertEquals(WebUiColumnDto(StreamDefaults.COLUMN_WIDTH_MIN_PX, "custom"), preset.column)
        assertEquals(listOf("clients"), preset.sections.map { it.sectionId })
        UUID.fromString(preset.id)
        UUID.fromString(preset.sections.single().id)
        assertEquals("save-side sanitization is not idempotent", persisted, WebUiLayoutPolicy.sanitize(persisted))
        assertEquals("", raw.sections.first().id) // the caller's graph was not mutated
        val layoutKey = stringPreferencesKey("web_ui_layout_json")
        assertEquals(serializeWebUiLayout(persisted), store.readFresh { it[layoutKey] })

        // A new store over the same DataStore file starts with the same
        // placeholder but its disk twin reads the persisted identities and
        // server timestamp, not a re-minted/default document.
        val reopened = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            CoroutineScope(SupervisorJob() + parkedDispatcher()),
        )
        assertEquals(WebUiLayoutPolicy.defaultLayout(), reopened.webUiLayout.value)
        assertEquals(persisted, reopened.webUiLayoutFromDisk())
    }

    @Test
    fun `corrupt layout JSON reads as the exact default and a later save heals the bytes`() = runBlocking {
        val layoutKey = stringPreferencesKey("web_ui_layout_json")
        // A test-only raw string descriptor plants corrupt bytes under the
        // production key. The production layout descriptor always sanitizes
        // its writes, so going through it could not exercise corrupt decode.
        val rawJsonPref = SettingPref(
            default = "",
            decode = { prefs -> prefs[layoutKey].orEmpty() },
            encode = { prefs, value -> prefs[layoutKey] = value },
        )
        assertEquals(WebUiLayoutPolicy.defaultLayout(), store.webUiLayoutFromDisk())

        val corrupt = listOf(
            "{not json",
            "null",
            "[]",
            """{"column":null}""",
            """{"sections":[null]}""",
            """{"presets":[{"id":"p","name":"Night","sections":[42]}]}""",
        )
        for (json in corrupt) {
            store.saveEntries(listOf(SettingEntry(rawJsonPref, json)))
            assertEquals("corrupt payload: $json", WebUiLayoutPolicy.defaultLayout(), store.webUiLayoutFromDisk())
            assertEquals(WebUiLayoutPolicy.defaultLayout(), store.webUiLayout.value)
        }

        val reopened = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            CoroutineScope(SupervisorJob() + parkedDispatcher()),
        )
        assertEquals(WebUiLayoutPolicy.defaultLayout(), reopened.webUiLayoutFromDisk())

        val healed = WebUiLayoutPolicy.defaultLayout().copy(
            updatedAtMs = 42L,
            activePresetId = "night",
            sections = listOf(WebUiSectionPlacementDto(id = "lens", kind = "builtin", sectionId = "lens")),
        )
        reopened.saveWebUiLayout(healed)
        assertEquals(healed, reopened.webUiLayoutFromDisk())
        assertEquals(serializeWebUiLayout(healed), reopened.readFresh { it[layoutKey] })
    }

    @Test
    fun `the rtsp auth spec assembles from one disk pass while its two flows hold defaults`() = runBlocking {
        // One atomic section save — exactly what the settings PUT lands:
        // credentials and the Basic-compat flag commit together.
        store.saveEntries(
            listOf(
                SettingEntry(
                    authSettingsPref,
                    StreamAuthSettings(enabled = true, username = "cam", passwordHash = "hash", rtspDigestHa1 = "ABC"),
                ),
                SettingEntry(rtspBasicAuthCompatPref, true),
            ),
        )

        // Both flows still at their descriptor defaults — the old `.value`
        // assembly answered null (auth "off"!) or mixed new/old pairs here.
        assertEquals(StreamAuthSettings(), store.authSettings.value)
        assertEquals(false, store.rtspBasicAuthCompat.value)

        // The disk pass the manager's provider runs: both inputs from one
        // snapshot, one coherent spec — new credentials WITH the new compat
        // flag, never a mixed pairing.
        val spec = store.readFresh(::rtspAuthSpecFromPrefs)
        assertEquals("cam", spec?.username)
        assertEquals("hash", spec?.passwordHash)
        assertEquals("abc", spec?.digestHa1)
        assertEquals(true, spec?.basicAuthCompat)
    }
}

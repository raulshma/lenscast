package com.raulshma.lenscast.streaming.web

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.Secret
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.SettingEntry
import com.raulshma.lenscast.data.apiTokenEnabledPref
import com.raulshma.lenscast.data.apiTokenHashPref
import com.raulshma.lenscast.data.overlaySettingsPref
import com.raulshma.lenscast.data.rtspEnabledPref
import com.raulshma.lenscast.data.rtspInputFormatPref
import com.raulshma.lenscast.data.rtspResolutionPref
import com.raulshma.lenscast.data.rtspVideoCodecPref
import com.raulshma.lenscast.data.streamingPortPref
import com.raulshma.lenscast.streaming.model.MaskingZoneDto
import com.raulshma.lenscast.streaming.model.MotionZoneDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog's completeness and convention gates (architecture review F3):
 * the streaming wire key set is DERIVED from the production DTO adapter and
 * asserted equal to the catalog's declared keys both directions — a DTO field
 * added without a catalog entry (the silent drift the old hand-written ladders
 * allowed, every param defaulted) fails here instead of answering the default
 * or never persisting. Every field then round-trips a non-default probe through
 * the catalog's own encode → descriptor-encode → decode seam (the exact loop
 * [com.raulshma.lenscast.data.SettingsDataStore.saveEntries] runs inside its one
 * transaction; the transaction wrapper itself is pinned by
 * [SettingsWebHandlerAtomicSaveTest]), and each convention keeps its pin:
 * secrets blank on the wire and keep-or-overwrite on disk, tolerant enums skip
 * unknown names, the apiToken gate fails closed on a blank hash, and the
 * overlay composite fans touched fields in without losing untouched siblings.
 * Pure JVM: the descriptors and the catalog need no Context or DataStore file.
 */
class StreamingFieldCatalogTest {

    private val probes = StreamingFieldProbes

    /** Apply entries exactly as saveEntries' transaction body does, on a copy of [base]. */
    private fun applyEntries(base: Preferences, entries: List<SettingEntry<*>>): MutablePreferences {
        val prefs = base.toMutablePreferences()
        for (entry in entries) entry.encode(prefs)
        return prefs
    }

    @Test
    fun `catalog wire keys equal the DTO adapter key set minus response-only facts`() {
        val dtoKeys = probes.wireTree(StreamingSettingsDto()).keys
        val catalogKeys = StreamingFieldCatalog.entries.flatMap { it.wireKeys }.toSet()
        assertEquals(dtoKeys - probes.responseOnlyKeys, catalogKeys)
        // No key declared twice — two entries owning one wire key would double-write.
        assertEquals(StreamingFieldCatalog.entries.sumOf { it.wireKeys.size }, catalogKeys.size)
    }

    @Test
    fun `every entry decodes exactly its declared wire keys`() {
        for (entry in StreamingFieldCatalog.entries) {
            assertEquals(entry.wireKeys, entry.decode(emptyPreferences()).keys)
        }
    }

    @Test
    fun `every field round-trips a non-default probe through encode and decode`() {
        val defaults = probes.defaultsTree()
        for (entry in StreamingFieldCatalog.entries) for (key in entry.wireKeys.sorted()) {
            val probe = probes.probeValue(key, defaults)
            val dto = probes.probeDto(key)
            val base = mutablePreferencesOf()
            // The enable probe needs a stored hash to pass the gate (its own pin below).
            if (key == "apiTokenEnabled") apiTokenHashPref.encode(base, "ab".repeat(32))

            val saved = applyEntries(base, StreamingFieldCatalog.encode(dto, setOf(key), base))
            val wire = probes.wireTree(StreamingFieldCatalog.decode(saved))
            val actual = wire[key]

            when (entry) {
                is StreamingFieldCatalog.SecretOverwrite -> {
                    assertEquals("secret wire must stay blank for $key", "", actual)
                    assertEquals("stored secret for $key", probe, entry.pref.decode(saved))
                }
                is StreamingFieldCatalog.HashStore -> {
                    assertEquals("token wire must stay blank", "", actual)
                    assertEquals("stored hash", StreamAuthCrypto.sha256Hex(probe as String), entry.hashPref.decode(saved))
                }
                else -> when (key) {
                    "soundClassificationAllowedClasses", "soundTriggerClasses" ->
                        assertEquals((probe as List<*>).toSet(), (actual as List<*>).toSet())
                    else -> assertTrue(
                        "round-trip mismatch for $key: expected $probe, got $actual",
                        probes.wireEquals(probes.expectedWire(key, probe), actual),
                    )
                }
            }
        }
    }

    @Test
    fun `secrets decode blank in the base tree and an empty update keeps the stored credential`() {
        val base = mutablePreferencesOf()
        for (entry in StreamingFieldCatalog.entries) when (entry) {
            is StreamingFieldCatalog.SecretOverwrite -> entry.pref.encode(base, "stored-${entry.wireKey}")
            is StreamingFieldCatalog.HashStore -> entry.hashPref.encode(base, StreamAuthCrypto.sha256Hex("stored-token"))
            else -> Unit
        }
        val wire = probes.wireTree(StreamingFieldCatalog.decode(base))
        for (key in probes.secretKeys) assertEquals("base tree must blank $key", "", wire[key])

        // The defaults DTO carries every secret empty: touching a secret key
        // with no presented value contributes no entry — the stored one stays.
        for (key in probes.secretKeys) {
            val entries = StreamingFieldCatalog.encode(StreamingSettingsDto(), setOf(key), base)
            assertTrue("empty update must not write $key", entries.isEmpty())
        }
    }

    @Test
    fun `tolerant enums skip an unknown or blank wire name`() {
        for (entry in StreamingFieldCatalog.entries) if (entry is StreamingFieldCatalog.EnumTolerant<*>) {
            for (name in listOf("BOGUS", "")) {
                val dto = probes.dtoAdapter.fromJsonValue(probes.defaultsTree() + (entry.wireKey to name))!!
                assertTrue(
                    "${entry.wireKey} must skip '$name'",
                    StreamingFieldCatalog.encode(dto, setOf(entry.wireKey), emptyPreferences()).isEmpty(),
                )
            }
        }
    }

    @Test
    fun `apiToken enable fails closed on a blank hash with no stored hash`() {
        val dto = StreamingSettingsDto(apiTokenEnabled = true)
        val noHash = applyEntries(emptyPreferences(), StreamingFieldCatalog.encode(dto, setOf("apiTokenEnabled"), emptyPreferences()))
        assertEquals(false, apiTokenEnabledPref.decode(noHash))

        // A presented token arms the flag even with no stored hash…
        val withToken = StreamingSettingsDto(apiTokenEnabled = true, apiToken = Secret("fresh-token"))
        val tokenApplied = applyEntries(emptyPreferences(), StreamingFieldCatalog.encode(withToken, setOf("apiTokenEnabled"), emptyPreferences()))
        assertEquals(true, apiTokenEnabledPref.decode(tokenApplied))

        // …and a stored hash keeps an enable with no plaintext armed.
        val storedHash = mutablePreferencesOf().also { apiTokenHashPref.encode(it, StreamAuthCrypto.sha256Hex("stored-token")) }
        val hashApplied = applyEntries(storedHash, StreamingFieldCatalog.encode(dto, setOf("apiTokenEnabled"), storedHash))
        assertEquals(true, apiTokenEnabledPref.decode(hashApplied))

        // Disabling always goes through, hash or not.
        val disable = StreamingSettingsDto(apiTokenEnabled = false)
        val disabled = applyEntries(storedHash, StreamingFieldCatalog.encode(disable, setOf("apiTokenEnabled"), storedHash))
        assertEquals(false, apiTokenEnabledPref.decode(disabled))
    }

    @Test
    fun `overlay fan-in keeps untouched siblings from the document and blank fallbacks from fresh`() {
        val fresh = mutablePreferencesOf()
        overlaySettingsPref.encode(
            fresh,
            OverlaySettings.DEFAULT.copy(
                timestampFormat = "HH:mm:ss",
                textColor = "#123456",
                backgroundColor = "#654321",
                fontSize = 40,
                brandingText = "StoredBrand",
            ),
        )
        val dto = StreamingSettingsDto(
            overlayEnabled = true,
            timestampFormat = "",
            overlayTextColor = "",
            overlayBackgroundColor = "",
        )
        val saved = applyEntries(fresh, StreamingFieldCatalog.encode(dto, setOf("overlayEnabled"), fresh))
        val overlay = overlaySettingsPref.decode(saved)
        assertEquals(true, overlay.enabled)
        // Blank-fallback fields read the FRESH snapshot, not the descriptor default.
        assertEquals("HH:mm:ss", overlay.timestampFormat)
        assertEquals("#123456", overlay.textColor)
        assertEquals("#654321", overlay.backgroundColor)
        // Untouched siblings ride the merged document (DTO defaults here), not fresh.
        assertEquals(OverlaySettings.DEFAULT.fontSize, overlay.fontSize)
        assertEquals(OverlaySettings.DEFAULT.brandingText, overlay.brandingText)
    }

    @Test
    fun `touched-only encoding leaves untouched keys verbatim`() {
        val base = mutablePreferencesOf()
        rtspEnabledPref.encode(base, true)
        streamingPortPref.encode(base, 8081)
        val dto = StreamingSettingsDto(port = 9090)
        val saved = applyEntries(base, StreamingFieldCatalog.encode(dto, setOf("port"), base))
        assertEquals(9090, streamingPortPref.decode(saved))
        assertEquals(true, rtspEnabledPref.decode(saved))
        // Exactly one stored key moved, and no new key appeared.
        assertEquals(base.asMap().keys, saved.asMap().keys)
        val changed = saved.asMap().filter { (key, value) -> base.asMap()[key] != value }
        assertEquals(1, changed.size)
    }

    @Test
    fun `empty touched set and unknown or response-only keys write nothing`() {
        assertTrue(StreamingFieldCatalog.encode(StreamingSettingsDto(), emptySet(), emptyPreferences()).isEmpty())
        val ignored = probes.responseOnlyKeys + "notAField"
        assertTrue(StreamingFieldCatalog.encode(StreamingSettingsDto(), ignored, emptyPreferences()).isEmpty())
    }

    @Test
    fun `stored enum constants render their exact wire spellings`() {
        val prefs = mutablePreferencesOf()
        rtspInputFormatPref.encode(prefs, RtspInputFormat.NV21)
        rtspResolutionPref.encode(prefs, RtspResolution.entries.first { it.wireName == "1080p43" })
        rtspVideoCodecPref.encode(prefs, RtspVideoCodec.entries.first { it.wireName == "h265" })
        val wire = probes.wireTree(StreamingFieldCatalog.decode(prefs))
        assertEquals("NV21", wire["rtspInputFormat"])
        assertEquals("1080p43", wire["rtspResolution"])
        assertEquals("h265", wire["rtspVideoCodec"])
    }
}

/**
 * The probe machinery the catalog gates share: one non-default value per
 * wire key, built off the DTO adapter's own defaults tree so a probe can
 * never drift from the wire shape. The zone-list adapters are the catalog's
 * own declarations (one home for both sides). Zone coordinates carry the
 * production Double→Float precision convention, so expectations are
 * float-normalized.
 */
internal object StreamingFieldProbes {

    val dtoAdapter = AppJson.moshi.adapter(StreamingSettingsDto::class.java)

    /** Response-only facts: the handler appends them; the catalog never owns them. */
    val responseOnlyKeys = setOf("apiTokenConfigured", "mlModelState", "mlModelProgress", "mlModelError")

    val secretKeys = setOf("rtmpUrl", "whipToken", "srtUrl", "backupWebdavPassword", "telegramBotToken", "apiToken")

    fun defaultsTree(): Map<String, Any?> = wireTree(StreamingSettingsDto())

    /** The DTO's JSON-ready wire tree (secrets already blanked by the adapter). */
    fun wireTree(dto: StreamingSettingsDto): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return dtoAdapter.toJsonValue(dto)!! as Map<String, Any?>
    }

    fun probeValue(key: String, defaults: Map<String, Any?>): Any? = when (key) {
        "rtspInputFormat" -> "NV21"
        "rtspResolution" -> "1080p"
        "rtspVideoCodec" -> "h265"
        "backupTarget" -> "telegram"
        "overlayPosition" -> "BOTTOM_RIGHT"
        "timestampFormat" -> "HH:mm:ss"
        "overlayTextColor" -> "#FF0000"
        "overlayBackgroundColor" -> "#00FF00"
        "soundClassificationAllowedClasses" -> listOf("Speech")
        "soundTriggerClasses" -> listOf("Glass")
        "motionZones" -> StreamingFieldCatalog.motionZoneListAdapter.toJsonValue(
            listOf(MotionZoneDto(id = "z1", label = "probe", enabled = true, x = 0.1, y = 0.2, width = 0.3, height = 0.4)),
        )
        "maskingZones" -> StreamingFieldCatalog.maskingZoneListAdapter.toJsonValue(
            listOf(
                MaskingZoneDto(
                    id = "m1", label = "probe", enabled = true, type = MaskingType.PIXELATE.name,
                    x = 0.1, y = 0.2, width = 0.3, height = 0.4, pixelateSize = 8, blurRadius = 5.0,
                ),
            ),
        )
        in secretKeys -> "probe-secret-$key"
        else -> when (val default = defaults[key]) {
            is Boolean -> !default
            // The arm-window end and the day mask default AT their maxima.
            is Number -> if (key == "motionArmEndMinute" || key == "motionArmDaysMask") default.toInt() - 1 else default.toInt() + 1
            is String -> "probe-$key"
            else -> error("no probe convention for $key (${default?.javaClass})")
        }
    }

    fun probeDto(key: String): StreamingSettingsDto =
        requireNotNull(dtoAdapter.fromJsonValue(defaultsTree() + (key to probeValue(key, defaultsTree()))))

    /** The probe as the wire must answer it: zone coordinates ride Float precision. */
    fun expectedWire(key: String, probe: Any?): Any? = when (key) {
        "motionZones", "maskingZones" -> floatNormalized(probe)
        else -> probe
    }

    private fun floatNormalized(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.mapValues { floatNormalized(it.value) }
        is List<*> -> value.map { floatNormalized(it) }
        is Double -> value.toFloat().toDouble()
        else -> value
    }

    /** Wire-tree equality: Moshi renders numbers as Integer/Long/Double interchangeably. */
    fun wireEquals(expected: Any?, actual: Any?): Boolean = when {
        expected is Map<*, *> && actual is Map<*, *> ->
            expected.keys == actual.keys && expected.all { (k, v) -> wireEquals(v, actual[k]) }
        expected is List<*> && actual is List<*> ->
            expected.size == actual.size && expected.zip(actual).all { (e, a) -> wireEquals(e, a) }
        expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
        else -> expected == actual
    }
}
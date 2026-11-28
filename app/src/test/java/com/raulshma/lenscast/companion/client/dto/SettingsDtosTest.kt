package com.raulshma.lenscast.companion.client.dto

import com.raulshma.lenscast.core.AppJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion settings DTOs' wire lockstep: the checked-in
 * `settings-update-fixture.json` (a PUT body written from the web console's
 * types.ts shape — the full streaming section a GET-merge-PUT save sends)
 * decodes, round-trips through encode unchanged, and serializes with the
 * `camera` key omitted (the "leave the camera section untouched" PUT
 * contract); the checked-in `web/contract/settings.json` document — the same
 * file the web dashboard's contract test pins — decodes into [AllSettings]
 * and round-trips losslessly. A wire-shape change that is not mirrored into
 * these DTOs fails here first.
 */
class SettingsDtosTest {

    private val moshi = AppJson.moshi

    /**
     * Unit tests run from the app module dir; fixtures live in this test
     * package's fixtures/ dir and in web/contract. (The fixture was re-homed
     * here from the retired System tab's test dir — the DTO contract it pins
     * is the client package's.)
     */
    private fun fixtureText(name: String): String =
        listOf(
            File("src/test/java/com/raulshma/lenscast/companion/client/dto/fixtures/$name"),
            File("../app/src/test/java/com/raulshma/lenscast/companion/client/dto/fixtures/$name"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("Fixture $name not found")

    private fun contractText(name: String): String =
        listOf(File("../web/contract/$name"), File("web/contract/$name"))
            .firstOrNull { it.exists() }?.readText()
            ?: error("Contract fixture $name not found; tried ../web/contract and web/contract")

    private inline fun <reified T> decode(text: String): T =
        moshi.adapter(T::class.java).fromJson(text) ?: error("Fixture decoded to null")

    @Test
    fun `the settings update fixture decodes with camera absent and the detection fields intact`() {
        val update = decode<SettingsUpdate>(fixtureText("settings-update-fixture.json"))

        // The PUT body carries the streaming section only: camera absent
        // means "leave the camera section untouched" server-side.
        assertNull(update.camera)
        val streaming = requireNotNull(update.streaming)

        // The detection/motion group — the fields the editor owns and the
        // save merge carries onto a freshly fetched document.
        assertTrue(streaming.motionDetectionEnabled)
        assertEquals(72, streaming.motionSensitivityPercent)
        assertTrue(streaming.motionRecordingEnabled)
        assertEquals(20, streaming.motionPostRollSeconds)
        assertEquals(30, streaming.motionCooldownSeconds)
        assertTrue(streaming.motionArmScheduleEnabled)
        assertEquals(1320, streaming.motionArmStartMinute)
        assertEquals(420, streaming.motionArmEndMinute)
        assertEquals(125, streaming.motionArmDaysMask)

        // The RTSP group: the legacy-compat knob rides NON-default (true —
        // the round trip below re-encodes it verbatim), the additive field
        // the older-device decode test covers as its absent-field case.
        assertTrue(streaming.rtspBasicAuthCompat)

        assertTrue(streaming.soundDetectionEnabled)
        assertEquals(45, streaming.soundThresholdPercent)
        assertTrue(streaming.soundAdaptiveNoiseFloor)
        assertTrue(streaming.soundRecordingEnabled)
        assertEquals(15, streaming.soundCooldownSeconds)
        assertEquals(listOf("Speech", "Dog"), streaming.soundClassificationAllowedClasses)

        assertTrue(streaming.mlDetectionEnabled)
        assertEquals(55, streaming.mlMinScorePercent)
        assertTrue(streaming.mlIncludePerson)
        assertFalse(streaming.mlIncludePets)
        assertTrue(streaming.mlIncludeVehicles)
        assertEquals("ready", streaming.mlModelState)
        assertEquals(-1.0, streaming.mlModelProgress, 0.0)

        assertTrue(streaming.tamperDetectionEnabled)

        // The capture/storage group.
        assertEquals(30, streaming.captureRetentionDays)
        assertEquals(14, streaming.eventRetentionDays)
        assertEquals(8_192, streaming.storageQuotaMb)

        // Zones decode whole (they are echoed back untouched on PUT).
        val maskingZone = streaming.maskingZones.single()
        assertEquals("Doorway", maskingZone.label)
        assertEquals("PIXELATE", maskingZone.type)
        assertEquals(0.3, maskingZone.width, 0.0)
        assertEquals(12.5, maskingZone.blurRadius, 0.0)
        val motionZone = streaming.motionZones.single()
        assertEquals("Driveway", motionZone.label)
        assertEquals(0.5, motionZone.height, 0.0)

        // Secrets ride blank (the write-only contract): the PUT never
        // carries a stored credential because GET never returned one.
        assertEquals("", streaming.rtmpUrl)
        assertEquals("", streaming.whipToken)
        assertEquals("", streaming.srtUrl)
        assertEquals("", streaming.backupWebdavPassword)
        assertEquals("", streaming.telegramBotToken)
        assertEquals("", streaming.mqttPassword)
        assertEquals("", streaming.apiToken)
        assertTrue(streaming.apiTokenEnabled)
        assertTrue(streaming.apiTokenConfigured)
    }

    @Test
    fun `the settings update fixture round-trips through encode unchanged`() {
        val adapter = moshi.adapter(SettingsUpdate::class.java)
        val original = adapter.fromJson(fixtureText("settings-update-fixture.json"))
            ?: error("fixture decode failed")
        val decodedAgain = adapter.fromJson(adapter.toJson(original))
            ?: error("re-decode failed")
        assertEquals(original, decodedAgain)
    }

    @Test
    fun `an encoded settings update omits the null camera section from the wire`() {
        // Moshi's serializeNulls-off default is load-bearing for the PUT:
        // a null section must be ABSENT from the body, not `null` on the
        // wire (the server decodes absent = untouched).
        val streaming = StreamingSettings(motionDetectionEnabled = true)
        val json = moshi.adapter(SettingsUpdate::class.java)
            .toJson(SettingsUpdate(camera = null, streaming = streaming))
        assertFalse(json.contains("\"camera\""))
        assertTrue(json.contains("\"streaming\""))
        assertTrue(json.contains("\"motionDetectionEnabled\":true"))
    }

    @Test
    fun `the web contract settings document decodes into AllSettings`() {
        val document = decode<AllSettings>(contractText("settings.json"))

        // Camera spot checks (decode-only for the companion — it never PUTs
        // the camera section).
        assertEquals(800, document.camera.iso)
        assertEquals(10_000_000L, document.camera.exposureTime)
        assertEquals("FHD_1080P", document.camera.resolution)
        assertEquals(24, document.camera.frameRate)
        assertEquals(0, document.camera.outputRotation)

        // Streaming spot checks across the editor's groups and beyond.
        val streaming = document.streaming
        assertEquals(8080, streaming.port)
        assertEquals("720p", streaming.rtspResolution)
        assertEquals("h264", streaming.rtspVideoCodec)
        assertFalse(streaming.motionDetectionEnabled)
        assertEquals(50, streaming.motionSensitivityPercent)
        assertEquals(10, streaming.motionPostRollSeconds)
        assertEquals(127, streaming.motionArmDaysMask)
        assertEquals(30, streaming.soundThresholdPercent)
        assertEquals(60, streaming.mlMinScorePercent)
        assertTrue(streaming.mlIncludePerson)
        assertFalse(streaming.tamperDetectionEnabled)
        assertEquals(0, streaming.captureRetentionDays)
        assertEquals(2_048, streaming.storageQuotaMb)
        assertEquals("not_downloaded", streaming.mlModelState)
        assertEquals("TOP_LEFT", streaming.overlayPosition)
        assertFalse(streaming.showIlluminance)
        assertEquals("homeassistant", streaming.mqttDiscoveryPrefix)
        assertEquals(1439, streaming.motionArmEndMinute)
    }

    @Test
    fun `the settings document round-trips losslessly through encode`() {
        val adapter = moshi.adapter(AllSettings::class.java)
        val original = adapter.fromJson(contractText("settings.json"))
            ?: error("contract decode failed")
        val decodedAgain = adapter.fromJson(adapter.toJson(original))
            ?: error("re-decode failed")
        assertEquals(original, decodedAgain)
    }

    @Test
    fun `an older device omitting the additive streaming fields decodes cleanly`() {
        // Wire tolerance: every StreamingSettings field carries a default, so
        // a device whose settings document predates a given field still
        // decodes — the missing fields fold to their defaults.
        val older = """
            {
              "camera": {"exposureCompensation": 0, "zoomRatio": 1.0, "frameRate": 30},
              "streaming": {"port": 8080, "webStreamingEnabled": true, "jpegQuality": 70,
                            "showPreview": true, "streamAudioEnabled": true,
                            "streamAudioBitrateKbps": 128, "streamAudioChannels": 1,
                            "streamAudioEchoCancellation": true, "recordingAudioEnabled": true,
                            "rtspEnabled": false, "rtspPort": 8554}
            }
        """.trimIndent()
        val document = decode<AllSettings>(older)
        assertEquals(30, document.camera.frameRate)
        assertNull(document.camera.iso)
        val streaming = document.streaming
        assertEquals(8080, streaming.port)
        assertEquals("AUTO", streaming.rtspInputFormat)
        assertEquals("720p", streaming.rtspResolution)
        // The compat knob is this test's own absent-field case: omitted →
        // the default false (the checked-in fixture carries the true side).
        assertEquals(false, streaming.rtspBasicAuthCompat)
        assertEquals(127, streaming.motionArmDaysMask)
        assertEquals(false, streaming.mlDetectionEnabled)
        assertEquals("not_downloaded", streaming.mlModelState)
        assertEquals(-1.0, streaming.mlModelProgress, 0.0)
        assertEquals(false, streaming.ecoIdleFpsEnabled)
        assertEquals(2_048, streaming.storageQuotaMb)
        assertTrue(streaming.maskingZones.isEmpty())
        assertTrue(streaming.motionZones.isEmpty())
    }
}

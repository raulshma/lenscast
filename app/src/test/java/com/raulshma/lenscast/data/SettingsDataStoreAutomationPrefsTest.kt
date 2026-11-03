package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.emptyPreferences
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The automation persistence codecs behind the Settings Store's descriptors:
 * the rule list's Moshi round trip (ids minted, defaults folded, sanitize
 * applied on both sides, malformed payloads degrading to an empty list), the
 * master toggle's boolean convention, and the torch-strength optional key's
 * persist/clear behavior — all pure, no Context or DataStore required, same
 * shape as [MaskingZoneCodecTest].
 */
class SettingsDataStoreAutomationPrefsTest {

    private fun <T> SettingPref<T>.encodeTo(value: T) =
        emptyPreferences().toMutablePreferences().also { encode(it, value) }

    private fun <T> SettingPref<T>.roundTrip(value: T) = decode(encodeTo(value))

    // ── automationEnabled ──

    @Test
    fun `automation master toggle decodes absence as off and round trips`() {
        // default-false convention: a fresh install must not execute rules
        // the user has never seen.
        assertFalse(automationEnabledPref.decode(emptyPreferences()))
        assertTrue(automationEnabledPref.roundTrip(true))
        assertFalse(automationEnabledPref.roundTrip(false))
    }

    // ── automationRules round trip ──

    @Test
    fun `absent rules key decodes to an empty list`() {
        assertEquals(emptyList<AutomationRule>(), automationRulesPref.decode(emptyPreferences()))
    }

    @Test
    fun `rules round trip through the persisted json`() {
        val rules = listOf(
            AutomationRule(
                id = "rule-1",
                name = "Night lock",
                enabled = true,
                minuteOfDay = 1_320,
                daysMask = 0b0011111,
                actions = listOf(
                    AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "8")),
                    AutomationActionSpec("capture_photo"),
                ),
            ),
            AutomationRule(
                id = "rule-2",
                name = "Morning stream",
                enabled = false,
                minuteOfDay = 420,
                daysMask = StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT,
                actions = listOf(AutomationActionSpec("stream", mapOf("enabled" to "false"))),
            ),
        )
        assertEquals(rules, automationRulesPref.roundTrip(rules))
    }

    @Test
    fun `decode mints fresh ids for rules without one`() {
        val decoded = parseAutomationRules(
            serializeAutomationRules(listOf(AutomationRule(name = "A"), AutomationRule(name = "B"))),
        )
        assertEquals(2, decoded.size)
        // Every id is a real UUID, and the two mints never collide — rules
        // sharing an id would journal each other's firings away.
        decoded.forEach { rule -> UUID.fromString(rule.id) }
        assertNotEquals(decoded[0].id, decoded[1].id)
        // Once minted, the ids ride the round trip unchanged.
        assertEquals(decoded, parseAutomationRules(serializeAutomationRules(decoded)))
    }

    @Test
    fun `decode folds absent fields to the rule defaults`() {
        val rule = parseAutomationRules("""[{"name":"Only"}]""").single()
        assertEquals("Only", rule.name)
        assertEquals(true, rule.enabled)
        assertEquals(AutomationRule.TRIGGER_TIME_OF_DAY, rule.triggerType)
        assertEquals(0, rule.minuteOfDay)
        assertEquals(StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT, rule.daysMask)
        assertEquals(emptyList<AutomationActionSpec>(), rule.actions)
        UUID.fromString(rule.id)
    }

    @Test
    fun `malformed payloads fall back to an empty list`() {
        assertEquals(emptyList<AutomationRule>(), parseAutomationRules(null))
        assertEquals(emptyList<AutomationRule>(), parseAutomationRules(""))
        assertEquals(emptyList<AutomationRule>(), parseAutomationRules("not json at all"))
        assertEquals(emptyList<AutomationRule>(), parseAutomationRules("""[{"id":}"""))
        assertEquals(emptyList<AutomationRule>(), parseAutomationRules("""{"id":"not-an-array"}"""))
        // Non-object elements broke the legacy codecs into the same catch.
        assertEquals(emptyList<AutomationRule>(), parseAutomationRules("[42]"))
    }

    @Test
    fun `decode sanitizes unknown actions clamps fields and caps the list`() {
        val json = """[{"id":"r1","minuteOfDay":2000,"daysMask":0,"actions":[""" +
            """{"type":"bogus"},{"type":"torch","params":{"enabled":"true","strength":"99"}}]}]"""
        val rule = parseAutomationRules(json).single()
        assertEquals(1_439, rule.minuteOfDay)
        assertEquals(StreamDefaults.MOTION_ARM_DAYS_MASK_MIN, rule.daysMask)
        assertEquals(listOf("torch"), rule.actions.map { it.type })
        assertEquals("8", rule.actions.single().params["strength"])

        // The cap applies on decode, so a hostile payload stays bounded.
        val capped = parseAutomationRules(
            serializeAutomationRules(List(20) { index -> AutomationRule(id = "r$index") }),
        )
        assertEquals(StreamDefaults.AUTOMATION_MAX_RULES, capped.size)
        assertEquals("r0", capped.first().id)
    }

    @Test
    fun `encode persists the sanitized list, never the raw one`() {
        // The save-side clamp owner: an unknown action must not survive a save.
        val encoded = automationRulesPref.encodeTo(
            listOf(AutomationRule(id = "r1", actions = listOf(AutomationActionSpec("bogus")))),
        )
        val decoded = automationRulesPref.decode(encoded)
        assertEquals(listOf(AutomationRule(id = "r1")), decoded)
    }

    // ── torchStrength ──

    @Test
    fun `torch strength persists and clears through the camera codec`() {
        val withStrength = cameraSettingsPref.roundTrip(CameraSettings(torchEnabled = true, torchStrength = 5))
        assertEquals(5, withStrength.torchStrength)
        // Absent (null) decodes back to null: the device default.
        val cleared = cameraSettingsPref.roundTrip(CameraSettings(torchEnabled = true))
        assertNull(cleared.torchStrength)
    }

    @Test
    fun `a cleared torch strength removes the persisted key`() {
        assertTrue(
            cameraSettingsPref.encodeTo(CameraSettings(torchStrength = 5))
                .asMap().keys.any { it.name == "torch_strength" },
        )
        // null must remove, not write a stale key that a later decode would
        // read back as an override.
        assertFalse(
            cameraSettingsPref.encodeTo(CameraSettings())
                .asMap().keys.any { it.name == "torch_strength" },
        )
    }

    @Test
    fun `camera settings round trip with the strength field keeps the rest intact`() {
        val settings = CameraSettings(
            exposureCompensation = -2,
            iso = 800,
            exposureTime = 20_000_000L,
            zoomRatio = 2.0f,
            frameRate = 30,
            torchEnabled = true,
            torchStrength = 7,
        )
        assertEquals(settings, cameraSettingsPref.roundTrip(settings))
    }
}

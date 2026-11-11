package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.automation.schedule.AutomationTriggerKind
import com.raulshma.lenscast.automation.schedule.ConditionMode
import com.raulshma.lenscast.automation.schedule.MissedRunPolicy
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `encode persists an id for every rule — a null id never lands on disk`() {
        // The save-side identity owner: a rule persisted without an id
        // re-mints on every decode, orphaning its fire-journal entries and
        // re-firing the rule whenever its trigger is still due. Whatever the
        // writer sent, an id lands in the persisted JSON itself — one id
        // field per rule, not something only the decode mint supplies.
        val encoded = automationRulesPref.encodeTo(
            listOf(AutomationRule(name = "Minted"), AutomationRule(id = "kept", name = "Kept")),
        )
        val json = encoded[stringPreferencesKey("automation_rules")]
        assertNotNull(json)
        assertEquals(2, """"id":""".toRegex().findAll(json!!).count())
        // The explicit id survives verbatim; the minted one is a real UUID.
        assertTrue(json.contains(""""id":"kept""""))
        UUID.fromString(""""id":"([^"]+)"""".toRegex().find(json)!!.groupValues[1])
        // And decoding what landed on disk keeps both identities.
        val decoded = automationRulesPref.decode(encoded)
        assertEquals("kept", decoded[1].id)
        assertEquals(decoded[0].id, automationRulesPref.decode(encoded)[0].id)
    }

    @Test
    fun `decode folds absent fields to the rule defaults`() {
        val rule = parseAutomationRules("""[{"name":"Only"}]""").single()
        assertEquals("Only", rule.name)
        assertEquals(true, rule.enabled)
        assertEquals(AutomationTriggerKind.TIME_OF_DAY, rule.triggerType)
        assertEquals(0, rule.minuteOfDay)
        assertEquals(StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT, rule.daysMask)
        assertEquals(emptyMap<String, String>(), rule.triggerParams)
        assertEquals(emptyList<AutomationConditionSpec>(), rule.conditions)
        assertEquals(ConditionMode.ALL, rule.conditionMode)
        assertEquals(StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS, rule.cooldownSeconds)
        // The missed-run fields fold too: pre-missed-run payloads decode
        // as the historical catch-up behavior.
        assertEquals(MissedRunPolicy.CATCH_UP, rule.missedRunPolicy)
        assertEquals(StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES, rule.missedWindowMinutes)
        assertEquals(emptyList<AutomationActionSpec>(), rule.actions)
        UUID.fromString(rule.id)
    }

    @Test
    fun `missed-run policy round trips through the persisted json`() {
        val rules = listOf(
            AutomationRule(
                id = "rule-5",
                name = "Sunrise or nothing",
                minuteOfDay = 360,
                missedRunPolicy = MissedRunPolicy.SKIP,
                actions = listOf(AutomationActionSpec("stream", mapOf("enabled" to "true"))),
            ),
            AutomationRule(
                id = "rule-6",
                name = "Morning within the hour",
                minuteOfDay = 420,
                missedRunPolicy = MissedRunPolicy.WINDOW,
                missedWindowMinutes = 60,
                actions = listOf(AutomationActionSpec("torch", mapOf("enabled" to "true"))),
            ),
        )
        assertEquals(rules, automationRulesPref.roundTrip(rules))
    }

    @Test
    fun `unknown vocabulary ids decode to the sanitize fallbacks and re-serialize canonically`() {
        // The vocabulary fields' wire tolerance, pinned end to end: before
        // the typed conversion an unknown triggerType / conditionMode /
        // missedRunPolicy survived decode as a string and folded at
        // sanitizeRule; the typed decode performs the SAME folds (same
        // fallbacks, via the enums' tolerant parse) and the wire adapter
        // writes only the canonical ids back, so an unknown spelling never
        // persists or rides a GET response.
        val rules = parseAutomationRules(
            """[{"id":"r-unknown","name":"Foreign","enabled":true,""" +
                """"triggerType":"teleport_event","minuteOfDay":420,"daysMask":127,""" +
                """"conditionMode":"some","missedRunPolicy":"fire_anyway","missedWindowMinutes":45,""" +
                """"actions":[{"type":"torch","params":{"enabled":"true"}}]}]""",
        )
        val rule = rules.single()
        assertEquals(AutomationTriggerKind.TIME_OF_DAY, rule.triggerType)
        assertEquals(ConditionMode.ALL, rule.conditionMode)
        assertEquals(MissedRunPolicy.CATCH_UP, rule.missedRunPolicy)

        // The persisted JSON carries the canonical ids only: the unknown
        // spellings are gone from what lands on disk.
        val json = serializeAutomationRules(rules)
        assertTrue(json.contains(""""triggerType":"time_of_day""""))
        assertTrue(json.contains(""""conditionMode":"all""""))
        assertTrue(json.contains(""""missedRunPolicy":"catch_up""""))
        assertFalse(json.contains("teleport_event"))
        assertFalse(json.contains("fire_anyway"))
        assertFalse(json.contains(""":""some""""))
        // The fold is stable: decoding the canonical re-serialization yields
        // the same rules again.
        assertEquals(rules, parseAutomationRules(json))
    }

    @Test
    fun `canonical vocabulary ids round-trip byte-identically through the codec`() {
        // The wire/persistence compatibility guarantee of the typed
        // conversion: every id the old String fields wrote serializes back
        // as the exact same bytes — decode → serialize reproduces the
        // persisted JSON verbatim (same keys, same order, same id strings).
        val json =
            """[{"id":"r-bytes","name":"Night","enabled":true,"triggerType":"time_of_day",""" +
                """"minuteOfDay":1320,"daysMask":31,"triggerParams":{},"conditions":[],""" +
                """"conditionMode":"any","cooldownSeconds":120,"missedRunPolicy":"window",""" +
                """"missedWindowMinutes":45,"actions":[{"type":"torch","params":{"enabled":"true","strength":"8"}}]}]"""
        assertEquals(json, serializeAutomationRules(parseAutomationRules(json)))
    }

    @Test
    fun `if-then rules round trip through the persisted json`() {
        val rules = listOf(
            AutomationRule(
                id = "rule-3",
                name = "Night motion",
                triggerType = AutomationTriggerKind.MOTION_DETECTED,
                triggerParams = emptyMap(),
                conditions = listOf(
                    AutomationConditionSpec(
                        AutomationConditionCatalog.TIME_WINDOW,
                        mapOf("start_minute" to "1320", "end_minute" to "360", "days_mask" to "127"),
                    ),
                ),
                conditionMode = ConditionMode.ALL,
                cooldownSeconds = 120,
                actions = listOf(AutomationActionSpec("siren", mapOf("enabled" to "true", "duration_seconds" to "10"))),
            ),
            AutomationRule(
                id = "rule-4",
                name = "Low battery",
                triggerType = AutomationTriggerKind.BATTERY_LEVEL,
                triggerParams = mapOf("level" to "20", "when_below" to "true"),
                conditions = listOf(
                    AutomationConditionSpec(AutomationConditionCatalog.STREAMING, mapOf("active" to "true")),
                ),
                conditionMode = ConditionMode.ANY,
                cooldownSeconds = 600,
                actions = listOf(AutomationActionSpec("capture_photo")),
            ),
        )
        assertEquals(rules, automationRulesPref.roundTrip(rules))
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

    @Test
    fun `a client-supplied lastFiredAt never persists`() {
        // The fire stamp is a GET-response decoration (the web handler's
        // copy(lastFiredAt=…)), never configuration: a client echoing a
        // response rule back in a PUT body (or a hand-crafted persisted
        // payload carrying the key) must not smuggle a claimed last-fired
        // instant into storage.
        val decoded = parseAutomationRules(
            """[{"id":"r1","name":"Smuggled","lastFiredAt":1234567890123,""" +
                """"actions":[{"type":"torch","params":{"enabled":"true"}}]}]""",
        )
        assertNull(decoded.single().lastFiredAt)

        // The encode side strips too (sanitize runs there): the persisted
        // JSON carries no lastFiredAt key even when the writer set one.
        val json = parseAutomationRules(
            serializeAutomationRules(
                listOf(
                    AutomationRule(
                        id = "r2",
                        name = "Stamped",
                        lastFiredAt = 9876543210987L,
                        actions = listOf(AutomationActionSpec("torch", mapOf("enabled" to "true"))),
                    ),
                ),
            ),
        ).single()
        assertNull(json.lastFiredAt)
        val persisted = automationRulesPref.encodeTo(
            listOf(AutomationRule(id = "r3", lastFiredAt = 1L, actions = listOf(AutomationActionSpec("stream", mapOf("enabled" to "true"))))),
        )
        assertFalse(
            "the persisted JSON carries a lastFiredAt key",
            persisted[stringPreferencesKey("automation_rules")]!!.contains("lastFiredAt"),
        )
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

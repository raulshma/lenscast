package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger catalog's additive-only contract: the exact wire id strings
 * and param schemas persist forever, so a rename here would silently strand
 * every persisted rule — this test makes it fail a build instead. The
 * sanitizeParams ladder shares the action catalog's semantics, pinned the
 * same way [AutomationActionCatalogTest] pins its twin.
 */
class AutomationTriggerCatalogTest {

    @Test
    fun `catalog pins the wire ids`() {
        assertEquals(
            listOf(
                "time_of_day",
                "motion_detected",
                "sound_detected",
                "tamper_detected",
                "charging_changed",
                "battery_level",
                "streaming_changed",
                "viewers_changed",
                "app_started",
            ),
            AutomationTriggerCatalog.ACTION_TYPES.map { it.id },
        )
    }

    @Test
    fun `time_of_day carries no trigger params`() {
        // Its real parameters are the rule's own minuteOfDay/daysMask fields
        // (persistence compatibility with the original time-only rules).
        val type = AutomationTriggerCatalog.typeFor(AutomationTriggerCatalog.TIME_OF_DAY)!!
        assertTrue(type.params.isEmpty())
    }

    @Test
    fun `battery_level pins its threshold schema`() {
        val level = AutomationTriggerCatalog.typeFor(AutomationTriggerCatalog.BATTERY_LEVEL)!!
            .params.single { it.name == "level" }
        assertEquals(ParamKind.INT, level.kind)
        assertEquals(1.0, level.min!!, 0.0)
        assertEquals(99.0, level.max!!, 0.0)
        assertFalse(level.optional)
        assertEquals("20", level.defaultValue)

        val whenBelow = AutomationTriggerCatalog.typeFor(AutomationTriggerCatalog.BATTERY_LEVEL)!!
            .params.single { it.name == "when_below" }
        assertEquals(ParamKind.BOOL, whenBelow.kind)
        assertEquals("true", whenBelow.defaultValue)
    }

    @Test
    fun `unknown id reads null`() {
        assertNull(AutomationTriggerCatalog.typeFor("totally-unknown"))
    }

    @Test
    fun `the trigger vocabulary enum ids agree with the catalog ids`() {
        // AutomationRule.triggerType is the typed form of these ids (the
        // enum's wire adapter writes `kind.id`): the two vocabularies must
        // agree one for one, or the persisted JSON would carry a string no
        // catalog lookup — and no picker — recognizes.
        assertEquals(
            AutomationTriggerCatalog.ACTION_TYPES.map { it.id },
            AutomationTriggerKind.entries.map { it.id },
        )
    }

    @Test
    fun `sanitizeParams canonicalizes values and folds absent required params to defaults`() {
        val sanitized = AutomationTriggerCatalog.sanitizeParams(
            AutomationTriggerCatalog.BATTERY_LEVEL,
            mapOf("level" to " 55.0 ", "when_below" to "FALSE"),
        )
        assertEquals(mapOf("level" to "55", "when_below" to "false"), sanitized)

        val defaults = AutomationTriggerCatalog.sanitizeParams(
            AutomationTriggerCatalog.BATTERY_LEVEL,
            emptyMap(),
        )
        assertEquals(mapOf("level" to "20", "when_below" to "true"), defaults)
    }

    @Test
    fun `sanitizeParams clamps into bounds`() {
        val clamped = AutomationTriggerCatalog.sanitizeParams(
            AutomationTriggerCatalog.BATTERY_LEVEL,
            mapOf("level" to "500"),
        )
        assertEquals("99", clamped!!["level"])
    }

    @Test
    fun `sanitizeParams rejects unknown triggers and unknown keys`() {
        assertNull(AutomationTriggerCatalog.sanitizeParams("bogus", emptyMap()))
        assertNull(
            AutomationTriggerCatalog.sanitizeParams(
                AutomationTriggerCatalog.BATTERY_LEVEL,
                mapOf("threshold_pct" to "5"),
            ),
        )
    }

    @Test
    fun `time_of_day has an empty param schema`() {
        assertEquals(
            emptyMap<String, String>(),
            AutomationTriggerCatalog.sanitizeParams(AutomationTriggerCatalog.TIME_OF_DAY, emptyMap()),
        )
        // With no known keys, any param key is an unknown-key violation —
        // the same schema verdict as any other trigger.
        assertNull(AutomationTriggerCatalog.sanitizeParams(AutomationTriggerCatalog.TIME_OF_DAY, mapOf("bogus" to "x")))
    }
}

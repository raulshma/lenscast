package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The condition catalog's additive-only contract (same discipline as the
 * action/trigger catalogs) plus the sanitizeCondition ladder's verdicts.
 */
class AutomationConditionCatalogTest {

    @Test
    fun `catalog pins the wire ids`() {
        assertEquals(
            listOf(
                "time_window",
                "charging",
                "battery_level",
                "power_save",
                "wifi_connected",
                "streaming",
                "viewers",
                "torch",
                "recording",
                "thermal",
                "motion_detection",
            ),
            AutomationConditionCatalog.CONDITIONS.map { it.id },
        )
    }

    @Test
    fun `time_window pins the wrapping-window schema`() {
        val type = AutomationConditionCatalog.typeFor(AutomationConditionCatalog.TIME_WINDOW)!!
        val names = type.params.map { it.name }
        assertEquals(listOf("start_minute", "end_minute", "days_mask"), names)
        assertTrue(type.params.all { !it.optional })
        // The default window is the whole day: 0..1439, every day.
        assertEquals("0", type.params[0].defaultValue)
        assertEquals("1439", type.params[1].defaultValue)
        assertEquals("127", type.params[2].defaultValue)
    }

    @Test
    fun `range conditions pin optional unbounded bounds`() {
        val battery = AutomationConditionCatalog.typeFor(AutomationConditionCatalog.BATTERY_LEVEL)!!
        assertTrue(battery.params.all { it.optional && it.defaultValue.isEmpty() })
        assertEquals(0.0, battery.params[0].min!!, 0.0)
        assertEquals(100.0, battery.params[1].max!!, 0.0)

        val viewers = AutomationConditionCatalog.typeFor(AutomationConditionCatalog.VIEWERS)!!
        assertEquals(listOf("min_count", "max_count"), viewers.params.map { it.name })
    }

    @Test
    fun `unknown id reads null`() {
        assertNull(AutomationConditionCatalog.typeFor("totally-unknown"))
    }

    @Test
    fun `sanitizeCondition canonicalizes and folds required defaults`() {
        val sanitized = AutomationConditionCatalog.sanitizeCondition(
            AutomationConditionSpec(
                AutomationConditionCatalog.CHARGING,
                mapOf("charging" to " FALSE "),
            ),
        )
        assertEquals(mapOf("charging" to "false"), sanitized!!.params)

        val defaulted = AutomationConditionCatalog.sanitizeCondition(
            AutomationConditionSpec(AutomationConditionCatalog.WIFI_CONNECTED, emptyMap()),
        )
        assertEquals(mapOf("connected" to "true"), defaulted!!.params)
    }

    @Test
    fun `sanitizeCondition drops unknown conditions and unknown keys`() {
        assertNull(AutomationConditionCatalog.sanitizeCondition(AutomationConditionSpec("bogus")))
        assertNull(
            AutomationConditionCatalog.sanitizeCondition(
                AutomationConditionSpec(
                    AutomationConditionCatalog.CHARGING,
                    mapOf("level" to "5"),
                ),
            ),
        )
    }

    @Test
    fun `sanitizeCondition keeps optional params absent when absent`() {
        val sanitized = AutomationConditionCatalog.sanitizeCondition(
            AutomationConditionSpec(
                AutomationConditionCatalog.BATTERY_LEVEL,
                mapOf("min_level" to "30"),
            ),
        )
        assertEquals("30", sanitized!!.params["min_level"])
        assertFalse(sanitized.params.containsKey("max_level"))
    }
}

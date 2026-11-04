package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The trigger and condition catalogs' sanitize ladders under hostile input —
 * the same seeded-corpus style as [AutomationActionCatalogFuzzTest], run over
 * both of the newer param-bearing catalogs via one shared invariant.
 *
 * ── The contract ──
 * sanitizeParams/sanitizeCondition never throw on any input. They answer null
 * ONLY for an unknown id or an unknown param name; otherwise the output's id
 * is in its catalog, every param name is catalog-known, every required param
 * is present, every value is inside its bounds and canonical, and
 * re-sanitizing the output is a fixed point.
 */
class AutomationTriggerConditionFuzzTest {

    // ── the corpus ──

    private val rawValues = listOf(
        "", " ", "0", "-0", "1", "-1", "20", "99", "100", "1439", "1440",
        "500", "99999999999999999999", "1e400", "-1e400", "NaN", "Infinity",
        "2.5", ".5", "+3", "0x10", "1,5", "--5", "12abc", "abc", "yes",
        "true", "false", "TRUE", "False", "  24  ", "1.0", "9".repeat(40),
        "\u0000", "\u00e9",
    )

    private val junkIds = listOf(
        "", " ", "motion ", " motion", "Motion", "MOTION", "motionx", "soundz",
        "time_window\n", "Time_Window", "charging\r", "battery-level",
        "null", ",null", "torch\u0000", "stream/drop",
    )

    // ── positive controls ──

    @Test(timeout = 10_000)
    fun `a fully catalog-known spec always sanitizes`() {
        assertNotNull(
            AutomationTriggerCatalog.sanitizeParams(AutomationTriggerCatalog.BATTERY_LEVEL, emptyMap()),
        )
        assertNotNull(
            AutomationTriggerCatalog.sanitizeParams(
                AutomationTriggerCatalog.BATTERY_LEVEL,
                mapOf("level" to "garbage"),
            ),
        )
        assertNotNull(
            AutomationConditionCatalog.sanitizeCondition(
                AutomationConditionSpec(AutomationConditionCatalog.BATTERY_LEVEL, mapOf("min_level" to "NaN")),
            ),
        )
    }

    @Test(timeout = 10_000)
    fun `an unknown id or param name is the only null branch`() {
        assertNull(AutomationTriggerCatalog.sanitizeParams("laser", emptyMap()))
        assertNull(
            AutomationTriggerCatalog.sanitizeParams(
                AutomationTriggerCatalog.BATTERY_LEVEL,
                mapOf("level" to "20", "bogus" to "1"),
            ),
        )
        assertNull(AutomationConditionCatalog.sanitizeCondition(AutomationConditionSpec("laser")))
        assertNull(
            AutomationConditionCatalog.sanitizeCondition(
                AutomationConditionSpec(
                    AutomationConditionCatalog.CHARGING,
                    mapOf("charging" to "true", "bogus" to "1"),
                ),
            ),
        )
    }

    // ── the seeded fuzz ──

    @Test(timeout = 10_000)
    fun `random trigger params never throw and sanitize to a null or a valid canonical map`() {
        val random = Random(0x70166E5)
        val validIds = AutomationTriggerCatalog.ACTION_TYPES.map { it.id }
        repeat(3_000) {
            val id =
                if (random.nextInt(4) == 0) junkIds[random.nextInt(junkIds.size)]
                else validIds[random.nextInt(validIds.size)]
            val raw = randomParams(random, id, AutomationTriggerCatalog.typeFor(id)?.params)
            val sanitized = AutomationTriggerCatalog.sanitizeParams(id, raw)
            assertParamsValidOrNull(id, raw, sanitized, AutomationTriggerCatalog.typeFor(id)?.params)
        }
    }

    @Test(timeout = 10_000)
    fun `random conditions never throw and sanitize to a null or a valid canonical spec`() {
        val random = Random(0xC00D17)
        val validIds = AutomationConditionCatalog.CONDITIONS.map { it.id }
        repeat(3_000) {
            val id =
                if (random.nextInt(4) == 0) junkIds[random.nextInt(junkIds.size)]
                else validIds[random.nextInt(validIds.size)]
            val raw = randomParams(random, id, AutomationConditionCatalog.typeFor(id)?.params)
            val rawSpec = AutomationConditionSpec(id, raw)
            val sanitized = AutomationConditionCatalog.sanitizeCondition(rawSpec)
            if (sanitized == null) {
                val type = AutomationConditionCatalog.typeFor(rawSpec.type)
                val known = type?.params?.map { it.name } ?: emptyList()
                assertTrue(
                    "unexpected null for id='${rawSpec.type}' params=${rawSpec.params.keys}",
                    type == null || rawSpec.params.keys.any { it !in known },
                )
                return@repeat
            }
            assertSpecValid(rawSpec, sanitized, AutomationConditionCatalog.typeFor(sanitized.type)!!)
        }
    }

    @Test(timeout = 10_000)
    fun `shotgun unknown keys are always the null branch`() {
        val shotgun = (0 until 200).associate { "bogus$it" to "1" }
        AutomationTriggerCatalog.ACTION_TYPES.forEach { type ->
            assertNull(AutomationTriggerCatalog.sanitizeParams(type.id, shotgun))
        }
        AutomationConditionCatalog.CONDITIONS.forEach { type ->
            assertNull(AutomationConditionCatalog.sanitizeCondition(AutomationConditionSpec(type.id, shotgun)))
        }
    }

    // ── the invariants ──

    private fun randomParams(
        random: Random,
        id: String,
        params: List<AutomationParamSpec>?,
    ): Map<String, String> {
        val raw = LinkedHashMap<String, String>()
        params?.forEach { param ->
            if (random.nextBoolean()) {
                raw[param.name] = rawValues[random.nextInt(rawValues.size)]
            }
        }
        if (random.nextInt(4) == 0) {
            raw["bogus${random.nextInt(1000)}"] = rawValues[random.nextInt(rawValues.size)]
        }
        return raw
    }

    private fun assertParamsValidOrNull(
        id: String,
        raw: Map<String, String>,
        sanitized: Map<String, String>?,
        specs: List<AutomationParamSpec>?,
    ) {
        if (sanitized == null) {
            val known = specs?.map { it.name } ?: emptyList()
            assertTrue(
                "unexpected null for id='$id' params=${raw.keys}",
                specs == null || raw.keys.any { it !in known },
            )
            return
        }
        assertNotNull("output id must be in the catalog", specs)
        specs!!
        val known = specs.associateBy { it.name }
        specs.filter { !it.optional }.forEach { required ->
            assertTrue(
                "required param ${required.name} missing from output of $id",
                sanitized.containsKey(required.name),
            )
        }
        sanitized.forEach { (name, value) ->
            val param = known[name]
            assertNotNull("unknown param $name leaked into output of $id", param)
            param!!
            assertCanonical(param, value)
        }
        // Fixed point: re-sanitizing changes nothing.
        assertEquals(sanitized, AutomationTriggerCatalog.sanitizeParams(id, sanitized))
    }

    private fun assertSpecValid(
        raw: AutomationConditionSpec,
        sanitized: AutomationConditionSpec,
        type: AutomationConditionType,
    ) {
        val known = type.params.associateBy { it.name }
        type.params.filter { !it.optional }.forEach { required ->
            assertTrue(
                "required param ${required.name} missing from output of ${type.id}",
                sanitized.params.containsKey(required.name),
            )
        }
        sanitized.params.forEach { (name, value) ->
            val param = known[name]
            assertNotNull("unknown param $name leaked into output of ${type.id}", param)
            param!!
            assertCanonical(param, value)
        }
        assertEquals(sanitized, AutomationConditionCatalog.sanitizeCondition(sanitized))
    }

    private fun assertCanonical(param: AutomationParamSpec, value: String) {
        when (param.kind) {
            ParamKind.BOOL ->
                assertTrue("non-canonical BOOL '$value'", value == "true" || value == "false")
            ParamKind.INT, ParamKind.FLOAT -> {
                val parsed = value.toDoubleOrNull()
                assertNotNull("non-numeric output '$value' for ${param.name}", parsed)
                parsed!!
                val min = param.min
                val max = param.max
                if (min != null) assertTrue("value $value below min for ${param.name}", parsed >= min)
                if (max != null) assertTrue("value $value above max for ${param.name}", parsed <= max)
                if (parsed == kotlin.math.floor(parsed) && kotlin.math.abs(parsed) < 1e15) {
                    assertFalse("non-canonical integral rendering '$value'", value.contains('.'))
                }
            }
        }
    }
}

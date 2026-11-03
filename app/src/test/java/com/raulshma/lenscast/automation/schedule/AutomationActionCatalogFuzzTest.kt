package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The action catalog's sanitize ladder under hostile input: a deterministic
 * corpus fuzzer over [AutomationActionCatalog.sanitizeAction] (the same
 * seeded-corpus style as [com.raulshma.lenscast.streaming.web.SettingsImportParserFuzzTest]).
 *
 * ── The contract ──
 * sanitizeAction never throws on any input. It answers null ONLY for an
 * unknown type id or an unknown param name (a fully catalog-known action
 * always sanitizes, never silently drops); otherwise the output's type is in
 * the catalog, every param name is catalog-known, every required param is
 * present, every value is inside its param's bounds and canonical ("true"/
 * "false" for BOOL, plain decimal for INT/FLOAT — no "1.0" renderings for
 * integral values), and re-sanitizing the output is a fixed point.
 *
 * ── The corpus ──
 * valid ids and junk ids, valid in-bounds values, out-of-bounds values, huge
 * numbers, non-numeric garbage, BOOL garbage, NaN/Infinity strings,
 * fractional tails on INT params, whitespace, empty strings, unknown param
 * names, and empty/oversized param maps — seeded and repeated.
 */
class AutomationActionCatalogFuzzTest {

    // ── the corpus ──

    private val rawValues = listOf(
        "", " ", "  ", "0", "-0", "1", "-1", "800", "3200", "4000", "10001",
        "99999999999999999999", "1e400", "-1e400", "1e-400", "NaN", "Infinity",
        "-Infinity", "2.5", "0.5", ".5", "5.", "+3", "-7", "0x10", "1,5", "--5",
        "12abc", "abc", "yes", "true", "false", "TRUE", "False", "tRuE",
        "24", "  24  ", "120", "86399", "86400", "86401", "1.0", "1.0000000001",
        "9".repeat(40), "-" + "9".repeat(40), "\u0000", "\u00e9",
    )

    private val junkIds = listOf(
        "", " ", "torch ", " torch", "Torch", "TORCH", "torchx", "laser",
        "camera_exposure\n", "Camera_Exposure", "recording\r", "capture-photo",
        "null", ",null", "torch\u0000", "stream/drop",
    )

    // ── positive controls ──

    @Test(timeout = 10_000)
    fun `a fully catalog-known action always sanitizes`() {
        // Controls for the null branch: nothing below may drop.
        assertNotNull(AutomationActionCatalog.sanitizeAction(AutomationActionSpec("torch")))
        assertNotNull(
            AutomationActionCatalog.sanitizeAction(
                AutomationActionSpec("torch", mapOf("enabled" to "garbage")),
            ),
        )
        assertNotNull(
            AutomationActionCatalog.sanitizeAction(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "NaN")),
            ),
        )
    }

    @Test(timeout = 10_000)
    fun `an unknown id or param name is the only null branch`() {
        assertNull(AutomationActionCatalog.sanitizeAction(AutomationActionSpec("laser")))
        assertNull(
            AutomationActionCatalog.sanitizeAction(
                AutomationActionSpec("torch", mapOf("enabled" to "true", "bogus" to "1")),
            ),
        )
    }

    // ── the seeded fuzz ──

    @Test(timeout = 10_000)
    fun `random specs never throw and sanitize to a null or a valid canonical action`() {
        val random = Random(0x0ACA7A5)
        val validIds = AutomationActionCatalog.ACTION_TYPES.map { it.id }
        repeat(3_000) {
            val id =
                if (random.nextInt(4) == 0) junkIds[random.nextInt(junkIds.size)]
                else validIds[random.nextInt(validIds.size)]
            val spec = AutomationActionSpec(type = id, params = randomParams(random, id))
            // Must not throw for any input.
            val sanitized = AutomationActionCatalog.sanitizeAction(spec)
            assertValidOrNull(spec, sanitized)
        }
    }

    @Test(timeout = 10_000)
    fun `empty and oversized param maps stay inside the contract`() {
        val validIds = AutomationActionCatalog.ACTION_TYPES.map { it.id }
        // Empty maps and a shotgun of unknown keys never throw and never
        // hallucinate a result for a junk id.
        validIds.forEach { id ->
            assertValidOrNull(AutomationActionSpec(id, emptyMap()), AutomationActionCatalog.sanitizeAction(AutomationActionSpec(id)))
        }
        val shotgun = (0 until 200).associate { "bogus$it" to "1" }
        validIds.forEach { id ->
            assertNull(AutomationActionCatalog.sanitizeAction(AutomationActionSpec(id, shotgun)))
        }
        // A valid action behind 200 unknown keys drops (schema violation).
        assertNull(AutomationActionCatalog.sanitizeAction(AutomationActionSpec("torch", shotgun)))
    }

    @Test(timeout = 10_000)
    fun `random single-param values sanitize without throwing`() {
        val random = Random(0x5EEDC0DE)
        val types = AutomationActionCatalog.ACTION_TYPES
        repeat(2_000) {
            val type = types[random.nextInt(types.size)]
            if (type.params.isEmpty()) return@repeat
            val param = type.params[random.nextInt(type.params.size)]
            val value = rawValues[random.nextInt(rawValues.size)]
            val spec = AutomationActionSpec(type.id, mapOf(param.name to value))
            val sanitized = AutomationActionCatalog.sanitizeAction(spec)
            assertValidOrNull(spec, sanitized)
        }
    }

    // ── the invariants ──

    private fun assertValidOrNull(raw: AutomationActionSpec, sanitized: AutomationActionSpec?) {
        if (sanitized == null) {
            // Null is legal ONLY for an unknown id or an unknown param name —
            // a fully-known action must sanitize, not silently drop.
            val type = AutomationActionCatalog.typeFor(raw.type)
            val known = type?.params?.map { it.name } ?: emptyList()
            assertTrue(
                "unexpected null for id='${raw.type}' params=${raw.params.keys}",
                type == null || raw.params.keys.any { it !in known },
            )
            return
        }
        val type = AutomationActionCatalog.typeFor(sanitized.type)
        assertNotNull("output type must be in the catalog", type)
        type!!
        val known = type.params.associateBy { it.name }
        // Every output param name is catalog-known, every required param is
        // present, every value is in-bounds and canonical.
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
            when (param.kind) {
                ParamKind.BOOL ->
                    assertTrue("non-canonical BOOL '$value'", value == "true" || value == "false")
                ParamKind.INT, ParamKind.FLOAT -> {
                    val parsed = value.toDoubleOrNull()
                    assertNotNull("non-numeric output '$value' for ${param.name}", parsed)
                    parsed!!
                    val min = param.min
                    val max = param.max
                    if (min != null) {
                        assertTrue("value $value below min for ${param.name}", parsed >= min)
                    }
                    if (max != null) {
                        assertTrue("value $value above max for ${param.name}", parsed <= max)
                    }
                    // Canonical rendering: an integral value never carries a
                    // "." (no "1.0" survives the ladder).
                    if (parsed == kotlin.math.floor(parsed) && kotlin.math.abs(parsed) < 1e15) {
                        assertFalse("non-canonical integral rendering '$value'", value.contains('.'))
                    }
                }
            }
        }
        // The output is a fixed point: re-sanitizing changes nothing.
        assertEquals(sanitized, AutomationActionCatalog.sanitizeAction(sanitized))
    }

    private fun randomParams(random: Random, id: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        AutomationActionCatalog.typeFor(id)?.params?.forEach { param ->
            if (random.nextBoolean()) {
                params[param.name] = rawValues[random.nextInt(rawValues.size)]
            }
        }
        if (random.nextInt(4) == 0) {
            params["bogus${random.nextInt(1000)}"] = rawValues[random.nextInt(rawValues.size)]
        }
        return params
    }
}

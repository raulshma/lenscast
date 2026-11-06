package com.raulshma.lenscast.data

import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The automation-rule persistence codec under hostile JSON: a deterministic
 * corpus fuzzer over [parseAutomationRules] (the same seeded-corpus style as
 * [com.raulshma.lenscast.streaming.web.SettingsImportParserFuzzTest], same
 * internals access as [SettingsDataStoreAutomationPrefsTest]).
 *
 * ── The contract ──
 * parse() NEVER throws — the total decode folds every malformed payload to an
 * empty list. Every answer is a rule list capped at
 * [StreamDefaults.AUTOMATION_MAX_RULES] whose rules are all sanitized: a
 * non-blank id (a blank/absent one is minted), the one defined trigger type,
 * a minute inside 0..[StreamDefaults.MINUTES_PER_DAY]-1, a day mask inside
 * the Motion Arming Policy bounds, at most
 * [StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE] actions whose types are
 * all in the catalog with catalog-known param names and canonical in-bounds
 * values.
 *
 * ── The corpus ──
 * truncations at every prefix of a valid rule list, wrong-typed every field,
 * huge strings/numbers, valid rules with junk actions, oversized rule lists,
 * and seeded garbage JSON.
 */
class AutomationRulesJsonFuzzTest {

    // ── the contract ──

    private fun parseHostile(body: String?): List<AutomationRule> {
        try {
            return parseAutomationRules(body)
        } catch (t: Throwable) {
            throw AssertionError("parse leaked ${t.javaClass.simpleName}: $t", t)
        }
    }

    private fun assertSanitized(rules: List<AutomationRule>) {
        assertTrue(
            "rule list past the cap: ${rules.size}",
            rules.size <= StreamDefaults.AUTOMATION_MAX_RULES,
        )
        rules.forEach { rule ->
            assertTrue("blank id", !rule.id.isNullOrBlank())
            // The GET-side fire-stamp decoration never survives a decode —
            // a hostile payload cannot smuggle a claimed last-fired instant
            // into the engine's view of a rule.
            assertNull("a decoded rule carried lastFiredAt", rule.lastFiredAt)
            // An unknown trigger folds to time_of_day; anything that survives
            // decode must be a catalog kind.
            assertNotNull(
                "unknown trigger survived: ${rule.triggerType}",
                AutomationTriggerCatalog.typeFor(rule.triggerType),
            )
            assertTrue("minute out of range: ${rule.minuteOfDay}", rule.minuteOfDay in 0..StreamDefaults.MINUTES_PER_DAY - 1)
            assertTrue(
                "day mask out of range: ${rule.daysMask}",
                rule.daysMask in StreamDefaults.MOTION_ARM_DAYS_MASK_MIN..StreamDefaults.MOTION_ARM_DAYS_MASK_MAX,
            )
            assertTrue(
                "condition cap exceeded: ${rule.conditions.size}",
                rule.conditions.size <= StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE,
            )
            assertTrue(
                "unknown condition mode survived: ${rule.conditionMode}",
                rule.conditionMode == AutomationRule.CONDITION_MODE_ALL ||
                    rule.conditionMode == AutomationRule.CONDITION_MODE_ANY,
            )
            assertTrue(
                "cooldown out of range: ${rule.cooldownSeconds}",
                rule.cooldownSeconds in StreamDefaults.AUTOMATION_COOLDOWN_MIN_SECONDS..StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS,
            )
            assertTrue(
                "unknown missed-run policy survived: ${rule.missedRunPolicy}",
                rule.missedRunPolicy == AutomationRule.MISSED_RUN_CATCH_UP ||
                    rule.missedRunPolicy == AutomationRule.MISSED_RUN_SKIP ||
                    rule.missedRunPolicy == AutomationRule.MISSED_RUN_WINDOW,
            )
            assertTrue(
                "missed window out of range: ${rule.missedWindowMinutes}",
                rule.missedWindowMinutes in
                    StreamDefaults.AUTOMATION_MISSED_WINDOW_MIN_MINUTES..StreamDefaults.AUTOMATION_MISSED_WINDOW_MAX_MINUTES,
            )
            rule.conditions.forEach { condition ->
                val type = AutomationConditionCatalog.typeFor(condition.type)
                assertNotNull("unknown condition type survived: ${condition.type}", type)
                type!!
                val known = type.params.associateBy { it.name }
                condition.params.forEach { (name, value) ->
                    val param = known[name]
                    assertNotNull("unknown param $name survived on ${condition.type}", param)
                    param!!
                    assertCanonicalValue(param, value, condition.type)
                }
            }
            assertTrue("action cap exceeded: ${rule.actions.size}", rule.actions.size <= StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE)
            rule.actions.forEach { action ->
                val type = AutomationActionCatalog.typeFor(action.type)
                assertNotNull("unknown action type survived: ${action.type}", type)
                type!!
                val known = type.params.associateBy { it.name }
                action.params.forEach { (name, value) ->
                    val param = known[name]
                    assertNotNull("unknown param $name survived on ${action.type}", param)
                    param!!
                    assertCanonicalValue(param, value, action.type)
                }
            }
        }
    }

    /** One param's canonical in-bounds rendering, shared by both spec kinds. */
    private fun assertCanonicalValue(
        param: com.raulshma.lenscast.automation.schedule.AutomationParamSpec,
        value: String,
        typeId: String,
    ) {
        when (param.kind) {
            com.raulshma.lenscast.automation.schedule.ParamKind.BOOL ->
                assertTrue("non-canonical BOOL '$value'", value == "true" || value == "false")
            else -> {
                val parsed = value.toDoubleOrNull()
                assertNotNull("non-canonical numeric '$value'", parsed)
                parsed!!
                val min = param.min
                val max = param.max
                if (min != null) assertTrue("'$value' below min on $typeId", parsed >= min)
                if (max != null) assertTrue("'$value' above max on $typeId", parsed <= max)
                if (parsed == kotlin.math.floor(parsed) && kotlin.math.abs(parsed) < 1e15) {
                    assertTrue("non-canonical integral '$value'", !value.contains('.'))
                }
            }
        }
    }

    // ── positive controls ──

    @Test(timeout = 10_000)
    fun `a valid rule list parses sanitized`() {
        val rules = parseHostile(
            """[{"id":"r1","name":"Night","enabled":true,"triggerType":"time_of_day",""" +
                """"minuteOfDay":1320,"daysMask":127,"actions":[{"type":"torch","params":{"enabled":"true","strength":"8"}}]}]""",
        )
        assertEquals(1, rules.size)
        assertEquals("r1", rules.single().id)
        assertEquals("torch", rules.single().actions.single().type)
        assertSanitized(rules)
    }

    @Test(timeout = 10_000)
    fun `a pre-if-then payload decodes with the new fields defaulted`() {
        // The exact shape the previous app version persisted: no triggerParams,
        // conditions, conditionMode, or cooldownSeconds keys at all.
        val rules = parseHostile(
            """[{"id":"r1","name":"Old","enabled":true,"triggerType":"time_of_day",""" +
                """"minuteOfDay":420,"daysMask":31,"actions":[{"type":"stream","params":{"enabled":"true"}}]}]""",
        )
        assertEquals(1, rules.size)
        val rule = rules.single()
        assertEquals(emptyMap<String, String>(), rule.triggerParams)
        assertEquals(emptyList<Any>(), rule.conditions)
        assertEquals(AutomationRule.CONDITION_MODE_ALL, rule.conditionMode)
        assertEquals(StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS, rule.cooldownSeconds)
        assertSanitized(rules)
    }

    @Test(timeout = 10_000)
    fun `an event rule with trigger params and conditions parses sanitized`() {
        val rules = parseHostile(
            """[{"id":"r2","name":"Night motion","triggerType":"motion_detected",""" +
                """"triggerParams":{},"conditions":[{"type":"time_window","params":{"start_minute":"1320","end_minute":"360","days_mask":"127"}}],""" +
                """"conditionMode":"all","cooldownSeconds":120,"actions":[{"type":"siren","params":{"enabled":"true"}}]}]""",
        )
        assertEquals(1, rules.size)
        val rule = rules.single()
        assertEquals(AutomationTriggerCatalog.MOTION_DETECTED, rule.triggerType)
        assertEquals(1, rule.conditions.size)
        assertEquals(120, rule.cooldownSeconds)
        assertSanitized(rules)
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - truncations at every prefix of a valid list are a total decode`() {
        val valid =
            """[{"id":"r1","name":"Night","minuteOfDay":1320,"daysMask":127,"actions":[{"type":"torch","params":{"enabled":"true"}}]}]"""
        for (cut in valid.indices) {
            assertSanitized(parseHostile(valid.substring(0, cut)))
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - wrong-typed every field never throws`() {
        val hostile = listOf(
            """[{"id":42}]""",
            """[{"name":true}]""",
            """[{"enabled":"yes"}]""",
            """[{"enabled":3}]""",
            """[{"triggerType":[]}]""",
            """[{"minuteOfDay":"noon"}]""",
            """[{"minuteOfDay":1.5}]""",
            """[{"daysMask":"all"}]""",
            """[{"triggerParams":"level=5"}]""",
            """[{"triggerParams":{"level":5}}]""",
            """[{"conditions":"time_window"}]""",
            """[{"conditions":[42]}]""",
            """[{"conditions":[{"type":"bogus"}]}]""",
            """[{"conditionMode":3}]""",
            """[{"conditionMode":"some"}]""",
            """[{"cooldownSeconds":"slow"}]""",
            """[{"cooldownSeconds":1.5}]""",
            """[{"cooldownSeconds":-1}]""",
            """[{"cooldownSeconds":99999999999}]""",
            """[{"missedRunPolicy":42}]""",
            """[{"missedRunPolicy":"whenever"}]""",
            """[{"missedRunPolicy":"skip"}]""",
            """[{"missedWindowMinutes":"later"}]""",
            """[{"missedWindowMinutes":0}]""",
            """[{"missedWindowMinutes":-30}]""",
            """[{"missedWindowMinutes":99999999999}]""",
            """[{"missedRunPolicy":"window","missedWindowMinutes":1e400}]""",
            """[{"lastFiredAt":"soon"}]""",
            """[{"lastFiredAt":1234567890123}]""",
            """[{"lastFiredAt":null}]""",
            """[{"actions":{"type":"torch"}}]""",
            """[{"actions":[42]}]""",
            """[{"actions":[{"type":42}]}]""",
            """[{"actions":[{"type":"torch","params":"enabled=true"}]}]""",
            """[{"actions":[{"type":"torch","params":{"enabled":true}}]}]""",
            """[{"actions":[{"type":"torch","params":{"strength":9}}]}]""",
            """[{"actions":[{"type":"torch","params":{"bogus":"1"}}]}]""",
            """[null]""",
            """[[]]""",
            """[{"id":null,"name":null,"enabled":null,"triggerType":null,"minuteOfDay":null,"daysMask":null,"triggerParams":null,"conditions":null,"conditionMode":null,"cooldownSeconds":null,"missedRunPolicy":null,"missedWindowMinutes":null,"actions":null}]""",
            """[{"id":""},{"id":"   "}]""",
            "null", "true", "42", "\"a string\"", "[]", "{}", "[",
        )
        hostile.forEach { body -> assertSanitized(parseHostile(body)) }
        assertEquals(emptyList<AutomationRule>(), parseHostile(null))
        assertEquals(emptyList<AutomationRule>(), parseHostile(""))
    }

    @Test(timeout = 10_000)
    fun `corpus - huge strings and numbers stay a total decode`() {
        val bigString = "a".repeat(1_000_000)
        parseHostile("""[{"id":"$bigString"}]""")
        parseHostile("""[{"name":"$bigString"}]""")
        for (number in listOf("1e400", "-1e400", "9".repeat(400), "-9".repeat(400), "2147483648", "-2147483649")) {
            parseHostile("""[{"minuteOfDay":$number,"daysMask":$number}]""")
        }
        assertSanitized(parseHostile("""[{"minuteOfDay":2147483648,"daysMask":-2147483649}]"""))
    }

    @Test(timeout = 10_000)
    fun `corpus - valid rules with junk actions sanitize and cap`() {
        val junk = """[{"id":"r1","actions":[""" +
            listOf(
                """{"type":"bogus"}""",
                """{"type":"torch","params":{"enabled":"garbage","strength":"1e400"}}""",
                """{"type":"camera_exposure","params":{"iso":"NaN","exposure_time_ms":"Infinity"}}""",
                """{"type":"stream","params":{"enabled":"TRUE"}}""",
                """{"type":"torch","params":{"unknown_param":"5"}}""",
            ).joinToString(",") + """]}]"""
        val rules = parseHostile(junk)
        // The unknown-id action and the unknown-param action drop; the rest
        // sanitize — invalid VALUES fold to defaults, they never drop the
        // action (that is the unknown id/param branch's job alone).
        assertEquals(listOf("torch", "camera_exposure", "stream"), rules.single().actions.map { it.type })
        assertEquals(mapOf("enabled" to "true"), rules.single().actions.first().params)
        assertEquals(mapOf("enabled" to "true"), rules.single().actions.last().params)
        assertSanitized(rules)
    }

    @Test(timeout = 10_000)
    fun `corpus - more rules than the cap decode capped`() {
        val json = (0 until 40).joinToString(",", prefix = "[", postfix = "]") { index ->
            """{"id":"r$index","minuteOfDay":$index}"""
        }
        val rules = parseHostile(json)
        assertEquals(StreamDefaults.AUTOMATION_MAX_RULES, rules.size)
        assertEquals("r0", rules.first().id)
        assertSanitized(rules)
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage JSON never throws and always decodes sanitized`() {
        val random = Random(0x2A7E51D)
        val alphabet = """{}[]",:0123456789.eE+-truefalsn\/ ]]"""
        for (trial in 0 until 1_000) {
            val body = buildString {
                append(if (random.nextBoolean()) "[" else "{")
                repeat(random.nextInt(128)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            assertSanitized(parseHostile(body))
        }
    }
}

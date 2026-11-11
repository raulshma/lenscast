package com.raulshma.lenscast.automation

import com.raulshma.lenscast.capture.MinuteWindow
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.automation.schedule.AutomationTriggerKind
import com.raulshma.lenscast.automation.schedule.ConditionMode
import com.raulshma.lenscast.automation.schedule.MissedRunPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the rule editor's state module: the draft↔rule
 * conversion, the rotation-saver's JSON codec, the catalog-derived
 * auto-sentinel verdict, the time-window day mask, the preset chips, and the
 * spec-list reorder helper — everything the editor screen delegates to
 * instead of deciding inside composition.
 */
class RuleEditorStateTest {

    // ── RuleDraft.new() defaults vs AutomationRule defaults ──

    @Test
    fun `a fresh draft matches the rule defaults except its deliberate 08_00 start`() {
        val fresh = RuleDraft.new()
        // The persisted shape's decode-fallback defaults: everything except
        // minuteOfDay must agree, so a saved-as-is fresh draft sanitizes to
        // exactly what a decode-without-payload would produce.
        val ruleDefaults = AutomationRule()
        assertEquals(ruleDefaults.id, fresh.id)
        assertEquals(ruleDefaults.name, fresh.name)
        assertEquals(ruleDefaults.enabled, fresh.enabled)
        assertEquals(ruleDefaults.triggerType.id, fresh.triggerType)
        assertEquals(ruleDefaults.daysMask, fresh.daysMask)
        assertEquals(ruleDefaults.triggerParams, fresh.triggerParams)
        assertEquals(ruleDefaults.conditions, fresh.conditions)
        assertEquals(ruleDefaults.conditionMode.id, fresh.conditionMode)
        assertEquals(ruleDefaults.cooldownSeconds, fresh.cooldownSeconds)
        assertEquals(ruleDefaults.missedRunPolicy.id, fresh.missedRunPolicy)
        assertEquals(ruleDefaults.missedWindowMinutes, fresh.missedWindowMinutes)
        assertEquals(ruleDefaults.actions, fresh.actions)
        // The one deliberate difference: a fresh editor starts at 08:00, not
        // midnight (the web editor's newAutomationRule twin picks the same).
        assertEquals(8 * 60, fresh.minuteOfDay)
        assertTrue(fresh.isTimeTrigger)
    }

    @Test
    fun `a fresh draft projects to a rule the sanitizer keeps byte-identical defaults on`() {
        val projected = RuleDraft.new().toRule()
        assertEquals(AutomationRule(minuteOfDay = 8 * 60), projected)
    }

    // ── draft → rule → draft round trips ──

    @Test
    fun `a time rule round-trips field for field`() {
        val rule = AutomationRule(
            id = "r-1",
            name = "Evening torch",
            enabled = false,
            triggerType = AutomationTriggerKind.TIME_OF_DAY,
            minuteOfDay = 1_320,
            daysMask = 0b0111110,
            missedRunPolicy = MissedRunPolicy.WINDOW,
            missedWindowMinutes = 15,
        )
        assertEquals(rule, RuleDraft.from(rule).toRule())
        assertEquals(RuleDraft.from(rule), RuleDraft.from(RuleDraft.from(rule).toRule()))
        assertTrue(RuleDraft.from(rule).isTimeTrigger)
    }

    @Test
    fun `an event rule with conditions and actions round-trips field for field`() {
        val rule = AutomationRule(
            id = "r-2",
            name = "",
            enabled = true,
            triggerType = AutomationTriggerKind.MOTION_DETECTED,
            minuteOfDay = 0,
            daysMask = 62,
            triggerParams = mapOf("charging" to "true"),
            conditions = listOf(
                AutomationConditionSpec(
                    type = AutomationConditionCatalog.TIME_WINDOW,
                    params = mapOf("start_minute" to "1320", "end_minute" to "480", "days_mask" to "62"),
                ),
                AutomationConditionSpec(
                    type = AutomationConditionCatalog.CHARGING,
                    params = mapOf("charging" to "false"),
                ),
            ),
            conditionMode = ConditionMode.ANY,
            cooldownSeconds = 900,
            missedRunPolicy = MissedRunPolicy.SKIP,
            missedWindowMinutes = 30,
            actions = listOf(
                AutomationActionSpec(type = "torch", params = mapOf("enabled" to "true", "strength" to "3")),
                AutomationActionSpec(type = "camera_exposure", params = mapOf("iso" to "0")),
            ),
        )
        assertEquals(rule, RuleDraft.from(rule).toRule())
        assertFalse(RuleDraft.from(rule).isTimeTrigger)
    }

    // ── the draft saver's JSON codec ──

    @Test
    fun `the saver snapshot round-trips through encode and decode`() {
        val draft = RuleDraft.from(
            AutomationRule(
                id = null,
                name = "Draft in flight",
                triggerType = AutomationTriggerKind.BATTERY_LEVEL,
                triggerParams = mapOf("level" to "15"),
                conditions = listOf(
                    AutomationConditionSpec(
                        type = AutomationConditionCatalog.TIME_WINDOW,
                        params = mapOf("start_minute" to "480", "end_minute" to "600", "days_mask" to "127"),
                    ),
                ),
                cooldownSeconds = 1_800,
                actions = listOf(AutomationActionSpec(type = "stream", params = mapOf("enabled" to "true"))),
            ),
        )
        assertEquals(draft, decodeRuleDraftJson(encodeRuleDraftJson(draft)))
    }

    @Test
    fun `the snapshot keeps the persisted AutomationRule field names`() {
        // The snapshot serializes the AutomationRule projection with the
        // store's own Moshi codec — an in-flight draft written by the
        // previous build must restore byte-compatibly, so the shape is the
        // persisted rule shape, pinned here by the keys it must carry.
        val json = encodeRuleDraftJson(RuleDraft.new())
        for (key in listOf(
            "name", "enabled", "triggerType", "minuteOfDay", "daysMask",
            "triggerParams", "conditions", "conditionMode", "cooldownSeconds",
            "missedRunPolicy", "missedWindowMinutes", "actions",
        )) {
            assertTrue("snapshot key \"$key\"", json.contains("\"$key\""))
        }
        // The id-less fresh draft writes no id key (the store mints it at
        // save, not the saver).
        assertFalse(json.contains("\"id\""))
    }

    @Test
    fun `a snapshot written by the previous build decodes to the same draft`() {
        // Hand-written with the exact field names the current build's Saver
        // writes — the restore-compatibility contract for an in-flight draft
        // saved before this module was extracted.
        val json = """
            {"id":"r-9","name":"Porch","enabled":false,"triggerType":"time_of_day",
             "minuteOfDay":1320,"daysMask":62,"triggerParams":{},
             "conditions":[{"type":"time_window","params":{"start_minute":"1320","end_minute":"480","days_mask":"62"}}],
             "conditionMode":"any","cooldownSeconds":900,"missedRunPolicy":"window",
             "missedWindowMinutes":15,"actions":[{"type":"torch","params":{"enabled":"true"}}]}
        """.trimIndent()
        val restored = decodeRuleDraftJson(json)!!
        assertEquals("r-9", restored.id)
        assertEquals("Porch", restored.name)
        assertFalse(restored.enabled)
        assertEquals(1_320, restored.minuteOfDay)
        assertEquals(62, restored.daysMask)
        assertEquals(ConditionMode.ANY.id, restored.conditionMode)
        assertEquals(900, restored.cooldownSeconds)
        assertEquals(MissedRunPolicy.WINDOW.id, restored.missedRunPolicy)
        assertEquals(15, restored.missedWindowMinutes)
        assertEquals(listOf(AutomationActionSpec(type = "torch", params = mapOf("enabled" to "true"))), restored.actions)
        assertEquals(RuleDraft.from(restored.toRule()), restored)
    }

    @Test
    fun `a corrupt snapshot degrades to editor closed`() {
        assertNull(decodeRuleDraftJson("not json at all"))
        assertNull(decodeRuleDraftJson("{\"minuteOfDay\":\"not-a-number\"}"))
    }

    // ── the auto-sentinel verdict ──

    @Test
    fun `iso and exposure time render the auto toggle`() {
        val exposure = AutomationActionCatalog.typeFor("camera_exposure")!!
        val iso = exposure.params.first { it.name == "iso" }
        val exposureTime = exposure.params.first { it.name == "exposure_time_ms" }
        assertTrue(isAutoSentinelParam("camera_exposure", iso))
        assertTrue(isAutoSentinelParam("camera_exposure", exposureTime))
        // The specs carry the flag themselves, so the same verdict holds for
        // the row the editor actually renders.
        assertTrue(iso.autoAtZero)
    }

    @Test
    fun `ordinary and no-limit numerics do not render the auto toggle`() {
        val torchStrength = AutomationActionCatalog.typeFor("torch")!!.params.first { it.name == "strength" }
        val recordingDuration = AutomationActionCatalog.typeFor("recording")!!.params.first { it.name == "duration_seconds" }
        assertFalse(isAutoSentinelParam("torch", torchStrength))
        // duration_seconds "0" is the UNBOUNDED sentinel — documented NOT the
        // auto sentinel (autoAtZero stays false in the catalog).
        assertFalse(isAutoSentinelParam("recording", recordingDuration))
    }

    @Test
    fun `a param pair unknown to the catalog has no auto toggle`() {
        // A name the action does not list: no catalog spec, no sentinel —
        // the old hardcoded name-set fallback is gone, the catalog decides.
        val zoomRatio = AutomationActionCatalog.typeFor("camera_zoom")!!.params.first { it.name == "ratio" }
        assertFalse(isAutoSentinelParam("camera_exposure", zoomRatio))
        // An action id the catalog does not know at all.
        val iso = AutomationActionCatalog.typeFor("camera_exposure")!!.params.first { it.name == "iso" }
        assertFalse(isAutoSentinelParam("camera_exposure_legacy", iso))
    }

    // ── the time-window day mask ──

    @Test
    fun `the window day mask reads the param and falls back to the sanitize default`() {
        assertEquals(
            62,
            conditionWindowDaysMask(
                AutomationConditionSpec(
                    type = AutomationConditionCatalog.TIME_WINDOW,
                    params = mapOf("days_mask" to "62"),
                ),
            ),
        )
        // Absent (or unparseable) = every day, the same value the condition
        // catalog documents as the param default and the sanitizer folds to.
        assertEquals(
            StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT,
            conditionWindowDaysMask(AutomationConditionSpec(type = AutomationConditionCatalog.TIME_WINDOW)),
        )
        assertEquals(
            StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT,
            conditionWindowDaysMask(
                AutomationConditionSpec(
                    type = AutomationConditionCatalog.TIME_WINDOW,
                    params = mapOf("days_mask" to "not-a-mask"),
                ),
            ),
        )
    }

    @Test
    fun `mask from selected days round-trips the editor chip flow`() {
        // The chips toggle one ISO day bit at a time from the all-days mask
        // (MotionArmingPolicy convention): deselect Mon..Thu off 127 and the
        // remaining mask arms exactly Fri, Sat, Sun (bits 4..6 → 112).
        var mask = MotionArmingPolicy.ALL_DAYS_MASK
        for (day in 0..3) mask = MotionArmingPolicy.toggleDay(mask, day)
        assertEquals(0b1110000, mask)
        val condition = AutomationConditionSpec(
            type = AutomationConditionCatalog.TIME_WINDOW,
            params = mapOf("days_mask" to mask.toString()),
        )
        assertEquals(mask, conditionWindowDaysMask(condition))
        // The label-rendering inputs (the pure part): the armed ISO day
        // indices in order, and the every-day verdict only on the full mask.
        assertEquals(listOf(4, 5, 6), AutomationViewModel.armedDays(conditionWindowDaysMask(condition)))
        assertFalse(AutomationViewModel.isEveryDay(conditionWindowDaysMask(condition)))
        assertTrue(AutomationViewModel.isEveryDay(conditionWindowDaysMask(AutomationConditionSpec(type = AutomationConditionCatalog.TIME_WINDOW))))
    }

    @Test
    fun `a window with start past end wraps midnight per the shared ladder`() {
        // The wrap the editor's hint describes (start > end arms across
        // midnight) survives the draft round trip, and the bounds the editor
        // reads back cover minutes across the boundary through MinuteWindow.
        val condition = AutomationConditionSpec(
            type = AutomationConditionCatalog.TIME_WINDOW,
            params = mapOf("start_minute" to "1320", "end_minute" to "480", "days_mask" to "127"),
        )
        val draft = RuleDraft.new().copy(conditions = listOf(condition))
        assertEquals(condition, draft.toRule().conditions.single())
        val start = draft.toRule().conditions.single().params["start_minute"]!!.toInt()
        val end = draft.toRule().conditions.single().params["end_minute"]!!.toInt()
        assertTrue(start > end)
        // 22:00 → 08:00 wraps: covers 23:00, 00:30 and 07:59, not 12:00.
        assertTrue(MinuteWindow.covers(start, end, 23 * 60))
        assertTrue(MinuteWindow.covers(start, end, 30))
        assertTrue(MinuteWindow.covers(start, end, 8 * 60 - 1))
        assertFalse(MinuteWindow.covers(start, end, 12 * 60))
    }

    // ── the preset chips ──

    @Test
    fun `the cooldown presets are the app's stable chip contract`() {
        // Pinned exactly — the web client pins the same six spans
        // ("exactly the app's CooldownEditor presets").
        assertEquals(listOf(30, 60, 300, 900, 1_800, 3_600), AUTOMATION_COOLDOWN_PRESET_SECONDS)
        // The default rides the StreamDefaults constant, not a second literal.
        assertTrue(AUTOMATION_COOLDOWN_PRESET_SECONDS.contains(StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS))
        // Every chip lands inside the persisted bound, and the display
        // ceiling (where a wider persisted value renders pinned) is inside it too.
        for (preset in AUTOMATION_COOLDOWN_PRESET_SECONDS) {
            assertTrue(preset >= StreamDefaults.AUTOMATION_COOLDOWN_MIN_SECONDS)
            assertTrue(preset <= StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS)
        }
        assertTrue(AUTOMATION_COOLDOWN_DISPLAY_MAX_SECONDS <= StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS)
        // The readout's ceiling is the top chip: the slider max is a chip.
        assertEquals(AUTOMATION_COOLDOWN_PRESET_SECONDS.last(), AUTOMATION_COOLDOWN_DISPLAY_MAX_SECONDS)
    }

    @Test
    fun `the missed-window presets are the app's stable chip contract`() {
        assertEquals(listOf(5, 15, 30, 60, 180), AUTOMATION_MISSED_WINDOW_PRESET_MINUTES)
        assertTrue(AUTOMATION_MISSED_WINDOW_PRESET_MINUTES.contains(StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES))
        for (preset in AUTOMATION_MISSED_WINDOW_PRESET_MINUTES) {
            assertTrue(preset >= StreamDefaults.AUTOMATION_MISSED_WINDOW_MIN_MINUTES)
            assertTrue(preset <= StreamDefaults.AUTOMATION_MISSED_WINDOW_MAX_MINUTES)
        }
    }

    // ── the spec-list reorder helper ──

    @Test
    fun `moved relocates one entry without disturbing the others`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "b", "d"), list.moved(2, 1))
        assertEquals(listOf("b", "c", "d", "a"), list.moved(0, 3))
        // The source list is never mutated — the editor copies the draft.
        assertEquals(listOf("a", "b", "c", "d"), list)
    }
}

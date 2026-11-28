package com.raulshma.lenscast.automation

import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.automation.schedule.AutomationTriggerKind
import com.raulshma.lenscast.automation.schedule.MissedRunPolicy
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Pure JVM tests for the automation screen's UI-model helpers: the
 * action-summary resource builder, the canonical param-value renderers, the
 * rule-list transforms, and the time/day display helpers — everything the
 * ViewModel companion exposes without an Android dependency beyond the
 * constant R ids.
 */
class AutomationUiModelTest {

    // ── action summaries ──

    @Test
    fun `torch on with strength renders two lines`() {
        val lines = AutomationViewModel.actionSummaryLines(
            "torch",
            mapOf("enabled" to "true", "strength" to "3"),
        )
        assertEquals(2, lines.size)
        assertEquals(R.string.automation_summary_torch_on, lines[0].res)
        assertEquals(R.string.automation_summary_torch_strength, lines[1].res)
        assertEquals(listOf("3"), lines[1].args)
    }

    @Test
    fun `torch off folds its strength away`() {
        val lines = AutomationViewModel.actionSummaryLines(
            "torch",
            mapOf("enabled" to "false", "strength" to "3"),
        )
        assertEquals(1, lines.size)
        assertEquals(R.string.automation_summary_torch_off, lines[0].res)
        assertTrue(lines[0].args.isEmpty())
    }

    @Test
    fun `camera exposure renders one line per param with auto sentinels`() {
        val lines = AutomationViewModel.actionSummaryLines(
            "camera_exposure",
            mapOf("iso" to "4000", "exposure_time_ms" to "0"),
        )
        assertEquals(
            listOf(R.string.automation_summary_iso, R.string.automation_summary_exposure_auto),
            lines.map { it.res },
        )
        assertEquals(listOf("4000"), lines[0].args)
    }

    @Test
    fun `stream start and stop follow the enabled param`() {
        assertEquals(
            R.string.automation_summary_stream_start,
            AutomationViewModel.actionSummaryLines("stream", mapOf("enabled" to "true")).single().res,
        )
        assertEquals(
            R.string.automation_summary_stream_stop,
            AutomationViewModel.actionSummaryLines("stream", mapOf("enabled" to "false")).single().res,
        )
    }

    @Test
    fun `recording duration renders seconds and unbounded renders a plain start`() {
        val bounded = AutomationViewModel.actionSummaryLines(
            "recording",
            mapOf("enabled" to "true", "duration_seconds" to "300"),
        ).single()
        assertEquals(R.string.automation_summary_recording_duration, bounded.res)
        assertEquals(listOf("300"), bounded.args)

        assertEquals(
            R.string.automation_summary_recording_start,
            AutomationViewModel.actionSummaryLines("recording", mapOf("enabled" to "true")).single().res,
        )
        assertEquals(
            R.string.automation_summary_recording_stop,
            AutomationViewModel.actionSummaryLines("recording", mapOf("enabled" to "false")).single().res,
        )
    }

    @Test
    fun `siren and photo render their summaries`() {
        assertEquals(
            R.string.automation_summary_siren,
            AutomationViewModel.actionSummaryLines("siren", mapOf("enabled" to "true")).single().res,
        )
        assertEquals(
            R.string.automation_summary_siren_stop,
            AutomationViewModel.actionSummaryLines("siren", mapOf("enabled" to "false")).single().res,
        )
        assertEquals(
            R.string.automation_summary_photo,
            AutomationViewModel.actionSummaryLines("capture_photo", emptyMap()).single().res,
        )
    }

    @Test
    fun `detection renders one line per present gate`() {
        val lines = AutomationViewModel.actionSummaryLines(
            "detection",
            mapOf("motion_enabled" to "true", "sound_enabled" to "false"),
        )
        assertEquals(
            listOf(R.string.automation_summary_motion_on, R.string.automation_summary_sound_off),
            lines.map { it.res },
        )
    }

    @Test
    fun `night vision renders its mode`() {
        assertEquals(
            R.string.automation_summary_night_vision_on,
            AutomationViewModel.actionSummaryLines("night_vision", mapOf("mode" to "on")).single().res,
        )
        assertEquals(
            R.string.automation_summary_night_vision_auto,
            AutomationViewModel.actionSummaryLines("night_vision", mapOf("mode" to "auto")).single().res,
        )
        assertEquals(
            R.string.automation_summary_night_vision_off,
            AutomationViewModel.actionSummaryLines("night_vision", mapOf("mode" to "off")).single().res,
        )
    }

    @Test
    fun `unknown type falls back to the unknown action line`() {
        assertEquals(
            R.string.automation_action_unknown,
            AutomationViewModel.actionSummaryLines("mystery", emptyMap()).single().res,
        )
    }

    @Test
    fun `action type labels cover the whole catalog`() {
        for (id in listOf(
            "camera_exposure", "camera_ev", "camera_zoom", "camera_fps", "camera_lens",
            "night_vision", "torch", "stream", "capture_photo", "recording", "siren", "detection",
        )) {
            assertFalse(
                "missing label for $id",
                AutomationViewModel.actionTypeLabelRes(id) == R.string.automation_action_unknown,
            )
        }
        assertEquals(
            R.string.automation_action_unknown,
            AutomationViewModel.actionTypeLabelRes("mystery"),
        )
    }

    @Test
    fun `param labels resolve polymorphic names per action type`() {
        // "index" reads as a lens index for camera_lens, an EV step elsewhere.
        assertEquals(
            R.string.automation_param_lens_index,
            AutomationViewModel.paramLabelRes("camera_lens", "index"),
        )
        assertEquals(
            R.string.automation_param_ev_index,
            AutomationViewModel.paramLabelRes("camera_ev", "index"),
        )
        // "enabled" names a different device per action; anything else falls
        // to the generic enabled label.
        assertEquals(
            R.string.automation_param_torch,
            AutomationViewModel.paramLabelRes("torch", "enabled"),
        )
        assertEquals(
            R.string.automation_param_siren,
            AutomationViewModel.paramLabelRes("siren", "enabled"),
        )
        assertEquals(
            R.string.automation_param_enabled,
            AutomationViewModel.paramLabelRes("camera_exposure", "enabled"),
        )
        // Plain names and the unknown-param fallback.
        assertEquals(
            R.string.automation_param_zoom_ratio,
            AutomationViewModel.paramLabelRes("camera_zoom", "ratio"),
        )
        assertEquals(
            R.string.automation_param_enabled,
            AutomationViewModel.paramLabelRes("torch", "bogus_param"),
        )
    }

    // ── canonical param-value rendering ──

    @Test
    fun `canonical int renders whole numbers`() {
        assertEquals("4000", AutomationViewModel.canonicalIntValue(4000.4f))
        assertEquals("1", AutomationViewModel.canonicalIntValue(0.6f))
    }

    @Test
    fun `canonical float drops the trailing zero and rounds to one decimal`() {
        assertEquals("2", AutomationViewModel.canonicalFloatValue(2.0f))
        assertEquals("1.5", AutomationViewModel.canonicalFloatValue(1.5f))
        assertEquals("1.5", AutomationViewModel.canonicalFloatValue(1.46f))
        assertEquals("10", AutomationViewModel.canonicalFloatValue(9.96f))
    }

    // ── rule-list transforms ──

    @Test
    fun `add mints an id for id-less rules and caps the list`() {
        val added = AutomationViewModel.withRuleAdded(emptyList(), AutomationRule(name = "Night"))
        assertEquals(1, added.size)
        assertFalse(added[0].id.isNullOrBlank())

        var rules = added
        repeat(StreamDefaults.AUTOMATION_MAX_RULES * 4) {
            rules = AutomationViewModel.withRuleAdded(rules, AutomationRule(name = "r$it"))
        }
        assertEquals(StreamDefaults.AUTOMATION_MAX_RULES, rules.size)
    }

    @Test
    fun `add keeps an existing id untouched`() {
        val added = AutomationViewModel.withRuleAdded(
            emptyList(),
            AutomationRule(id = "fixed", name = "Night"),
        )
        assertEquals("fixed", added.single().id)
    }

    @Test
    fun `update replaces by id and ignores unknown or id-less rules`() {
        val a = AutomationRule(id = "a", name = "A")
        val b = AutomationRule(id = "b", name = "B")
        assertEquals(
            listOf(a.copy(name = "A2"), b),
            AutomationViewModel.withRuleUpdated(listOf(a, b), a.copy(name = "A2")),
        )
        assertEquals(
            listOf(a, b),
            AutomationViewModel.withRuleUpdated(listOf(a, b), AutomationRule(name = "no-id")),
        )
        assertEquals(
            listOf(a, b),
            AutomationViewModel.withRuleUpdated(listOf(a, b), AutomationRule(id = "zz", name = "missing")),
        )
    }

    @Test
    fun `remove drops only the matching id`() {
        val a = AutomationRule(id = "a", name = "A")
        val b = AutomationRule(id = "b", name = "B")
        assertEquals(listOf(b), AutomationViewModel.withRuleRemoved(listOf(a, b), "a"))
        assertEquals(listOf(a, b), AutomationViewModel.withRuleRemoved(listOf(a, b), "zz"))
    }

    @Test
    fun `toggle enabled flips only the matching rule`() {
        val a = AutomationRule(id = "a", name = "A")
        val b = AutomationRule(id = "b", name = "B")
        assertEquals(
            listOf(a.copy(enabled = false), b),
            AutomationViewModel.withRuleEnabled(listOf(a, b), "a", false),
        )
    }

    // ── param edits ──

    @Test
    fun `setActionParam writes and removes canonically`() {
        val torch = AutomationActionSpec("torch", mapOf("enabled" to "true"))
        assertEquals(
            mapOf("enabled" to "true", "strength" to "3"),
            AutomationViewModel.withActionParam(torch, "strength", "3").params,
        )
        assertEquals(
            mapOf("enabled" to "true"),
            AutomationViewModel.withActionParam(torch, "strength", null).params,
        )
        assertEquals(
            mapOf("enabled" to "true"),
            AutomationViewModel.withActionParam(torch, "strength", "").params,
        )
    }

    @Test
    fun `default action params carry only required params at their defaults`() {
        assertEquals(mapOf("enabled" to "true"), AutomationViewModel.defaultActionParams("torch"))
        assertEquals(emptyMap<String, String>(), AutomationViewModel.defaultActionParams("camera_exposure"))
        assertEquals(mapOf("fps" to "24"), AutomationViewModel.defaultActionParams("camera_fps"))
        assertEquals(mapOf("index" to "0"), AutomationViewModel.defaultActionParams("camera_lens"))
        assertEquals(emptyMap<String, String>(), AutomationViewModel.defaultActionParams("unknown_type"))
    }

    // ── torch strength bounds ──

    @Test
    fun `torch strength bounds follow the device max inside the catalog cap`() {
        assertEquals(1..8, AutomationViewModel.torchStrengthBounds(null))
        assertEquals(1..3, AutomationViewModel.torchStrengthBounds(3))
        assertEquals(1..8, AutomationViewModel.torchStrengthBounds(16))
        assertEquals(4, AutomationViewModel.clampTorchStrength(6, 4))
        assertEquals(1, AutomationViewModel.clampTorchStrength(0, 4))
    }

    @Test
    fun `strength editor hides only on a known single-level or unsupported verdict`() {
        // Null = no device verdict yet (camera not bound): the editor shows
        // and rides the catalog span, so LED brightness is selectable early.
        assertTrue(AutomationViewModel.showsTorchStrengthEditor(null))
        assertTrue(AutomationViewModel.showsTorchStrengthEditor(2))
        assertTrue(AutomationViewModel.showsTorchStrengthEditor(16))
        assertFalse(AutomationViewModel.showsTorchStrengthEditor(1))
        assertFalse(AutomationViewModel.showsTorchStrengthEditor(0))
    }

    // ── time + day helpers ──

    @Test
    fun `minute of day formats as a zero-padded 24h clock clamped into the day`() {
        assertEquals("00:00", AutomationViewModel.formatMinuteOfDay(0))
        assertEquals("08:05", AutomationViewModel.formatMinuteOfDay(8 * 60 + 5))
        assertEquals("23:59", AutomationViewModel.formatMinuteOfDay(1439))
        assertEquals("23:59", AutomationViewModel.formatMinuteOfDay(2000))
    }

    @Test
    fun `day helpers read the ISO day-bit mask`() {
        val mondayAndWednesday = 0b101
        assertEquals(listOf(0, 2), AutomationViewModel.armedDays(mondayAndWednesday))
        assertTrue(AutomationViewModel.isEveryDay(127))
        assertFalse(AutomationViewModel.isEveryDay(mondayAndWednesday))
    }

    // ── trigger + condition labels & summaries ──

    @Test
    fun `trigger labels resolve every catalog id and fall to unknown`() {
        assertEquals(
            R.string.automation_trigger_motion,
            AutomationViewModel.triggerTypeLabelRes("motion_detected"),
        )
        assertEquals(
            R.string.automation_trigger_time_of_day,
            AutomationViewModel.triggerTypeLabelRes("time_of_day"),
        )
        assertEquals(
            R.string.automation_action_unknown,
            AutomationViewModel.triggerTypeLabelRes("bogus"),
        )
        assertEquals(
            R.string.automation_trigger_illuminance,
            AutomationViewModel.triggerTypeLabelRes(AutomationTriggerCatalog.ILLUMINANCE),
        )
        AutomationTriggerCatalog.ACTION_TYPES.forEach { type ->
            assertFalse(
                "missing trigger label for ${type.id}",
                AutomationViewModel.triggerTypeLabelRes(type.id) == R.string.automation_action_unknown,
            )
        }
    }

    @Test
    fun `condition labels resolve every catalog id and fall to unknown`() {
        assertEquals(
            R.string.automation_condition_time_window,
            AutomationViewModel.conditionTypeLabelRes("time_window"),
        )
        assertEquals(
            R.string.automation_action_unknown,
            AutomationViewModel.conditionTypeLabelRes("bogus"),
        )
        assertEquals(
            R.string.automation_condition_illuminance,
            AutomationViewModel.conditionTypeLabelRes(AutomationConditionCatalog.ILLUMINANCE),
        )
        AutomationConditionCatalog.CONDITIONS.forEach { type ->
            assertFalse(
                "missing condition label for ${type.id}",
                AutomationViewModel.conditionTypeLabelRes(type.id) == R.string.automation_action_unknown,
            )
        }
    }

    @Test
    fun `every catalog param reads as its own row on its editor`() {
        // A param with no label of its own falls to the generic "Enabled"
        // row — which is how the lux thresholds once rendered here alone.
        AutomationTriggerCatalog.ACTION_TYPES.forEach { type ->
            type.params.forEach { param ->
                assertNotEquals(
                    "trigger ${type.id}/${param.name} has no label of its own",
                    R.string.automation_param_enabled,
                    AutomationViewModel.triggerParamLabelRes(type.id, param.name),
                )
            }
        }
        AutomationConditionCatalog.CONDITIONS.forEach { type ->
            type.params.forEach { param ->
                assertNotEquals(
                    "condition ${type.id}/${param.name} has no label of its own",
                    R.string.automation_param_enabled,
                    AutomationViewModel.conditionParamLabelRes(type.id, param.name),
                )
            }
        }
    }

    @Test
    fun `illuminance params read as lux rows`() {
        assertEquals(
            R.string.automation_param_lux_threshold,
            AutomationViewModel.triggerParamLabelRes(AutomationTriggerCatalog.ILLUMINANCE, "lux"),
        )
        assertEquals(
            R.string.automation_param_when_below,
            AutomationViewModel.triggerParamLabelRes(AutomationTriggerCatalog.ILLUMINANCE, "when_below"),
        )
        assertEquals(
            R.string.automation_param_lux_min,
            AutomationViewModel.conditionParamLabelRes(AutomationConditionCatalog.ILLUMINANCE, "min_lux"),
        )
        assertEquals(
            R.string.automation_param_lux_max,
            AutomationViewModel.conditionParamLabelRes(AutomationConditionCatalog.ILLUMINANCE, "max_lux"),
        )
    }

    @Test
    fun `time rule trigger summary is empty and event triggers render when-lines`() {
        assertEquals(
            emptyList<AutomationSummaryLine>(),
            AutomationViewModel.triggerSummaryLines(AutomationRule(triggerType = AutomationTriggerKind.TIME_OF_DAY)),
        )
        val motion = AutomationViewModel.triggerSummaryLines(
            AutomationRule(triggerType = AutomationTriggerKind.MOTION_DETECTED),
        )
        assertEquals(1, motion.size)
        assertEquals(R.string.automation_summary_trigger_motion, motion.single().res)
        assertTrue(motion.single().args.isEmpty())
    }

    @Test
    fun `battery trigger summary reads the threshold and direction`() {
        val below = AutomationViewModel.triggerSummaryLines(
            AutomationRule(
                triggerType = AutomationTriggerKind.BATTERY_LEVEL,
                triggerParams = mapOf("level" to "20", "when_below" to "true"),
            ),
        ).single()
        assertEquals(R.string.automation_summary_trigger_battery_below, below.res)
        assertEquals(listOf("20"), below.args)

        val above = AutomationViewModel.triggerSummaryLines(
            AutomationRule(
                triggerType = AutomationTriggerKind.BATTERY_LEVEL,
                triggerParams = mapOf("level" to "80", "when_below" to "false"),
            ),
        ).single()
        assertEquals(R.string.automation_summary_trigger_battery_above, above.res)
        assertEquals(listOf("80"), above.args)
    }

    @Test
    fun `illuminance trigger summary reads the threshold and direction`() {
        fun lineFor(vararg params: Pair<String, String>) = AutomationViewModel.triggerSummaryLines(
            AutomationRule(
                triggerType = AutomationTriggerKind.ILLUMINANCE,
                triggerParams = mapOf(*params),
            ),
        ).single()

        val below = lineFor("lux" to "25", "when_below" to "true")
        assertEquals(R.string.automation_summary_trigger_illuminance_below, below.res)
        assertEquals(listOf("25"), below.args)

        val above = lineFor("lux" to "400", "when_below" to "false")
        assertEquals(R.string.automation_summary_trigger_illuminance_above, above.res)
        assertEquals(listOf("400"), above.args)

        // An absent threshold reads the catalog default — the value the
        // engine evaluates the rule with.
        val defaulted = lineFor()
        assertEquals(R.string.automation_summary_trigger_illuminance_below, defaulted.res)
        assertEquals(listOf(StreamDefaults.LIGHT_SENSOR_TRIGGER_DEFAULT_LUX.toString()), defaulted.args)
    }

    @Test
    fun `charging trigger summary follows its polarity param`() {
        val on = AutomationViewModel.triggerSummaryLines(
            AutomationRule(triggerType = AutomationTriggerKind.CHARGING_CHANGED),
        ).single()
        assertEquals(R.string.automation_summary_trigger_charging_on, on.res)

        val off = AutomationViewModel.triggerSummaryLines(
            AutomationRule(
                triggerType = AutomationTriggerKind.CHARGING_CHANGED,
                triggerParams = mapOf("charging" to "false"),
            ),
        ).single()
        assertEquals(R.string.automation_summary_trigger_charging_off, off.res)
    }

    @Test
    fun `time window condition summary renders the formatted span`() {
        val line = AutomationViewModel.conditionSummaryLines(
            AutomationConditionSpec(
                AutomationConditionCatalog.TIME_WINDOW,
                mapOf("start_minute" to "1320", "end_minute" to "360", "days_mask" to "127"),
            ),
        ).single()
        assertEquals(R.string.automation_summary_condition_time_window, line.res)
        assertEquals(listOf("22:00", "06:00"), line.args)
    }

    @Test
    fun `battery condition summary picks the bound shape`() {
        fun lineFor(vararg params: Pair<String, String>) = AutomationViewModel.conditionSummaryLines(
            AutomationConditionSpec(AutomationConditionCatalog.BATTERY_LEVEL, mapOf(*params)),
        ).single()

        assertEquals(
            R.string.automation_summary_condition_battery_between,
            lineFor("min_level" to "20", "max_level" to "80").res,
        )
        assertEquals(
            R.string.automation_summary_condition_battery_min,
            lineFor("min_level" to "20").res,
        )
        assertEquals(
            R.string.automation_summary_condition_battery_max,
            lineFor("max_level" to "80").res,
        )
        assertEquals(
            R.string.automation_summary_condition_battery_any,
            lineFor().res,
        )
    }

    @Test
    fun `illuminance condition summary picks the bound shape`() {
        fun lineFor(vararg params: Pair<String, String>) = AutomationViewModel.conditionSummaryLines(
            AutomationConditionSpec(AutomationConditionCatalog.ILLUMINANCE, mapOf(*params)),
        ).single()

        val between = lineFor("min_lux" to "10", "max_lux" to "300")
        assertEquals(R.string.automation_summary_condition_illuminance_between, between.res)
        assertEquals(listOf("10", "300"), between.args)
        assertEquals(R.string.automation_summary_condition_illuminance_min, lineFor("min_lux" to "10").res)
        assertEquals(R.string.automation_summary_condition_illuminance_max, lineFor("max_lux" to "300").res)
        assertEquals(R.string.automation_summary_condition_illuminance_any, lineFor().res)
    }

    @Test
    fun `integer sliders keep a tick per value only up to the cap`() {
        // One tick — and one snap position — per whole value.
        assertEquals(0, AutomationViewModel.sliderSteps(0f, 1f, integer = true))
        assertEquals(1, AutomationViewModel.sliderSteps(0f, 2f, integer = true))
        assertEquals(99, AutomationViewModel.sliderSteps(0f, 100f, integer = true))
        assertEquals(99, AutomationViewModel.sliderSteps(-50f, 50f, integer = true))
        // Past the cap the track is continuous: each tick is a draw call per
        // frame, and the value still lands whole through canonicalIntValue.
        assertEquals(0, AutomationViewModel.sliderSteps(0f, 101f, integer = true))
        assertEquals(0, AutomationViewModel.sliderSteps(0f, 100_000f, integer = true))
        // A float track never ticks by step.
        assertEquals(0, AutomationViewModel.sliderSteps(0f, 1f, integer = false))
    }

    @Test
    fun `the typed entry opens on the same boundary that drops the ticks`() {
        assertFalse(AutomationViewModel.sliderIsWide(0f, 100f))
        assertTrue(AutomationViewModel.sliderIsWide(0f, 101f))
        assertTrue(AutomationViewModel.sliderIsWide(0f, 100_000f))
    }

    @Test
    fun `lux readout shows the reading, claims no sensor only on a known verdict, and otherwise waits`() {
        val measured = AutomationViewModel.illuminanceReadout(AutomationIlluminance(lux = 120, sensorAvailable = true))
        assertEquals(R.string.automation_illuminance_now, measured.res)
        assertEquals(listOf("120"), measured.args)

        assertEquals(
            R.string.automation_illuminance_unavailable,
            AutomationViewModel.illuminanceReadout(AutomationIlluminance(lux = null, sensorAvailable = false)).res,
        )
        // Silence is never "no sensor": a known sensor still debouncing its
        // first reading waits...
        assertEquals(
            R.string.automation_illuminance_waiting,
            AutomationViewModel.illuminanceReadout(AutomationIlluminance(lux = null, sensorAvailable = true)).res,
        )
        // ...and so does a device that has not answered, or is too old to
        // publish the field at all.
        assertEquals(
            R.string.automation_illuminance_waiting,
            AutomationViewModel.illuminanceReadout(AutomationIlluminance.UNKNOWN).res,
        )
        // A reading outranks the verdict.
        assertEquals(
            R.string.automation_illuminance_now,
            AutomationViewModel.illuminanceReadout(AutomationIlluminance(lux = 120, sensorAvailable = false)).res,
        )
    }

    @Test
    fun `boolean condition summaries follow their polarity`() {
        fun lineFor(type: String, vararg params: Pair<String, String>) = AutomationViewModel.conditionSummaryLines(
            AutomationConditionSpec(type, mapOf(*params)),
        ).single()

        assertEquals(R.string.automation_summary_condition_charging_on, lineFor(AutomationConditionCatalog.CHARGING).res)
        assertEquals(
            R.string.automation_summary_condition_charging_off,
            lineFor(AutomationConditionCatalog.CHARGING, "charging" to "false").res,
        )
        assertEquals(
            R.string.automation_summary_condition_power_save_on,
            lineFor(AutomationConditionCatalog.POWER_SAVE, "enabled" to "true").res,
        )
        assertEquals(R.string.automation_summary_condition_power_save_off, lineFor(AutomationConditionCatalog.POWER_SAVE).res)
    }

    @Test
    fun `unknown condition id renders no summary lines`() {
        assertTrue(AutomationViewModel.conditionSummaryLines(AutomationConditionSpec("bogus")).isEmpty())
    }

    @Test
    fun `default trigger and condition params carry required defaults only`() {
        assertEquals(
            mapOf("level" to "20", "when_below" to "true"),
            AutomationViewModel.defaultTriggerParams(AutomationTriggerCatalog.BATTERY_LEVEL),
        )
        assertEquals(emptyMap<String, String>(), AutomationViewModel.defaultTriggerParams(AutomationTriggerCatalog.TIME_OF_DAY))
        assertEquals(
            mapOf("start_minute" to "0", "end_minute" to "1439", "days_mask" to "127"),
            AutomationViewModel.defaultConditionParams(AutomationConditionCatalog.TIME_WINDOW),
        )
        // Optional condition params stay absent: absence IS "unbounded".
        assertEquals(
            emptyMap<String, String>(),
            AutomationViewModel.defaultConditionParams(AutomationConditionCatalog.BATTERY_LEVEL),
        )
    }

    @Test
    fun `withSpecParam writes and removes keys across the three spec kinds`() {
        val params = mapOf("a" to "1")
        assertEquals(mapOf("a" to "2"), AutomationViewModel.withSpecParam(params, "a", "2"))
        assertEquals(emptyMap<String, String>(), AutomationViewModel.withSpecParam(params, "a", null))
        assertEquals(emptyMap<String, String>(), AutomationViewModel.withSpecParam(params, "a", ""))

        val action = AutomationActionSpec("torch", params)
        assertEquals("2", AutomationViewModel.withActionParam(action, "a", "2").params["a"])
        assertFalse(AutomationViewModel.withActionParam(action, "a", null).params.containsKey("a"))

        val rule = AutomationRule(triggerParams = params)
        assertEquals("2", AutomationViewModel.withSpecParam(rule.triggerParams, "a", "2")["a"])

        val condition = AutomationConditionSpec(AutomationConditionCatalog.CHARGING, params)
        assertEquals("2", AutomationViewModel.withConditionParam(condition, "a", "2").params["a"])
    }

    // ── duplication ──

    @Test
    fun `duplicate inserts after the source with a fresh id enabled and the copy name`() {
        val a = AutomationRule(id = "a", name = "A", enabled = false)
        val b = AutomationRule(id = "b", name = "B")
        val duplicated = AutomationViewModel.withRuleDuplicated(listOf(a, b), a, "Copy of A")
        assertEquals(3, duplicated.size)
        assertEquals("a", duplicated[0].id)
        // A fresh UUID: the journal key must never be shared with the source.
        assertNotEquals("a", duplicated[1].id)
        assertFalse(duplicated[1].id.isNullOrBlank())
        assertEquals("Copy of A", duplicated[1].name)
        assertTrue(duplicated[1].enabled)
        assertEquals("b", duplicated[2].id)
    }

    @Test
    fun `duplicate ignores id-less unknown and at-cap sources`() {
        val a = AutomationRule(id = "a", name = "A")
        assertEquals(
            listOf(a),
            AutomationViewModel.withRuleDuplicated(listOf(a), AutomationRule(name = "no-id"), "x"),
        )
        assertEquals(
            listOf(a),
            AutomationViewModel.withRuleDuplicated(listOf(a), AutomationRule(id = "zz"), "x"),
        )
        var rules = listOf(a)
        repeat(StreamDefaults.AUTOMATION_MAX_RULES - 1) {
            rules = AutomationViewModel.withRuleAdded(rules, AutomationRule(name = "r$it"))
        }
        assertEquals(StreamDefaults.AUTOMATION_MAX_RULES, rules.size)
        assertEquals(rules, AutomationViewModel.withRuleDuplicated(rules, a, "Copy"))
    }

    // ── draft validation ──

    @Test
    fun `isValidDraft requires at least one action`() {
        // The engine fires nothing from an action-less rule — the editor's
        // Save gate and its inline error share this verdict.
        assertTrue(AutomationViewModel.isValidDraft(AutomationRule(name = "A", actions = listOf(AutomationActionSpec("torch")))))
        assertFalse(AutomationViewModel.isValidDraft(AutomationRule(name = "A")))
        assertFalse(AutomationViewModel.isValidDraft(AutomationRule(name = "A", actions = emptyList())))
    }

    // ── last-fired buckets ──

    @Test
    fun `last fired buckets today yesterday earlier and never`() {
        val zone = TimeZone.getTimeZone("UTC")
        // Fixed "now": 2026-11-04T12:00:00Z.
        val now = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()

        val today = AutomationViewModel.lastFiredLabel(now - 90_000L, now, zone)
        assertTrue(today is AutomationLastFiredLabel.Today)
        // 11:58:30 → minute-of-day 718.
        assertEquals(11 * 60 + 58, (today as AutomationLastFiredLabel.Today).minuteOfDay)

        val yesterdayMs = Instant.parse("2026-11-03T23:59:00Z").toEpochMilli()
        val yesterday = AutomationViewModel.lastFiredLabel(yesterdayMs, now, zone)
        assertTrue(yesterday is AutomationLastFiredLabel.Yesterday)
        assertEquals(23 * 60 + 59, (yesterday as AutomationLastFiredLabel.Yesterday).minuteOfDay)

        val earlierMs = Instant.parse("2026-11-01T08:15:00Z").toEpochMilli()
        val earlier = AutomationViewModel.lastFiredLabel(earlierMs, now, zone)
        assertTrue(earlier is AutomationLastFiredLabel.Earlier)
        assertEquals(8 * 60 + 15, (earlier as AutomationLastFiredLabel.Earlier).minuteOfDay)
        assertEquals(earlierMs, (earlier as AutomationLastFiredLabel.Earlier).epochMillis)

        // Never fired (and nonsense) renders nothing.
        assertNull(AutomationViewModel.lastFiredLabel(0L, now, zone))
        assertNull(AutomationViewModel.lastFiredLabel(-5L, now, zone))
    }

    @Test
    fun `last fired buckets follow the zone day boundary`() {
        // 2026-11-03T23:30Z is already Nov 4 in Berlin (UTC+1 in November):
        // against a 01:00Z "now", Berlin says today while UTC says yesterday.
        val fired = Instant.parse("2026-11-03T23:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-11-04T01:00:00Z").toEpochMilli()
        assertTrue(AutomationViewModel.lastFiredLabel(fired, now, TimeZone.getTimeZone("UTC")) is AutomationLastFiredLabel.Yesterday)
        assertTrue(
            AutomationViewModel.lastFiredLabel(fired, now, TimeZone.getTimeZone("Europe/Berlin"))
                is AutomationLastFiredLabel.Today,
        )
    }

    // ── humanized durations ──

    @Test
    fun `humanized durations round to the nearest unit with a floor of one`() {
        assertEquals(AutomationDurationLabel.Seconds(0), AutomationViewModel.humanizeDuration(0))
        assertEquals(AutomationDurationLabel.Seconds(59), AutomationViewModel.humanizeDuration(59))
        // Minutes: round-half-up, never zero.
        assertEquals(AutomationDurationLabel.Minutes(1), AutomationViewModel.humanizeDuration(60))
        assertEquals(AutomationDurationLabel.Minutes(1), AutomationViewModel.humanizeDuration(61))
        assertEquals(AutomationDurationLabel.Minutes(2), AutomationViewModel.humanizeDuration(90))
        assertEquals(AutomationDurationLabel.Minutes(60), AutomationViewModel.humanizeDuration(3_599))
        // Hours from a full hour up.
        assertEquals(AutomationDurationLabel.Hours(1), AutomationViewModel.humanizeDuration(3_600))
        assertEquals(AutomationDurationLabel.Hours(2), AutomationViewModel.humanizeDuration(5_400))
        assertEquals(AutomationDurationLabel.Hours(2), AutomationViewModel.humanizeDuration(7_200))
        // Negative input clamps to zero seconds.
        assertEquals(AutomationDurationLabel.Seconds(0), AutomationViewModel.humanizeDuration(-30))
    }

    @Test
    fun `cooldown presets land exactly on their unit`() {
        assertEquals(AutomationDurationLabel.Seconds(30), AutomationViewModel.humanizeDuration(30))
        assertEquals(AutomationDurationLabel.Minutes(5), AutomationViewModel.humanizeDuration(300))
        assertEquals(AutomationDurationLabel.Minutes(15), AutomationViewModel.humanizeDuration(900))
        assertEquals(AutomationDurationLabel.Minutes(30), AutomationViewModel.humanizeDuration(1_800))
        assertEquals(AutomationDurationLabel.Hours(1), AutomationViewModel.humanizeDuration(3_600))
    }

    // ── missed-run summary line ──

    @Test
    fun `the default catch_up policy and event rules get no card line`() {
        assertNull(
            AutomationViewModel.missedRunSummaryLine(
                AutomationRule(triggerType = AutomationTriggerKind.TIME_OF_DAY),
            ),
        )
        assertNull(
            AutomationViewModel.missedRunSummaryLine(
                AutomationRule(triggerType = AutomationTriggerKind.MOTION_DETECTED, missedRunPolicy = MissedRunPolicy.SKIP),
            ),
        )
        // The catch_up shape gets no line — and an unknown wire policy can
        // never reach a card at all: the decode folds it to catch_up (pinned
        // by SettingsDataStoreAutomationPrefsTest) and the card renders
        // nothing for that shape, as asserted here.
        assertNull(
            AutomationViewModel.missedRunSummaryLine(
                AutomationRule(triggerType = AutomationTriggerKind.TIME_OF_DAY, missedRunPolicy = MissedRunPolicy.CATCH_UP),
            ),
        )
    }

    @Test
    fun `skip renders its summary line`() {
        val line = AutomationViewModel.missedRunSummaryLine(
            AutomationRule(triggerType = AutomationTriggerKind.TIME_OF_DAY, missedRunPolicy = MissedRunPolicy.SKIP),
        )!!
        assertEquals(R.string.automation_missed_skip_summary, line.res)
        assertTrue(line.args.isEmpty())
    }

    @Test
    fun `window renders through the same rounded unit as the readout`() {
        val minutes = AutomationViewModel.missedRunSummaryLine(
            AutomationRule(
                triggerType = AutomationTriggerKind.TIME_OF_DAY,
                missedRunPolicy = MissedRunPolicy.WINDOW,
                missedWindowMinutes = 30,
            ),
        )!!
        assertEquals(R.string.automation_missed_window_summary_minutes, minutes.res)
        assertEquals(listOf("30"), minutes.args)

        val hours = AutomationViewModel.missedRunSummaryLine(
            AutomationRule(
                triggerType = AutomationTriggerKind.TIME_OF_DAY,
                missedRunPolicy = MissedRunPolicy.WINDOW,
                missedWindowMinutes = 180,
            ),
        )!!
        assertEquals(R.string.automation_missed_window_summary_hours, hours.res)
        assertEquals(listOf("3"), hours.args)
    }
}

package com.raulshma.lenscast.automation

import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "torch", "stream", "capture_photo", "recording", "siren", "detection",
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
}

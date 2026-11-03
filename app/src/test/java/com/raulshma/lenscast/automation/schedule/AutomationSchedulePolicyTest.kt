package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule policy's verdicts, mirroring
 * [com.raulshma.lenscast.capture.MotionArmingPolicyTest]: the occurrence-key
 * arithmetic, the due/after-journal/day-mask matrix, the STATEFUL/ONESHOT
 * catch-up split, the rule sanitizer's clamps, and the camera-settings merge
 * for every camera action type — all pure JVM.
 */
class AutomationSchedulePolicyTest {

    private val ALL_DAYS = StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT

    // Fixed schedule: epoch day 100, the rule fires at 10:00 (minute 600).
    private val day = 100L

    private fun rule(
        id: String? = "rule-1",
        minuteOfDay: Int = 600,
        daysMask: Int = ALL_DAYS,
        enabled: Boolean = true,
        actions: List<AutomationActionSpec> = listOf(
            AutomationActionSpec("stream", mapOf("enabled" to "true")),
        ),
    ) = AutomationRule(
        id = id,
        minuteOfDay = minuteOfDay,
        daysMask = daysMask,
        enabled = enabled,
        actions = actions,
    )

    private fun due(
        rules: List<AutomationRule> = listOf(rule()),
        minuteOfDay: Int = 600,
        enabled: Boolean = true,
        journal: Map<String, Long> = emptyMap(),
        isoDayIndex: Int = 0,
        epochDay: Long = day,
        graceMs: Long = StreamDefaults.AUTOMATION_ONESHOT_GRACE_MS,
    ) = AutomationSchedulePolicy.dueRules(
        rules, enabled, minuteOfDay, epochDay, isoDayIndex, journal, graceMs,
    )

    // ── occurrenceKey ──

    @Test
    fun `occurrence key is the day-minute counter`() {
        assertEquals(
            100L * StreamDefaults.MINUTES_PER_DAY + 600,
            AutomationSchedulePolicy.occurrenceKey(rule(), 100L),
        )
        assertEquals(0L, AutomationSchedulePolicy.occurrenceKey(rule(minuteOfDay = 0), 0L))
        assertEquals(
            StreamDefaults.MINUTES_PER_DAY.toLong(),
            AutomationSchedulePolicy.occurrenceKey(rule(minuteOfDay = 0), 1L),
        )
    }

    // ── Due verdict ──

    @Test
    fun `rule is due on its exact minute`() {
        val fired = due()
        assertEquals(listOf("rule-1"), fired.map { it.rule.id })
        assertEquals(listOf("stream"), fired.single().actions.map { it.type })
    }

    @Test
    fun `rule is not due before its minute`() {
        assertTrue(due(minuteOfDay = 599).isEmpty())
    }

    @Test
    fun `rule is still due later the same day (catch-up)`() {
        assertEquals(1, due(minuteOfDay = 900).size)
    }

    @Test
    fun `off day is never due`() {
        val mondayOnly = 0b0000001
        assertEquals(1, due(isoDayIndex = 0, rules = listOf(rule(daysMask = mondayOnly))).size)
        assertTrue(due(isoDayIndex = 2, rules = listOf(rule(daysMask = mondayOnly))).isEmpty())
        assertTrue(due(isoDayIndex = 6, rules = listOf(rule(daysMask = mondayOnly))).isEmpty())
    }

    @Test
    fun `master toggle off means nothing is due`() {
        assertTrue(due(enabled = false).isEmpty())
    }

    @Test
    fun `disabled rule is skipped even when its time arrives`() {
        assertTrue(due(rules = listOf(rule(enabled = false))).isEmpty())
    }

    @Test
    fun `a rule scheduled later today is not due yet`() {
        // The occurrence key sits in today's future; the time-arrived check
        // holds it back even with an empty journal.
        assertTrue(due(minuteOfDay = 0).isEmpty())
    }

    // ── Journal ──

    @Test
    fun `a journaled occurrence blocks the refire`() {
        val key = AutomationSchedulePolicy.occurrenceKey(rule(), day)
        assertTrue(due(journal = mapOf("rule-1" to key)).isEmpty())
        // A journal entry from later today (a stale or future write) blocks too.
        assertTrue(due(journal = mapOf("rule-1" to key + 1)).isEmpty())
    }

    @Test
    fun `an older journal key lets the rule fire again`() {
        // Yesterday's occurrence was journaled; today's is a new one.
        assertEquals(
            1,
            due(journal = mapOf("rule-1" to (day - 1) * StreamDefaults.MINUTES_PER_DAY + 600)).size,
        )
        // An earlier minute today journaled by a stale write still allows the
        // rule's own minute to fire.
        assertEquals(1, due(journal = mapOf("rule-1" to day * StreamDefaults.MINUTES_PER_DAY + 599)).size)
    }

    @Test
    fun `rules without an id are never blocked by the journal`() {
        assertEquals(1, due(rules = listOf(rule(id = null))).size)
    }

    // ── Catch-up: STATEFUL vs ONESHOT ──

    @Test
    fun `stateful actions fire hours past the occurrence`() {
        // 800 minutes late — lateness is schedule-time, so no wall clock needed.
        val fired = due(minuteOfDay = 1_400)
        assertEquals(listOf("stream"), fired.single().actions.map { it.type })
    }

    @Test
    fun `stateful actions catch up the next day`() {
        // Fired yesterday; today's occurrence is new, and yesterday's stateful
        // convergence must not be skipped just because the tick is late.
        val nextDay = day + 1
        val fired = due(
            minuteOfDay = 700,
            epochDay = nextDay,
            journal = mapOf("rule-1" to day * StreamDefaults.MINUTES_PER_DAY + 600),
        )
        assertEquals(listOf("rule-1"), fired.map { it.rule.id })
    }

    @Test
    fun `one-shot fires at the last minute of the grace window`() {
        val rules = listOf(rule(actions = listOf(AutomationActionSpec("capture_photo"))))
        // Exactly grace (10 min) late: 10 * 60_000 <= grace holds.
        val fired = due(rules = rules, minuteOfDay = 610)
        assertEquals(listOf("capture_photo"), fired.single().actions.map { it.type })
    }

    @Test
    fun `one-shot past grace is dropped and a one-shot-only rule never fires`() {
        val rules = listOf(rule(actions = listOf(AutomationActionSpec("capture_photo"))))
        assertTrue(due(rules = rules, minuteOfDay = 611).isEmpty())
    }

    @Test
    fun `past grace only the stateful actions remain`() {
        val rules = listOf(
            rule(
                actions = listOf(
                    AutomationActionSpec("capture_photo"),
                    AutomationActionSpec("torch", mapOf("enabled" to "true")),
                ),
            ),
        )
        val fired = due(rules = rules, minuteOfDay = 611)
        assertEquals(listOf("torch"), fired.single().actions.map { it.type })
    }

    @Test
    fun `unknown action types are filtered at the last gate`() {
        val fired = due(rules = listOf(rule(actions = listOf(AutomationActionSpec("bogus")))))
        assertTrue(fired.isEmpty())
    }

    // ── sanitizeRule ──

    @Test
    fun `sanitizeRule clamps minute and day mask`() {
        val high = AutomationSchedulePolicy.sanitizeRule(rule(minuteOfDay = 2_000, daysMask = 0))
        assertEquals(StreamDefaults.MINUTES_PER_DAY - 1, high.minuteOfDay)
        assertEquals(StreamDefaults.MOTION_ARM_DAYS_MASK_MIN, high.daysMask)

        val low = AutomationSchedulePolicy.sanitizeRule(rule(minuteOfDay = -5, daysMask = 200))
        assertEquals(0, low.minuteOfDay)
        assertEquals(StreamDefaults.MOTION_ARM_DAYS_MASK_MAX, low.daysMask)
    }

    @Test
    fun `sanitizeRule trims the name and resets an unknown trigger type`() {
        val sanitized = AutomationSchedulePolicy.sanitizeRule(
            AutomationRule(name = "  Night lock  ", triggerType = "motion_event"),
        )
        assertEquals("Night lock", sanitized.name)
        assertEquals(AutomationRule.TRIGGER_TIME_OF_DAY, sanitized.triggerType)
        val kept = AutomationSchedulePolicy.sanitizeRule(
            AutomationRule(triggerType = AutomationRule.TRIGGER_TIME_OF_DAY),
        )
        assertEquals(AutomationRule.TRIGGER_TIME_OF_DAY, kept.triggerType)
    }

    @Test
    fun `sanitizeRule drops unknown actions and caps the count`() {
        val actions = List(12) { AutomationActionSpec("stream", mapOf("enabled" to "true")) }
        val sanitized = AutomationSchedulePolicy.sanitizeRule(
            // Unknown action first: the cap applies to the *sanitized* list,
            // so valid actions behind unknown noise are not wasted.
            rule(actions = listOf(AutomationActionSpec("bogus")) + actions),
        )
        assertEquals(StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE, sanitized.actions.size)
        assertTrue(sanitized.actions.all { it.type == "stream" })
    }

    @Test
    fun `sanitizeRule clamps action params and preserves the id`() {
        val sanitized = AutomationSchedulePolicy.sanitizeRule(
            rule(
                id = "keep-me",
                actions = listOf(
                    AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "99")),
                ),
            ),
        )
        assertEquals("keep-me", sanitized.id)
        assertEquals("8", sanitized.actions.single().params["strength"])
    }

    @Test
    fun `sanitizeRules caps the list length`() {
        val sanitized = AutomationSchedulePolicy.sanitizeRules(List(20) { index -> rule(id = "r$index") })
        assertEquals(StreamDefaults.AUTOMATION_MAX_RULES, sanitized.size)
        assertEquals("r0", sanitized.first().id)
    }

    // ── mergeCameraSettings ──

    @Test
    fun `exposure merge maps auto sentinels to null and values to manual`() {
        val auto = AutomationSchedulePolicy.mergeCameraSettings(
            CameraSettings(iso = 400, exposureTime = 12_000_000L),
            AutomationActionSpec("camera_exposure", mapOf("iso" to "0", "exposure_time_ms" to "0")),
        )
        assertNull(auto.iso)
        assertNull(auto.exposureTime)

        val manual = AutomationSchedulePolicy.mergeCameraSettings(
            CameraSettings(),
            AutomationActionSpec("camera_exposure", mapOf("iso" to "800", "exposure_time_ms" to "33")),
        )
        assertEquals(800, manual.iso)
        assertEquals(33_000_000L, manual.exposureTime)
    }

    @Test
    fun `exposure merge leaves absent optional params unchanged`() {
        val base = CameraSettings(iso = 400, exposureTime = 5_000_000L)
        val merged = AutomationSchedulePolicy.mergeCameraSettings(
            base,
            AutomationActionSpec("camera_exposure", mapOf("iso" to "800")),
        )
        assertEquals(800, merged.iso)
        assertEquals(5_000_000L, merged.exposureTime)
    }

    @Test
    fun `exposure merge leaves malformed values unchanged instead of folding to auto`() {
        // A malformed value must not collapse the field to the auto sentinel:
        // "absent/malformed = leave unchanged" — folding a manual setting
        // back to driver auto on garbage would silently change the camera.
        val base = CameraSettings(iso = 400, exposureTime = 5_000_000L)
        val malformed = AutomationSchedulePolicy.mergeCameraSettings(
            base,
            AutomationActionSpec("camera_exposure", mapOf("iso" to "garbage", "exposure_time_ms" to "abc")),
        )
        assertEquals(400, malformed.iso)
        assertEquals(5_000_000L, malformed.exposureTime)

        // One malformed field must not block the parseable one.
        val half = AutomationSchedulePolicy.mergeCameraSettings(
            base,
            AutomationActionSpec("camera_exposure", mapOf("iso" to "garbage", "exposure_time_ms" to "20")),
        )
        assertEquals(400, half.iso)
        assertEquals(20_000_000L, half.exposureTime)

        val halfIso = AutomationSchedulePolicy.mergeCameraSettings(
            base,
            AutomationActionSpec("camera_exposure", mapOf("iso" to "200", "exposure_time_ms" to "abc")),
        )
        assertEquals(200, halfIso.iso)
        assertEquals(5_000_000L, halfIso.exposureTime)

        // Only the EXACT "0" sentinel is auto — "0.0" is not the sentinel
        // spelling (the catalog sanitizer canonicalizes it upstream, so a
        // raw "0.0" reaching here is a hand-built action) and does not
        // parse as an Int, so it leaves the field unchanged too.
        val zeroPoint = AutomationSchedulePolicy.mergeCameraSettings(
            base,
            AutomationActionSpec("camera_exposure", mapOf("iso" to "0.0")),
        )
        assertEquals(400, zeroPoint.iso)
        assertEquals(5_000_000L, zeroPoint.exposureTime)
    }

    @Test
    fun `ev zoom and fps merge into their fields`() {
        val base = CameraSettings()
        assertEquals(
            -4,
            AutomationSchedulePolicy.mergeCameraSettings(
                base, AutomationActionSpec("camera_ev", mapOf("index" to "-4")),
            ).exposureCompensation,
        )
        assertEquals(
            2.5f,
            AutomationSchedulePolicy.mergeCameraSettings(
                base, AutomationActionSpec("camera_zoom", mapOf("ratio" to "2.5")),
            ).zoomRatio,
        )
        assertEquals(
            30,
            AutomationSchedulePolicy.mergeCameraSettings(
                base, AutomationActionSpec("camera_fps", mapOf("fps" to "30")),
            ).frameRate,
        )
    }

    @Test
    fun `torch merge sets enabled always and strength only when present`() {
        val on = AutomationSchedulePolicy.mergeCameraSettings(
            CameraSettings(),
            AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "5")),
        )
        assertTrue(on.torchEnabled)
        assertEquals(5, on.torchStrength)

        // Absent strength: the toggle flips, an existing strength stays.
        val keepStrength = AutomationSchedulePolicy.mergeCameraSettings(
            CameraSettings(torchEnabled = false, torchStrength = 3),
            AutomationActionSpec("torch", mapOf("enabled" to "true")),
        )
        assertTrue(keepStrength.torchEnabled)
        assertEquals(3, keepStrength.torchStrength)

        val off = AutomationSchedulePolicy.mergeCameraSettings(
            on, AutomationActionSpec("torch", mapOf("enabled" to "false")),
        )
        assertFalse(off.torchEnabled)
        assertEquals(5, off.torchStrength)
    }

    @Test
    fun `non-camera types return settings unchanged`() {
        val base = CameraSettings(iso = 400, torchEnabled = true)
        listOf(
            "stream", "capture_photo", "recording", "siren", "detection",
            "camera_lens", "totally-unknown",
        ).forEach { type ->
            assertEquals(
                "unchanged: $type",
                base,
                AutomationSchedulePolicy.mergeCameraSettings(
                    base, AutomationActionSpec(type, mapOf("enabled" to "false")),
                ),
            )
        }
    }
}

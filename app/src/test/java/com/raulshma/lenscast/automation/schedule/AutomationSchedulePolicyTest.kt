package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.core.StreamDefaults
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule policy's verdicts, mirroring
 * [com.raulshma.lenscast.capture.MotionArmingPolicyTest]: the occurrence-key
 * arithmetic, the due/after-journal/day-mask matrix, the STATEFUL/ONESHOT
 * catch-up split, the event trigger matching and cooldown verdicts, the rule
 * sanitizer's clamps, and the camera-settings merge for every camera action
 * type — all pure JVM.
 */
class AutomationSchedulePolicyTest {

    private val ALL_DAYS = StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT

    // Fixed schedule: epoch day 100, the rule fires at 10:00 (minute 600).
    private val day = 100L

    private fun rule(
        id: String? = "rule-1",
        triggerType: AutomationTriggerKind = AutomationTriggerKind.TIME_OF_DAY,
        minuteOfDay: Int = 600,
        daysMask: Int = ALL_DAYS,
        enabled: Boolean = true,
        triggerParams: Map<String, String> = emptyMap(),
        conditions: List<AutomationConditionSpec> = emptyList(),
        conditionMode: ConditionMode = ConditionMode.ALL,
        cooldownSeconds: Int = StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS,
        missedRunPolicy: MissedRunPolicy = MissedRunPolicy.CATCH_UP,
        missedWindowMinutes: Int = StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES,
        actions: List<AutomationActionSpec> = listOf(
            AutomationActionSpec("stream", mapOf("enabled" to "true")),
        ),
    ) = AutomationRule(
        id = id,
        triggerType = triggerType,
        minuteOfDay = minuteOfDay,
        daysMask = daysMask,
        enabled = enabled,
        triggerParams = triggerParams,
        conditions = conditions,
        conditionMode = conditionMode,
        cooldownSeconds = cooldownSeconds,
        missedRunPolicy = missedRunPolicy,
        missedWindowMinutes = missedWindowMinutes,
        actions = actions,
    )

    private fun snapshot(
        minuteOfDay: Int = 600,
        epochDay: Long = day,
        isoDayIndex: Int = 0,
        charging: Boolean = false,
        batteryLevel: Int? = 80,
        powerSave: Boolean = false,
        wifiConnected: Boolean = true,
        streamingActive: Boolean = true,
        viewerCount: Int = 0,
        torchOn: Boolean = false,
        recordingActive: Boolean = false,
        thermalNormal: Boolean = true,
        motionDetectionEnabled: Boolean = true,
    ) = AutomationSensorSnapshot(
        minuteOfDay = minuteOfDay,
        epochDay = epochDay,
        isoDayIndex = isoDayIndex,
        charging = charging,
        batteryLevel = batteryLevel,
        powerSave = powerSave,
        wifiConnected = wifiConnected,
        streamingActive = streamingActive,
        viewerCount = viewerCount,
        torchOn = torchOn,
        recordingActive = recordingActive,
        thermalNormal = thermalNormal,
        motionDetectionEnabled = motionDetectionEnabled,
    )

    private fun due(
        rules: List<AutomationRule> = listOf(rule()),
        enabled: Boolean = true,
        journal: Map<String, Long> = emptyMap(),
        at: AutomationSensorSnapshot = snapshot(),
        graceMs: Long = StreamDefaults.AUTOMATION_ONESHOT_GRACE_MS,
    ) = AutomationSchedulePolicy.dueRules(rules, enabled, at, journal, graceMs)

    private fun eventFired(
        event: AutomationEvent,
        rules: List<AutomationRule> = listOf(rule(triggerType = AutomationTriggerKind.MOTION_DETECTED)),
        enabled: Boolean = true,
        journal: Map<String, Long> = emptyMap(),
        nowMs: Long = 1_800_000_000_000L,
        at: AutomationSensorSnapshot = snapshot(),
    ) = AutomationSchedulePolicy.eventDue(rules, enabled, event, at, journal, nowMs)

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

    @Test
    fun `decode occurrence key round-trips to the wall-clock instant`() {
        val utc = TimeZone.getTimeZone("UTC")
        val key = AutomationSchedulePolicy.occurrenceKey(rule(minuteOfDay = 600), day)
        assertEquals(
            LocalDate.ofEpochDay(day).atStartOfDay(utc.toZoneId()).plusMinutes(600).toInstant().toEpochMilli(),
            AutomationSchedulePolicy.decodeOccurrenceKey(key, utc),
        )
        // The zone decides the instant, not the key: the same key decodes
        // through Berlin's offset.
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        assertEquals(
            LocalDate.ofEpochDay(day).atStartOfDay(berlin.toZoneId()).plusMinutes(600).toInstant().toEpochMilli(),
            AutomationSchedulePolicy.decodeOccurrenceKey(key, berlin),
        )
    }

    @Test
    fun `decode passes event timestamps through and rejects negative keys`() {
        val eventMs = 1_800_000_000_000L
        assertEquals(eventMs, AutomationSchedulePolicy.decodeOccurrenceKey(eventMs, TimeZone.getTimeZone("UTC")))
        assertNull(AutomationSchedulePolicy.decodeOccurrenceKey(-1L, TimeZone.getTimeZone("UTC")))
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
        assertTrue(due(at = snapshot(minuteOfDay = 599)).isEmpty())
    }

    @Test
    fun `rule is still due later the same day (catch-up)`() {
        assertEquals(1, due(at = snapshot(minuteOfDay = 900)).size)
    }

    @Test
    fun `off day is never due`() {
        val mondayOnly = 0b0000001
        assertEquals(1, due(at = snapshot(isoDayIndex = 0), rules = listOf(rule(daysMask = mondayOnly))).size)
        assertTrue(due(at = snapshot(isoDayIndex = 2), rules = listOf(rule(daysMask = mondayOnly))).isEmpty())
        assertTrue(due(at = snapshot(isoDayIndex = 6), rules = listOf(rule(daysMask = mondayOnly))).isEmpty())
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
        assertTrue(due(at = snapshot(minuteOfDay = 0)).isEmpty())
    }

    @Test
    fun `event rules never ride the time tick`() {
        // An event rule's minuteOfDay is meaningless (default 0) — evaluating
        // it here would fire every event rule at midnight of every day.
        assertTrue(
            due(rules = listOf(rule(triggerType = AutomationTriggerKind.MOTION_DETECTED))).isEmpty(),
        )
    }

    // ── Time rules with preconditions ──

    @Test
    fun `a time rule with unmet conditions stays due for a later tick`() {
        val chargingOnly = listOf(
            rule(conditions = listOf(chargingCondition(required = true))),
        )
        // Unmet now: nothing fires, nothing journals.
        assertTrue(due(rules = chargingOnly).isEmpty())
        // Met on a later tick the same day: the rule fires (catch-up).
        assertEquals(
            1,
            due(rules = chargingOnly, at = snapshot(minuteOfDay = 700, charging = true)).size,
        )
    }

    @Test
    fun `condition mode all demands every condition`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    chargingCondition(required = true),
                    streamingCondition(required = false),
                ),
                conditionMode = ConditionMode.ALL,
            ),
        )
        assertTrue(due(rules = rules).isEmpty())
        assertEquals(1, due(rules = rules, at = snapshot(charging = true, streamingActive = false)).size)
    }

    @Test
    fun `condition mode any admits one matching condition`() {
        val rules = listOf(
            rule(
                conditions = listOf(
                    chargingCondition(required = true),
                    streamingCondition(required = false),
                ),
                conditionMode = ConditionMode.ANY,
            ),
        )
        assertEquals(1, due(rules = rules, at = snapshot(charging = true, streamingActive = true)).size)
        assertEquals(1, due(rules = rules, at = snapshot(charging = false, streamingActive = false)).size)
        // Neither condition met: held.
        assertTrue(due(rules = rules, at = snapshot(charging = false, streamingActive = true)).isEmpty())
    }

    @Test
    fun `an empty condition list fires under either mode`() {
        assertEquals(1, due(rules = listOf(rule(conditionMode = ConditionMode.ANY))).size)
        assertEquals(1, due(rules = listOf(rule(conditionMode = ConditionMode.ALL))).size)
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
    fun `an event-kind journal value does not block a time fire`() {
        // The rule used to be event-triggered and journaled a wall-clock ms;
        // that record is not a time occurrence key and must be inert here.
        assertEquals(
            1,
            due(journal = mapOf("rule-1" to AutomationSchedulePolicy.EVENT_JOURNAL_THRESHOLD)).size,
        )
    }

    @Test
    fun `rules without an id are never blocked by the journal`() {
        assertEquals(1, due(rules = listOf(rule(id = null))).size)
    }

    // ── Catch-up: STATEFUL vs ONESHOT ──

    @Test
    fun `stateful actions fire hours past the occurrence`() {
        // 800 minutes late — lateness is schedule-time, so no wall clock needed.
        val fired = due(at = snapshot(minuteOfDay = 1_400))
        assertEquals(listOf("stream"), fired.single().actions.map { it.type })
    }

    @Test
    fun `stateful actions catch up the next day`() {
        // Fired yesterday; today's occurrence is new, and yesterday's stateful
        // convergence must not be skipped just because the tick is late.
        val nextDay = day + 1
        val fired = due(
            at = snapshot(minuteOfDay = 700, epochDay = nextDay),
            journal = mapOf("rule-1" to day * StreamDefaults.MINUTES_PER_DAY + 600),
        )
        assertEquals(listOf("rule-1"), fired.map { it.rule.id })
    }

    @Test
    fun `one-shot fires at the last minute of the grace window`() {
        val rules = listOf(rule(actions = listOf(AutomationActionSpec("capture_photo"))))
        // Exactly grace (10 min) late: 10 * 60_000 <= grace holds.
        val fired = due(rules = rules, at = snapshot(minuteOfDay = 610))
        assertEquals(listOf("capture_photo"), fired.single().actions.map { it.type })
    }

    @Test
    fun `one-shot past grace is dropped and a one-shot-only rule never fires`() {
        val rules = listOf(rule(actions = listOf(AutomationActionSpec("capture_photo"))))
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 611)).isEmpty())
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
        val fired = due(rules = rules, at = snapshot(minuteOfDay = 611))
        assertEquals(listOf("torch"), fired.single().actions.map { it.type })
    }

    @Test
    fun `unknown action types are filtered at the last gate`() {
        val fired = due(rules = listOf(rule(actions = listOf(AutomationActionSpec("bogus")))))
        assertTrue(fired.isEmpty())
    }

    // ── Missed-run policy ──

    @Test
    fun `skip policy fires in the scheduled minute but never late`() {
        val rules = listOf(rule(missedRunPolicy = MissedRunPolicy.SKIP))
        assertEquals(1, due(rules = rules, at = snapshot(minuteOfDay = 600)).size)
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 601)).isEmpty())
        // Hours late — a late boot's first evaluation — still nothing:
        // "at the scheduled time or not at all".
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 1_400)).isEmpty())
    }

    @Test
    fun `window policy bounds the catch-up span and the boundary is inclusive`() {
        val rules = listOf(rule(missedRunPolicy = MissedRunPolicy.WINDOW, missedWindowMinutes = 30))
        assertEquals(listOf("stream"), due(rules = rules, at = snapshot(minuteOfDay = 630)).single().actions.map { it.type })
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 631)).isEmpty())
    }

    @Test
    fun `window policy lets a one-shot ride past the global grace`() {
        val rules = listOf(
            rule(
                missedRunPolicy = MissedRunPolicy.WINDOW,
                missedWindowMinutes = 30,
                actions = listOf(AutomationActionSpec("capture_photo")),
            ),
        )
        // 20 minutes late: a catch_up one-shot-only rule would have dropped
        // past the 10-minute grace, but the rule window is the authority.
        val fired = due(rules = rules, at = snapshot(minuteOfDay = 620))
        assertEquals(listOf("capture_photo"), fired.single().actions.map { it.type })
    }

    @Test
    fun `catch_up policy keeps the per-action grace for one-shots`() {
        val rules = listOf(
            rule(
                missedRunPolicy = MissedRunPolicy.CATCH_UP,
                missedWindowMinutes = 60,
                actions = listOf(AutomationActionSpec("capture_photo")),
            ),
        )
        // The window minutes are meaningless outside window mode: the
        // one-shot obeys the global 10-minute grace.
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 611)).isEmpty())
        assertEquals(
            listOf("capture_photo"),
            due(rules = rules, at = snapshot(minuteOfDay = 610)).single().actions.map { it.type },
        )
    }

    @Test
    fun `deferred conditions plus skip policy means the rule never fires that day`() {
        val rules = listOf(
            rule(
                missedRunPolicy = MissedRunPolicy.SKIP,
                conditions = listOf(AutomationConditionSpec("charging", mapOf("charging" to "true"))),
            ),
        )
        // Conditions unmet at the scheduled minute...
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 600, charging = false)).isEmpty())
        // ...met ten minutes later: the scheduled moment has passed, so the
        // skip policy holds — no same-day deferred fire.
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 610, charging = true)).isEmpty())
    }

    @Test
    fun `deferred conditions still fire under the default catch_up policy`() {
        val rules = listOf(
            rule(conditions = listOf(AutomationConditionSpec("charging", mapOf("charging" to "true")))),
        )
        assertTrue(due(rules = rules, at = snapshot(minuteOfDay = 600, charging = false)).isEmpty())
        assertEquals(1, due(rules = rules, at = snapshot(minuteOfDay = 610, charging = true)).size)
    }

    // ── Event path: trigger matching ──

    @Test
    fun `motion rule fires on motion only`() {
        assertEquals(1, eventFired(AutomationEvent.MotionDetected).size)
        assertTrue(eventFired(AutomationEvent.SoundDetected).isEmpty())
        assertTrue(eventFired(AutomationEvent.AppStarted).isEmpty())
    }

    @Test
    fun `time rules never match an event`() {
        assertTrue(
            eventFired(
                AutomationEvent.MotionDetected,
                rules = listOf(rule()), // default trigger: time_of_day
            ).isEmpty(),
        )
    }

    @Test
    fun `charging rule matches by polarity`() {
        val onPlug = listOf(rule(triggerType = AutomationTriggerKind.CHARGING_CHANGED))
        assertEquals(
            1,
            eventFired(AutomationEvent.ChargingChanged(charging = true), rules = onPlug).size,
        )
        assertTrue(
            eventFired(AutomationEvent.ChargingChanged(charging = false), rules = onPlug).isEmpty(),
        )
        // Inverted polarity: fires on unplug.
        val onUnplug = listOf(
            rule(
                triggerType = AutomationTriggerKind.CHARGING_CHANGED,
                triggerParams = mapOf("charging" to "false"),
            ),
        )
        assertEquals(
            1,
            eventFired(AutomationEvent.ChargingChanged(charging = false), rules = onUnplug).size,
        )
        assertTrue(
            eventFired(AutomationEvent.ChargingChanged(charging = true), rules = onUnplug).isEmpty(),
        )
    }

    @Test
    fun `streaming and viewer rules match by state`() {
        val onStart = listOf(rule(triggerType = AutomationTriggerKind.STREAMING_CHANGED))
        assertEquals(
            1,
            eventFired(AutomationEvent.StreamingChanged(streaming = true), rules = onStart).size,
        )
        assertTrue(
            eventFired(AutomationEvent.StreamingChanged(streaming = false), rules = onStart).isEmpty(),
        )

        val onJoin = listOf(rule(triggerType = AutomationTriggerKind.VIEWERS_CHANGED))
        assertEquals(
            1,
            eventFired(AutomationEvent.ViewersChanged(connected = true), rules = onJoin).size,
        )
        assertTrue(
            eventFired(AutomationEvent.ViewersChanged(connected = false), rules = onJoin).isEmpty(),
        )
    }

    @Test
    fun `battery rule fires only on its threshold crossing`() {
        fun batteryRule(whenBelow: Boolean) = rule(
            triggerType = AutomationTriggerKind.BATTERY_LEVEL,
            triggerParams = mapOf("level" to "20", "when_below" to whenBelow.toString()),
        )

        // Crossing below: 21 → 20 fires; steady 20 → 20 and 19 → 21 don't.
        assertEquals(
            1,
            eventFired(
                AutomationEvent.BatteryLevelChanged(previous = 21, current = 20),
                rules = listOf(batteryRule(whenBelow = true)),
            ).size,
        )
        assertTrue(
            eventFired(
                AutomationEvent.BatteryLevelChanged(previous = 20, current = 20),
                rules = listOf(batteryRule(whenBelow = true)),
            ).isEmpty(),
        )
        assertTrue(
            eventFired(
                AutomationEvent.BatteryLevelChanged(previous = 18, current = 22),
                rules = listOf(batteryRule(whenBelow = true)),
            ).isEmpty(),
        )
        // Unknown previous level (first read of the process) never fires.
        assertTrue(
            eventFired(
                AutomationEvent.BatteryLevelChanged(previous = null, current = 19),
                rules = listOf(batteryRule(whenBelow = true)),
            ).isEmpty(),
        )
        // Crossing above: 19 → 21 fires (the level moves strictly past 20).
        assertEquals(
            1,
            eventFired(
                AutomationEvent.BatteryLevelChanged(previous = 19, current = 21),
                rules = listOf(batteryRule(whenBelow = false)),
            ).size,
        )
        assertTrue(
            eventFired(
                AutomationEvent.BatteryLevelChanged(previous = 22, current = 21),
                rules = listOf(batteryRule(whenBelow = false)),
            ).isEmpty(),
        )
    }

    @Test
    fun `detection rules each match only their own event`() {
        val rules = listOf(
            rule(id = "m", triggerType = AutomationTriggerKind.MOTION_DETECTED),
            rule(id = "s", triggerType = AutomationTriggerKind.SOUND_DETECTED),
            rule(id = "t", triggerType = AutomationTriggerKind.TAMPER_DETECTED),
        )
        assertEquals(listOf("m"), eventFired(AutomationEvent.MotionDetected, rules = rules).map { it.rule.id })
        assertEquals(listOf("s"), eventFired(AutomationEvent.SoundDetected, rules = rules).map { it.rule.id })
        assertEquals(listOf("t"), eventFired(AutomationEvent.TamperDetected, rules = rules).map { it.rule.id })
    }

    @Test
    fun `app started fires exactly on the app-started event`() {
        val rules = listOf(rule(triggerType = AutomationTriggerKind.APP_STARTED))
        assertEquals(1, eventFired(AutomationEvent.AppStarted, rules = rules).size)
    }

    // ── Event path: gates ──

    @Test
    fun `master toggle off blocks event fires too`() {
        assertTrue(
            eventFired(AutomationEvent.MotionDetected, enabled = false).isEmpty(),
        )
    }

    @Test
    fun `disabled event rule never fires`() {
        assertTrue(
            eventFired(
                AutomationEvent.MotionDetected,
                rules = listOf(rule(triggerType = AutomationTriggerKind.MOTION_DETECTED, enabled = false)),
            ).isEmpty(),
        )
    }

    @Test
    fun `null-id event rule is skipped defensively`() {
        // A null-id rule could never be journaled; the store mints ids so
        // this is unreachable — skipping keeps a hot event loop impossible.
        assertTrue(
            eventFired(
                AutomationEvent.MotionDetected,
                rules = listOf(rule(id = null, triggerType = AutomationTriggerKind.MOTION_DETECTED)),
            ).isEmpty(),
        )
    }

    @Test
    fun `event conditions are checked at event time`() {
        val rules = listOf(
            rule(
                triggerType = AutomationTriggerKind.MOTION_DETECTED,
                conditions = listOf(streamingCondition(required = true)),
            ),
        )
        assertTrue(eventFired(AutomationEvent.MotionDetected, rules = rules, at = snapshot(streamingActive = false)).isEmpty())
        assertEquals(1, eventFired(AutomationEvent.MotionDetected, rules = rules, at = snapshot(streamingActive = true)).size)
    }

    @Test
    fun `event rules run their full action list with no catch-up filter`() {
        val rules = listOf(
            rule(
                triggerType = AutomationTriggerKind.MOTION_DETECTED,
                actions = listOf(AutomationActionSpec("capture_photo")),
            ),
        )
        val fired = eventFired(AutomationEvent.MotionDetected, rules = rules)
        assertEquals(listOf("capture_photo"), fired.single().actions.map { it.type })
    }

    // ── Event path: cooldown ──

    @Test
    fun `cooldown blocks a refire inside the window`() {
        val now = 1_800_000_000_000L
        val rules = listOf(
            rule(triggerType = AutomationTriggerKind.MOTION_DETECTED, cooldownSeconds = 120),
        )
        // Last fired 60 s ago with a 120 s cooldown: held.
        assertTrue(
            eventFired(
                AutomationEvent.MotionDetected,
                rules = rules,
                journal = mapOf("rule-1" to now - 60_000L),
                nowMs = now,
            ).isEmpty(),
        )
        // Exactly the cooldown ago: elapsed (now - journaled >= window).
        assertEquals(
            1,
            eventFired(
                AutomationEvent.MotionDetected,
                rules = rules,
                journal = mapOf("rule-1" to now - 120_000L),
                nowMs = now,
            ).size,
        )
    }

    @Test
    fun `zero cooldown admits every event`() {
        val now = 1_800_000_000_000L
        assertEquals(
            1,
            eventFired(
                AutomationEvent.MotionDetected,
                rules = listOf(rule(triggerType = AutomationTriggerKind.MOTION_DETECTED, cooldownSeconds = 0)),
                journal = mapOf("rule-1" to now),
                nowMs = now,
            ).size,
        )
    }

    @Test
    fun `an occurrence-key journal value is not a cooldown record`() {
        // The rule used to be a time trigger; its journaled occurrence key is
        // below the event threshold and must not read as "fired a millisecond
        // after the epoch".
        assertEquals(
            1,
            eventFired(
                AutomationEvent.MotionDetected,
                rules = listOf(rule(triggerType = AutomationTriggerKind.MOTION_DETECTED)),
                journal = mapOf("rule-1" to day * StreamDefaults.MINUTES_PER_DAY + 600),
            ).size,
        )
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
    fun `sanitizeRule trims the name and keeps a catalog trigger type`() {
        // The unknown-trigger fold itself moved to the JSON decode (the
        // vocabulary enum's parse folds an unknown wire id to time_of_day,
        // pinned by SettingsDataStoreAutomationPrefsTest); the sanitizer
        // keeps the final guard for a decoded constant.
        val sanitized = AutomationSchedulePolicy.sanitizeRule(
            AutomationRule(name = "  Night lock  ", triggerType = AutomationTriggerKind.MOTION_DETECTED),
        )
        assertEquals("Night lock", sanitized.name)
        assertEquals(AutomationTriggerKind.MOTION_DETECTED, sanitized.triggerType)
        val fallback = AutomationSchedulePolicy.sanitizeRule(
            AutomationRule(triggerType = AutomationTriggerKind.TIME_OF_DAY),
        )
        assertEquals(AutomationTriggerKind.TIME_OF_DAY, fallback.triggerType)
    }

    @Test
    fun `sanitizeRule canonicalizes trigger params and degrades unknown keys to defaults`() {
        val clamped = AutomationSchedulePolicy.sanitizeRule(
            rule(
                triggerType = AutomationTriggerKind.BATTERY_LEVEL,
                triggerParams = mapOf("level" to "5000", "when_below" to "TRUE"),
            ),
        )
        assertEquals("99", clamped.triggerParams["level"])
        assertEquals("true", clamped.triggerParams["when_below"])

        // An unknown key means the payload's shape is not trusted: the map
        // degrades to the schema's documented defaults, the rule survives.
        val degraded = AutomationSchedulePolicy.sanitizeRule(
            rule(
                triggerType = AutomationTriggerKind.BATTERY_LEVEL,
                triggerParams = mapOf("threshold_pct" to "5"),
            ),
        )
        assertEquals("20", degraded.triggerParams["level"])
        assertEquals("true", degraded.triggerParams["when_below"])
    }

    @Test
    fun `sanitizeRule drops unknown conditions and caps the count`() {
        val conditions = List(12) { chargingCondition(required = true) }
        val sanitized = AutomationSchedulePolicy.sanitizeRule(
            rule(
                conditions = listOf(AutomationConditionSpec("bogus")) + conditions,
            ),
        )
        assertEquals(StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE, sanitized.conditions.size)
        assertTrue(sanitized.conditions.all { it.type == AutomationConditionCatalog.CHARGING })
    }

    @Test
    fun `sanitizeRule clamps condition params`() {
        val sanitized = AutomationSchedulePolicy.sanitizeRule(
            rule(
                conditions = listOf(
                    AutomationConditionSpec(
                        AutomationConditionCatalog.TIME_WINDOW,
                        mapOf("start_minute" to "5000", "end_minute" to "-3", "days_mask" to "0"),
                    ),
                ),
            ),
        )
        val window = sanitized.conditions.single()
        assertEquals("1439", window.params["start_minute"])
        assertEquals("0", window.params["end_minute"])
        assertEquals("1", window.params["days_mask"])
    }

    @Test
    fun `sanitizeRule keeps the condition mode and clamps cooldown`() {
        // The unknown-mode fold moved to the JSON decode (ConditionMode.parse
        // maps anything that is not the exact ANY id to ALL, pinned by
        // SettingsDataStoreAutomationPrefsTest); the sanitizer keeps the
        // final guard over decoded constants.
        assertEquals(
            ConditionMode.ALL,
            AutomationSchedulePolicy.sanitizeRule(rule(conditionMode = ConditionMode.ALL)).conditionMode,
        )
        assertEquals(
            ConditionMode.ANY,
            AutomationSchedulePolicy.sanitizeRule(rule(conditionMode = ConditionMode.ANY)).conditionMode,
        )

        assertEquals(
            StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS,
            AutomationSchedulePolicy.sanitizeRule(rule(cooldownSeconds = 500_000)).cooldownSeconds,
        )
        // Below the floor clamps to 0 (fire every event), not to the default.
        assertEquals(
            StreamDefaults.AUTOMATION_COOLDOWN_MIN_SECONDS,
            AutomationSchedulePolicy.sanitizeRule(rule(cooldownSeconds = -1)).cooldownSeconds,
        )
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

    @Test
    fun `sanitizeRule strips a client-supplied lastFiredAt`() {
        // The fire stamp is a read-only GET decoration (the web handler's
        // copy(lastFiredAt=…) decodes the journal), never configuration: a
        // client echoing a response rule back in a PUT body cannot smuggle a
        // claimed last-fired instant into persistence or the engine.
        val smuggled = AutomationSchedulePolicy.sanitizeRule(
            rule(id = "r1").copy(lastFiredAt = 1_234_567_890_123L),
        )
        assertNull(smuggled.lastFiredAt)
        assertNull(
            AutomationSchedulePolicy.sanitizeRules(listOf(rule(id = "r2").copy(lastFiredAt = 42L)))
                .single().lastFiredAt,
        )
    }

    @Test
    fun `sanitizeRule keeps a missed-run policy and clamps the window`() {
        // The unknown-policy fold itself moved to the JSON decode
        // (MissedRunPolicy.parse folds an unknown wire id to catch_up, pinned
        // by SettingsDataStoreAutomationPrefsTest); the sanitizer keeps the
        // final guard over decoded constants.
        assertEquals(
            MissedRunPolicy.CATCH_UP,
            AutomationSchedulePolicy.sanitizeRule(rule(missedRunPolicy = MissedRunPolicy.CATCH_UP)).missedRunPolicy,
        )
        val skip = AutomationSchedulePolicy.sanitizeRule(rule(missedRunPolicy = MissedRunPolicy.SKIP))
        assertEquals(MissedRunPolicy.SKIP, skip.missedRunPolicy)
        val window = AutomationSchedulePolicy.sanitizeRule(
            rule(missedRunPolicy = MissedRunPolicy.WINDOW, missedWindowMinutes = 0),
        )
        assertEquals(MissedRunPolicy.WINDOW, window.missedRunPolicy)
        // Zero would be SKIP by another name — the floor stays at one minute.
        assertEquals(StreamDefaults.AUTOMATION_MISSED_WINDOW_MIN_MINUTES, window.missedWindowMinutes)
        val capped = AutomationSchedulePolicy.sanitizeRule(
            rule(missedRunPolicy = MissedRunPolicy.WINDOW, missedWindowMinutes = 100_000),
        )
        assertEquals(StreamDefaults.AUTOMATION_MISSED_WINDOW_MAX_MINUTES, capped.missedWindowMinutes)
        // A window within the span passes through untouched.
        val passThrough = AutomationSchedulePolicy.sanitizeRule(
            rule(missedRunPolicy = MissedRunPolicy.WINDOW, missedWindowMinutes = 45),
        )
        assertEquals(45, passThrough.missedWindowMinutes)
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

    // ── helpers ──

    private fun chargingCondition(required: Boolean) = AutomationConditionSpec(
        AutomationConditionCatalog.CHARGING,
        mapOf("charging" to required.toString()),
    )

    private fun streamingCondition(required: Boolean) = AutomationConditionSpec(
        AutomationConditionCatalog.STREAMING,
        mapOf("active" to required.toString()),
    )
}

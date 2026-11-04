package com.raulshma.lenscast.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The condition verdicts against a sensor snapshot: every catalog condition's
 * polarity, the range conditions' bound semantics, the midnight-wrapping
 * time window, the unknown-battery fail-closed, and the ALL/ANY/empty mode
 * ladder — all pure JVM.
 */
class AutomationConditionPolicyTest {

    private fun snapshot(
        minuteOfDay: Int = 600,
        isoDayIndex: Int = 0,
        charging: Boolean = false,
        batteryLevel: Int? = 80,
        powerSave: Boolean = false,
        wifiConnected: Boolean = true,
        streamingActive: Boolean = false,
        viewerCount: Int = 0,
        torchOn: Boolean = false,
        recordingActive: Boolean = false,
        thermalNormal: Boolean = true,
        motionDetectionEnabled: Boolean = true,
    ) = AutomationSensorSnapshot(
        minuteOfDay = minuteOfDay,
        epochDay = 100L,
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

    private fun condition(type: String, params: Map<String, String> = emptyMap()) =
        AutomationConditionSpec(type, params)

    private fun met(conditionSpec: AutomationConditionSpec, at: AutomationSensorSnapshot = snapshot()) =
        AutomationConditionPolicy.isMet(conditionSpec, at)

    // ── Boolean polarity conditions ──

    @Test
    fun `charging reads both polarities`() {
        val wantOn = condition(AutomationConditionCatalog.CHARGING, mapOf("charging" to "true"))
        val wantOff = condition(AutomationConditionCatalog.CHARGING, mapOf("charging" to "false"))
        assertTrue(met(wantOn, snapshot(charging = true)))
        assertFalse(met(wantOn, snapshot(charging = false)))
        assertFalse(met(wantOff, snapshot(charging = true)))
        assertTrue(met(wantOff, snapshot(charging = false)))
    }

    @Test
    fun `power_save wifi streaming torch recording thermal and motion read their states`() {
        assertTrue(met(condition(AutomationConditionCatalog.POWER_SAVE, mapOf("enabled" to "false"))))
        assertFalse(met(condition(AutomationConditionCatalog.POWER_SAVE, mapOf("enabled" to "true"))))

        assertTrue(met(condition(AutomationConditionCatalog.WIFI_CONNECTED, mapOf("connected" to "true"))))
        assertFalse(met(condition(AutomationConditionCatalog.WIFI_CONNECTED, mapOf("connected" to "true")), snapshot(wifiConnected = false)))

        assertTrue(met(condition(AutomationConditionCatalog.STREAMING, mapOf("active" to "true")), snapshot(streamingActive = true)))
        assertFalse(met(condition(AutomationConditionCatalog.STREAMING, mapOf("active" to "true"))))

        assertTrue(met(condition(AutomationConditionCatalog.TORCH, mapOf("on" to "true")), snapshot(torchOn = true)))
        assertFalse(met(condition(AutomationConditionCatalog.TORCH, mapOf("on" to "true"))))

        assertTrue(met(condition(AutomationConditionCatalog.RECORDING, mapOf("active" to "true")), snapshot(recordingActive = true)))
        assertFalse(met(condition(AutomationConditionCatalog.RECORDING, mapOf("active" to "true"))))

        assertTrue(met(condition(AutomationConditionCatalog.THERMAL, mapOf("normal" to "true"))))
        assertFalse(met(condition(AutomationConditionCatalog.THERMAL, mapOf("normal" to "true")), snapshot(thermalNormal = false)))

        assertTrue(met(condition(AutomationConditionCatalog.MOTION_DETECTION, mapOf("enabled" to "true"))))
        assertFalse(met(condition(AutomationConditionCatalog.MOTION_DETECTION, mapOf("enabled" to "true")), snapshot(motionDetectionEnabled = false)))
    }

    // ── Range conditions ──

    @Test
    fun `battery bounds are inclusive and one-sided by default`() {
        val minOnly = condition(AutomationConditionCatalog.BATTERY_LEVEL, mapOf("min_level" to "20"))
        assertTrue(met(minOnly, snapshot(batteryLevel = 20)))
        assertFalse(met(minOnly, snapshot(batteryLevel = 19)))
        assertTrue(met(minOnly, snapshot(batteryLevel = 100)))

        val maxOnly = condition(AutomationConditionCatalog.BATTERY_LEVEL, mapOf("max_level" to "30"))
        assertTrue(met(maxOnly, snapshot(batteryLevel = 30)))
        assertFalse(met(maxOnly, snapshot(batteryLevel = 31)))
        assertTrue(met(maxOnly, snapshot(batteryLevel = 0)))

        // Both bounds absent: trivially met.
        assertTrue(met(condition(AutomationConditionCatalog.BATTERY_LEVEL)))
    }

    @Test
    fun `unknown battery fails closed`() {
        // A null level is never guessed: the condition reads not met.
        assertFalse(
            met(condition(AutomationConditionCatalog.BATTERY_LEVEL, mapOf("min_level" to "20")), snapshot(batteryLevel = null)),
        )
        assertFalse(
            met(condition(AutomationConditionCatalog.BATTERY_LEVEL), snapshot(batteryLevel = null)),
        )
    }

    @Test
    fun `viewer bounds read the count`() {
        val some = condition(AutomationConditionCatalog.VIEWERS, mapOf("min_count" to "1"))
        assertTrue(met(some, snapshot(viewerCount = 1)))
        assertFalse(met(some, snapshot(viewerCount = 0)))

        val none = condition(AutomationConditionCatalog.VIEWERS, mapOf("max_count" to "0"))
        assertTrue(met(none, snapshot(viewerCount = 0)))
        assertFalse(met(none, snapshot(viewerCount = 2)))

        val band = condition(AutomationConditionCatalog.VIEWERS, mapOf("min_count" to "1", "max_count" to "3"))
        assertTrue(met(band, snapshot(viewerCount = 2)))
        assertFalse(met(band, snapshot(viewerCount = 4)))
    }

    // ── Time window ──

    @Test
    fun `time window covers a plain span`() {
        val window = condition(
            AutomationConditionCatalog.TIME_WINDOW,
            mapOf("start_minute" to "480", "end_minute" to "720", "days_mask" to "127"),
        )
        assertTrue(met(window, snapshot(minuteOfDay = 480)))
        assertTrue(met(window, snapshot(minuteOfDay = 600)))
        assertTrue(met(window, snapshot(minuteOfDay = 720)))
        assertFalse(met(window, snapshot(minuteOfDay = 479)))
        assertFalse(met(window, snapshot(minuteOfDay = 721)))
    }

    @Test
    fun `time window wraps across midnight`() {
        val night = condition(
            AutomationConditionCatalog.TIME_WINDOW,
            mapOf("start_minute" to "1320", "end_minute" to "360", "days_mask" to "127"),
        )
        assertTrue(met(night, snapshot(minuteOfDay = 1320)))
        assertTrue(met(night, snapshot(minuteOfDay = 0)))
        assertTrue(met(night, snapshot(minuteOfDay = 360)))
        assertFalse(met(night, snapshot(minuteOfDay = 361)))
        assertFalse(met(night, snapshot(minuteOfDay = 1319)))
    }

    @Test
    fun `time window respects the day mask and fails on degenerate bounds`() {
        val mondayOnly = condition(
            AutomationConditionCatalog.TIME_WINDOW,
            mapOf("start_minute" to "0", "end_minute" to "1439", "days_mask" to "1"),
        )
        assertTrue(met(mondayOnly, snapshot(isoDayIndex = 0)))
        assertFalse(met(mondayOnly, snapshot(isoDayIndex = 2)))

        // start == end covers nothing (an affirmative "inside the window"
        // has no "never restricts" reading).
        val degenerate = condition(
            AutomationConditionCatalog.TIME_WINDOW,
            mapOf("start_minute" to "600", "end_minute" to "600", "days_mask" to "127"),
        )
        assertFalse(met(degenerate, snapshot(minuteOfDay = 600)))
    }

    @Test
    fun `unknown condition id fails closed`() {
        assertFalse(met(condition("bogus")))
    }

    // ── Mode ladder ──

    @Test
    fun `mode all demands every condition`() {
        val rule = AutomationRule(
            conditions = listOf(
                condition(AutomationConditionCatalog.CHARGING, mapOf("charging" to "true")),
                condition(AutomationConditionCatalog.WIFI_CONNECTED, mapOf("connected" to "true")),
            ),
            conditionMode = AutomationRule.CONDITION_MODE_ALL,
        )
        assertTrue(AutomationConditionPolicy.conditionsMet(rule, snapshot(charging = true)))
        assertFalse(AutomationConditionPolicy.conditionsMet(rule, snapshot(charging = false)))
    }

    @Test
    fun `mode any admits one matching condition`() {
        val rule = AutomationRule(
            conditions = listOf(
                condition(AutomationConditionCatalog.CHARGING, mapOf("charging" to "true")),
                condition(AutomationConditionCatalog.STREAMING, mapOf("active" to "true")),
            ),
            conditionMode = AutomationRule.CONDITION_MODE_ANY,
        )
        assertTrue(AutomationConditionPolicy.conditionsMet(rule, snapshot(charging = true)))
        assertTrue(AutomationConditionPolicy.conditionsMet(rule, snapshot(streamingActive = true)))
        assertFalse(AutomationConditionPolicy.conditionsMet(rule, snapshot(charging = false)))
    }

    @Test
    fun `no conditions means met under either mode`() {
        val all = AutomationRule(conditionMode = AutomationRule.CONDITION_MODE_ALL)
        val any = AutomationRule(conditionMode = AutomationRule.CONDITION_MODE_ANY)
        // An empty ANY must not read vacuously false and silently disable
        // the rule.
        assertTrue(AutomationConditionPolicy.conditionsMet(all, snapshot()))
        assertTrue(AutomationConditionPolicy.conditionsMet(any, snapshot()))
    }

    @Test
    fun `snapshot wall-clock triple comes from the calendar`() {
        // 2026-01-05 is a Monday (ISO index 0), 12:30 local.
        val calendar = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JANUARY, 5, 12, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val at = snapshot().copy(
            minuteOfDay = com.raulshma.lenscast.core.LocalWallClock.minuteOfDay(calendar),
            epochDay = com.raulshma.lenscast.core.LocalWallClock.epochDay(calendar),
            isoDayIndex = com.raulshma.lenscast.core.LocalWallClock.isoDayIndex(calendar),
        )
        assertEquals(12 * 60 + 30, at.minuteOfDay)
        assertEquals(0, at.isoDayIndex)
    }
}

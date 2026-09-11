package com.raulshma.lenscast.automation

import com.raulshma.lenscast.automation.AutomationIntentPolicy.AutomationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The exported receiver's in-code security surface: the action dispatch
 * table (an unlisted action routes to nothing), the `enabled` extra verdict
 * shared by torch and siren, and the bounded extras parsing for recording
 * and siren durations. The manifest permission gate sits outside these
 * verdicts; everything a broadcast can influence after it passes the gate is
 * decided here.
 */
class AutomationIntentPolicyTest {

    // ── route: the action dispatch table ──

    @Test
    fun `every documented action routes to its operation`() {
        assertEquals(AutomationAction.START_STREAM, AutomationIntentPolicy.route(AutomationReceiver.ACTION_START_STREAM))
        assertEquals(AutomationAction.STOP_STREAM, AutomationIntentPolicy.route(AutomationReceiver.ACTION_STOP_STREAM))
        assertEquals(AutomationAction.CAPTURE_PHOTO, AutomationIntentPolicy.route(AutomationReceiver.ACTION_CAPTURE_PHOTO))
        assertEquals(AutomationAction.START_RECORDING, AutomationIntentPolicy.route(AutomationReceiver.ACTION_START_RECORDING))
        assertEquals(AutomationAction.STOP_RECORDING, AutomationIntentPolicy.route(AutomationReceiver.ACTION_STOP_RECORDING))
        assertEquals(AutomationAction.SET_TORCH, AutomationIntentPolicy.route(AutomationReceiver.ACTION_SET_TORCH))
        assertEquals(AutomationAction.SET_SIREN, AutomationIntentPolicy.route(AutomationReceiver.ACTION_SET_SIREN))
    }

    @Test
    fun `the action strings keep their package-scoped wire spelling`() {
        // Tasker/MacroDroid profiles, adb scripts, and the manifest
        // intent-filter all pin these literals; a rename would silently
        // break every external integration.
        assertEquals(
            "com.raulshma.lenscast.action.START_STREAM",
            AutomationReceiver.ACTION_START_STREAM,
        )
        assertEquals("com.raulshma.lenscast.action.STOP_STREAM", AutomationReceiver.ACTION_STOP_STREAM)
        assertEquals("com.raulshma.lenscast.action.CAPTURE_PHOTO", AutomationReceiver.ACTION_CAPTURE_PHOTO)
        assertEquals("com.raulshma.lenscast.action.START_RECORDING", AutomationReceiver.ACTION_START_RECORDING)
        assertEquals("com.raulshma.lenscast.action.STOP_RECORDING", AutomationReceiver.ACTION_STOP_RECORDING)
        assertEquals("com.raulshma.lenscast.action.SET_TORCH", AutomationReceiver.ACTION_SET_TORCH)
        assertEquals("com.raulshma.lenscast.action.SET_SIREN", AutomationReceiver.ACTION_SET_SIREN)
    }

    @Test
    fun `unknown and foreign actions route to nothing`() {
        assertNull(AutomationIntentPolicy.route(null))
        assertNull(AutomationIntentPolicy.route(""))
        assertNull(AutomationIntentPolicy.route("com.example.app.action.START_STREAM"))
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.SELF_DESTRUCT"))
        // Case is significant: a case-variant must not sneak onto a seam.
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.start_stream"))
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.START_STREAM "))
    }

    @Test
    fun `near-miss action spellings do not route`() {
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.START_STREAMX"))
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.START"))
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.SET"))
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action."))
        assertNull(AutomationIntentPolicy.route("com.raulshma.lenscast.action.START_STREAM\n"))
    }

    // ── resolveEnable: the enabled-extra verdict ──

    @Test
    fun `a present enabled extra forces the state in either direction`() {
        assertEquals(true, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = true, enabledExtraValue = true, currentState = true))
        assertEquals(true, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = true, enabledExtraValue = true, currentState = false))
        assertEquals(false, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = true, enabledExtraValue = false, currentState = true))
        assertEquals(false, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = true, enabledExtraValue = false, currentState = false))
    }

    @Test
    fun `an absent enabled extra toggles the current state`() {
        assertEquals(false, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = false, enabledExtraValue = false, currentState = true))
        assertEquals(true, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = false, enabledExtraValue = false, currentState = false))
    }

    @Test
    fun `an absent extra ignores the default value a caller would read`() {
        // The receiver reads getBooleanExtra(EXTRA_ENABLED, false); without
        // the extra present that default must never win over the toggle.
        assertEquals(true, AutomationIntentPolicy.resolveEnable(hasEnabledExtra = false, enabledExtraValue = false, currentState = false))
    }

    // ── recordingDurationSeconds: bounded ──

    @Test
    fun `zero duration means unbounded`() {
        assertEquals(0L, AutomationIntentPolicy.recordingDurationSeconds(0))
    }

    @Test
    fun `negative durations clamp to unbounded zero`() {
        assertEquals(0L, AutomationIntentPolicy.recordingDurationSeconds(-1))
        assertEquals(0L, AutomationIntentPolicy.recordingDurationSeconds(Int.MIN_VALUE))
    }

    @Test
    fun `positive durations pass through unchanged`() {
        assertEquals(30L, AutomationIntentPolicy.recordingDurationSeconds(30))
        assertEquals(3599L, AutomationIntentPolicy.recordingDurationSeconds(3599))
    }

    @Test
    fun `durations above the one-hour ceiling clamp to 3600`() {
        assertEquals(3600L, AutomationIntentPolicy.recordingDurationSeconds(3600))
        assertEquals(3600L, AutomationIntentPolicy.recordingDurationSeconds(3601))
        assertEquals(3600L, AutomationIntentPolicy.recordingDurationSeconds(Int.MAX_VALUE))
    }

    // ── sirenAutoStopDelayMs: only an explicit extra arms the timer ──

    @Test
    fun `an absent duration extra arms no timer`() {
        assertNull(AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = false, rawDurationSeconds = 0))
    }

    @Test
    fun `a zero duration is the explicit no-limit`() {
        // 0 ms arms no timer in SirenAutoStop — distinct from "extra absent",
        // because an explicit 0 must still cancel/replace an armed stop.
        assertEquals(0L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = 0))
    }

    @Test
    fun `durations convert to milliseconds`() {
        assertEquals(1_000L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = 1))
        assertEquals(30_000L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = 30))
        assertEquals(3_600_000L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = 3600))
    }

    @Test
    fun `negative durations pass through unclamped`() {
        // Parity with the receiver: SirenAutoStop treats a non-positive delay
        // as "no timer", so a negative extra is harmless without a clamp.
        assertEquals(-5_000L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = -5))
    }

    @Test
    fun `the largest duration does not overflow the millisecond conversion`() {
        assertEquals(2_147_483_647_000L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = Int.MAX_VALUE))
    }

    // ── the non-integer wire value: the framework degrades it, not the policy ──

    @Test
    fun `a non-integer duration extra reaches the policy as the zero default`() {
        // The receiver reads the extra with Intent.getIntExtra, and the
        // framework's Bundle.getInt catches the ClassCastException of a
        // String/boolean extra and answers the 0 default — the policy only
        // ever sees an Int, so "non-integer on the wire" is exactly the
        // explicit-0 verdict below for both consumers: unbounded for the
        // recorder (extra may be absent) and the no-limit arm for the siren
        // (extra present, delay 0).
        assertEquals(0L, AutomationIntentPolicy.recordingDurationSeconds(0))
        assertEquals(0L, AutomationIntentPolicy.sirenAutoStopDelayMs(hasDurationExtra = true, rawDurationSeconds = 0))
    }
}

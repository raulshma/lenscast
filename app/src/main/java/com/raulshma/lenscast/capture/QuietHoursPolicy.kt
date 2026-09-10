package com.raulshma.lenscast.capture

/**
 * Pure quiet-hours verdict for local detection alerts: inside the window the
 * coordinator holds the heads-up notification (webhook, MQTT, and the event
 * log keep firing — quiet hours silence the phone, not the integrations).
 * Window semantics mirror [MotionArmingPolicy]: minute-of-day 0..1439,
 * midnight-wrapping when start > end, and start == end degenerate (the
 * persisted defaults are 22:00 → 07:00, so a user toggling the feature on
 * without touching the sliders gets a sane night window).
 */
object QuietHoursPolicy {

    fun isQuiet(
        enabled: Boolean,
        startMinute: Int,
        endMinute: Int,
        minuteOfDay: Int,
    ): Boolean {
        if (!enabled) return false
        return MinuteWindow.covers(startMinute, endMinute, minuteOfDay)
    }
}

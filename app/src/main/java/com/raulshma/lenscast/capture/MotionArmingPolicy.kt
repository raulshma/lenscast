package com.raulshma.lenscast.capture

/**
 * Pure arming verdict for event detection (motion or sound) under an optional
 * time-of-day schedule. Minute-of-day is 0..1439; a window that wraps
 * midnight (start > end) arms across the boundary; start == end means the
 * schedule is degenerate and never restricts anything.
 */
object MotionArmingPolicy {

    fun isArmed(
        detectionEnabled: Boolean,
        scheduleEnabled: Boolean,
        startMinute: Int,
        endMinute: Int,
        minuteOfDay: Int,
    ): Boolean {
        if (!detectionEnabled) return false
        if (!scheduleEnabled) return true
        if (startMinute == endMinute) return true
        // Both bounds are inclusive: endMinute is a minute-of-day the user
        // picks on a slider ("arm until 23:59" must include 23:59).
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute..endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay <= endMinute
        }
    }
}

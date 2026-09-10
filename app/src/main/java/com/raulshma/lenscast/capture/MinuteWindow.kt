package com.raulshma.lenscast.capture

/**
 * The one midnight-wrapping minute-of-day window ladder behind the arm
 * schedule ([MotionArmingPolicy]) and quiet hours ([QuietHoursPolicy]):
 * minutes are 0..1439, both bounds inclusive, start > end wraps across
 * midnight. Equal bounds cover nothing — each caller decides its own
 * degenerate semantics (the schedule's "never restricts", quiet hours'
 * "never quiet") before consulting this.
 */
object MinuteWindow {

    fun covers(startMinute: Int, endMinute: Int, minuteOfDay: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute..endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay <= endMinute
        }
    }
}

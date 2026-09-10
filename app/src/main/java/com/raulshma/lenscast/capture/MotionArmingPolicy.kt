package com.raulshma.lenscast.capture

import com.raulshma.lenscast.core.StreamDefaults

/**
 * Pure arming verdict for event detection (motion or sound) under an optional
 * time-of-day schedule and day-of-week mask. Minute-of-day is 0..1439; a
 * window that wraps midnight (start > end) arms across the boundary;
 * start == end means the schedule is degenerate and never restricts anything.
 *
 * The day mask carries one bit per ISO day index (bit 0 = Monday … bit 6 =
 * Sunday); [ALL_DAYS_MASK] (the persisted default) arms every day, so the
 * schedule degrades to the legacy time-of-day-only verdict.
 */
object MotionArmingPolicy {

    /** Every day armed — the default mask. */
    const val ALL_DAYS_MASK = StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT

    /** Bit position for one ISO day index (0 = Monday … 6 = Sunday). */
    fun dayBit(isoDayIndex: Int): Int = 1 shl isoDayIndex.coerceIn(
        StreamDefaults.MOTION_ARM_DAYS_MONDAY,
        StreamDefaults.MOTION_ARM_DAYS_SUNDAY,
    )

    /** Whether [daysMask] arms [isoDayIndex] (0 = Monday … 6 = Sunday). */
    fun isArmedDay(daysMask: Int, isoDayIndex: Int): Boolean =
        (daysMask and dayBit(isoDayIndex)) != 0

    /**
     * The day-chip toggle verdict: flips [isoDayIndex]'s bit in [daysMask]
     * but never clears the last armed day (the store's clamp would restore
     * it, so the verdict keeps it on — an all-off schedule cannot arise).
     */
    fun toggleDay(daysMask: Int, isoDayIndex: Int): Int {
        val toggled = daysMask xor dayBit(isoDayIndex)
        return if (toggled == 0) daysMask else toggled
    }

    /**
     * [daysMask] and [isoDayIndex] have no defaults on purpose: a silently
     * wrong day (the mask checked against the wrong ISO index) would read as
     * "disarmed for no reason". [ALL_DAYS_MASK] with any index is the
     * explicit time-of-day-only shape.
     */
    fun isArmed(
        detectionEnabled: Boolean,
        scheduleEnabled: Boolean,
        startMinute: Int,
        endMinute: Int,
        minuteOfDay: Int,
        daysMask: Int,
        isoDayIndex: Int,
    ): Boolean {
        if (!detectionEnabled) return false
        if (!scheduleEnabled) return true
        if (!isArmedDay(daysMask, isoDayIndex)) return false
        // The schedule's degenerate window is "never restricts", the opposite
        // of quiet hours' — decided here, the shared window only ladders the
        // wrap arithmetic.
        if (startMinute == endMinute) return true
        return MinuteWindow.covers(startMinute, endMinute, minuteOfDay)
    }
}

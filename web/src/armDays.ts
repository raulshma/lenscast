/**
 * The day-of-week arm-mask helpers — the TS mirror of the server's
 * MotionArmingPolicy: chip order follows the ISO day bits (bit 0 = Monday …
 * bit 6 = Sunday), and the toggle verdict never clears the last armed day
 * (the store clamps a zero mask back up, so a "clear the last day" tap would
 * silently do nothing). Pinned by armDays.test.ts against the Kotlin tests.
 */
/** Bit position for one ISO day index (0 = Monday … 6 = Sunday), clamped like the server's dayBit. */
export function dayBit(isoDayIndex: number): number {
  return 1 << Math.min(Math.max(isoDayIndex, 0), 6)
}

/** Whether [daysMask] arms [isoDayIndex] (0 = Monday … 6 = Sunday). */
export function isDayArmed(daysMask: number, isoDayIndex: number): boolean {
  return (daysMask & dayBit(isoDayIndex)) !== 0
}

/** Flips one day bit but never clears the last armed day — an all-off mask cannot arise. */
export function toggleArmDayMask(daysMask: number, isoDayIndex: number): number {
  const toggled = daysMask ^ dayBit(isoDayIndex)
  return toggled === 0 ? daysMask : toggled
}

/** The TS mirror of the server's MinuteWindow: minutes 0..1439, both bounds
 * inclusive, start > end wraps across midnight. Equal bounds cover nothing —
 * the schedule's degenerate "never restricts" verdict is [isArmed]'s to make. */
function minuteWindowCovers(startMinute: number, endMinute: number, minuteOfDay: number): boolean {
  if (startMinute === endMinute) return false
  return startMinute < endMinute
    ? minuteOfDay >= startMinute && minuteOfDay <= endMinute
    : minuteOfDay >= startMinute || minuteOfDay <= endMinute
}

/** The TS mirror of the server's MotionArmingPolicy.isArmed — the console's
 * armed strip must answer the same question the capture coordinator answers
 * for actual detection, with the same inclusive bounds. Minute-of-day is
 * 0..1439; a window that wraps midnight (start > end) arms across the
 * boundary; start == end means the schedule never restricts anything. */
export function isArmed(
  detectionEnabled: boolean,
  scheduleEnabled: boolean,
  startMinute: number,
  endMinute: number,
  minuteOfDay: number,
  daysMask: number,
  isoDayIndex: number,
): boolean {
  if (!detectionEnabled) return false
  if (!scheduleEnabled) return true
  if (!isDayArmed(daysMask, isoDayIndex)) return false
  if (startMinute === endMinute) return true
  return minuteWindowCovers(startMinute, endMinute, minuteOfDay)
}

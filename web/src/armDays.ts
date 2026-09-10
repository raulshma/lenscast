/**
 * The day-of-week arm-mask helpers — the TS mirror of the server's
 * MotionArmingPolicy: chip order follows the ISO day bits (bit 0 = Monday …
 * bit 6 = Sunday), and the toggle verdict never clears the last armed day
 * (the store clamps a zero mask back up, so a "clear the last day" tap would
 * silently do nothing). Pinned by armDays.test.ts against the Kotlin tests.
 */
export const ARM_DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const

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

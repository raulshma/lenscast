import { describe, expect, it } from 'vitest'
import { dayBit, isDayArmed, toggleArmDayMask } from './armDays'

// Mirrors MotionArmingPolicyTest's day-mask cases so the chip verdict cannot
// drift from the server's clamp behavior.
describe('arm day mask', () => {
  it('day bits follow ISO indices and clamp out-of-range days', () => {
    expect(dayBit(0)).toBe(0b0000001)
    expect(dayBit(6)).toBe(0b1000000)
    expect(dayBit(-3)).toBe(0b0000001)
    expect(dayBit(9)).toBe(0b1000000)
  })

  it('isDayArmed matches the bit under test', () => {
    expect(isDayArmed(0b0000010, 1)).toBe(true)
    expect(isDayArmed(0b0000010, 2)).toBe(false)
  })

  it('the ISO day index space is Monday through Sunday', () => {
    // Chip labels are localized (automation.day.*) — the mask math pins the
    // 0..6 index space itself.
    expect([0, 1, 2, 3, 4, 5, 6].every((day) => isDayArmed(0b1111111, day))).toBe(true)
    expect([0, 1, 2, 3, 4, 5, 6].filter((day) => isDayArmed(0b0011111, day))).toEqual([0, 1, 2, 3, 4])
  })

  it('toggling a day flips its bit but never clears the last armed day', () => {
    const weekdays = 0b0011111 // Mon–Fri
    expect(toggleArmDayMask(weekdays, 5)).toBe(0b0111111)
    expect(toggleArmDayMask(weekdays, 4)).toBe(0b0001111)
    // The last armed day stays on — an all-off mask cannot arise.
    expect(toggleArmDayMask(0b0000001, 0)).toBe(0b0000001)
  })
})

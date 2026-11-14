import { describe, expect, it } from 'vitest'
import { dayBit, isArmed, isDayArmed, toggleArmDayMask } from './armDays'

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

// Mirrors MotionArmingPolicyTest's isArmed cases so the console's armed strip
// answers exactly what the capture coordinator answers for real detection.
describe('arm schedule verdict', () => {
  const armed = (minuteOfDay: number, start = 480, end = 1200, daysMask = 0b1111111, isoDayIndex = 0) =>
    isArmed(true, true, start, end, minuteOfDay, daysMask, isoDayIndex)

  it('disabled detection is never armed', () => {
    expect(isArmed(false, false, 0, 0, 600, 0b1111111, 0)).toBe(false)
  })

  it('no schedule arms around the clock', () => {
    expect(isArmed(true, false, 480, 1200, 60, 0b0000000, 0)).toBe(true)
  })

  it('the window arms inclusively at start and end', () => {
    expect(armed(480)).toBe(true)
    expect(armed(1200)).toBe(true)
    expect(armed(479)).toBe(false)
    expect(armed(1201)).toBe(false)
  })

  it('a wrapping window arms across midnight', () => {
    expect(armed(1380, 1380, 360)).toBe(true) // 23:00
    expect(armed(60, 1380, 360)).toBe(true) // 01:00
    expect(armed(720, 1380, 360)).toBe(false) // noon
  })

  it('equal start and end means always armed', () => {
    expect(armed(60, 600, 600)).toBe(true)
    expect(armed(1200, 600, 600)).toBe(true)
  })

  it('a cleared day is disarmed even inside the window', () => {
    // Tuesday only.
    expect(armed(600, 480, 1200, 0b0000010, 1)).toBe(true)
    expect(armed(600, 480, 1200, 0b0000010, 0)).toBe(false)
  })

  it('the day gate applies even when the window is degenerate', () => {
    expect(isArmed(true, true, 600, 600, 600, 0b0000000, 0)).toBe(false)
  })
})

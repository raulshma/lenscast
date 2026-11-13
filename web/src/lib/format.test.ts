import { describe, it, expect } from 'vitest'
import { formatFileSize, formatDuration } from './format'

describe('formatFileSize', () => {
  it('returns empty for absent sizes', () => {
    expect(formatFileSize(0)).toBe('')
    expect(formatFileSize(-5)).toBe('')
  })

  it('keeps whole bytes undivided', () => {
    expect(formatFileSize(512)).toBe('512 B')
  })

  it('climbs the unit ladder at 1024 boundaries', () => {
    expect(formatFileSize(1024)).toBe('1.0 KB')
    expect(formatFileSize(1536)).toBe('1.5 KB')
    expect(formatFileSize(1024 * 1024)).toBe('1.0 MB')
    expect(formatFileSize(1024 * 1024 * 1024)).toBe('1.0 GB')
  })

  it('stops at GB instead of inventing a unit', () => {
    expect(formatFileSize(2 * 1024 * 1024 * 1024)).toBe('2.0 GB')
  })
})

describe('formatDuration', () => {
  it('pads seconds to two digits', () => {
    expect(formatDuration(0)).toBe('0:00')
    expect(formatDuration(5_000)).toBe('0:05')
  })

  it('carries minutes without padding them', () => {
    expect(formatDuration(90_000)).toBe('1:30')
    expect(formatDuration(3_600_000)).toBe('60:00')
  })

  it('floors partial seconds', () => {
    expect(formatDuration(1_999)).toBe('0:01')
  })
})

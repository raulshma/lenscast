import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSectionedDebouncer } from './sectionedDebounce'

describe('createSectionedDebouncer', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('waits the default 400 ms before saving', () => {
    const debounceSave = createSectionedDebouncer()
    const save = vi.fn()
    debounceSave('camera', save)
    vi.advanceTimersByTime(399)
    expect(save).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(save).toHaveBeenCalledTimes(1)
  })

  it('keeps sections isolated: a streaming edit does not cancel a pending camera save', () => {
    const debounceSave = createSectionedDebouncer()
    const saveCamera = vi.fn()
    const saveStreaming = vi.fn()
    debounceSave('camera', saveCamera)
    vi.advanceTimersByTime(100)
    debounceSave('streaming', saveStreaming)
    vi.advanceTimersByTime(300)
    // Camera's 400 ms elapsed while streaming still has 100 ms pending.
    expect(saveCamera).toHaveBeenCalledTimes(1)
    expect(saveStreaming).not.toHaveBeenCalled()
    vi.advanceTimersByTime(100)
    expect(saveStreaming).toHaveBeenCalledTimes(1)
  })

  it('a same-section edit replaces the pending save instead of stacking it', () => {
    const debounceSave = createSectionedDebouncer()
    const save = vi.fn()
    debounceSave('camera', () => save('first'))
    vi.advanceTimersByTime(300)
    debounceSave('camera', () => save('second'))
    vi.advanceTimersByTime(400)
    expect(save).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledWith('second')
  })

  it('honours a custom delay', () => {
    const debounceSave = createSectionedDebouncer()
    const save = vi.fn()
    debounceSave('streaming', save, 600)
    vi.advanceTimersByTime(400)
    expect(save).not.toHaveBeenCalled()
    vi.advanceTimersByTime(200)
    expect(save).toHaveBeenCalledTimes(1)
  })
})

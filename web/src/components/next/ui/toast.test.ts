import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { pushToast, dismissToast, clearToasts, toastList } from './toast'

describe('toast store', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    clearToasts()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('pushes toasts oldest-first', () => {
    pushToast('info', 'one')
    pushToast('danger', 'two')
    const list = toastList()
    expect(list.map((t) => t.message)).toEqual(['one', 'two'])
    expect(list[1].tone).toBe('danger')
  })

  it('ignores empty messages', () => {
    pushToast('info', '')
    expect(toastList()).toEqual([])
  })

  it('auto-dismisses after the timeout', () => {
    pushToast('success', 'bye', 1000)
    expect(toastList()).toHaveLength(1)
    vi.advanceTimersByTime(1001)
    expect(toastList()[0].exiting).toBe(true)
    vi.advanceTimersByTime(190)
    expect(toastList()).toHaveLength(0)
  })

  it('caps the stack so an error storm cannot pile up', () => {
    for (let i = 0; i < 8; i++) pushToast('warning', `m${i}`)
    const list = toastList()
    expect(list).toHaveLength(4)
    expect(list[0].message).toBe('m4')
    expect(list[3].message).toBe('m7')
  })

  it('dismisses by id through the exit window', () => {
    pushToast('info', 'a')
    const id = toastList()[0].id
    dismissToast(id)
    // The exit holds the toast in place (layout does not collapse)…
    expect(toastList()).toHaveLength(1)
    expect(toastList()[0].exiting).toBe(true)
    // …then the timer removes it once the window has elapsed.
    vi.advanceTimersByTime(190)
    expect(toastList()).toHaveLength(0)
  })

  it('never double-schedules an exiting toast', () => {
    pushToast('info', 'a')
    const id = toastList()[0].id
    dismissToast(id)
    const scheduled = vi.getTimerCount()
    dismissToast(id)
    expect(vi.getTimerCount()).toBe(scheduled)
    vi.advanceTimersByTime(190)
    expect(toastList()).toHaveLength(0)
  })
})

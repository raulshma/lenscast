import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPollLadder } from './pollLadder'

function tally() {
  const calls: string[] = []
  return {
    calls,
    fire: (key: string) => { calls.push(key) },
    count: (key: string) => calls.filter((c) => c === key).length,
  }
}

describe('createPollLadder tick math', () => {
  it('fires a lane exactly on its everyTicks boundary, not before', () => {
    const t = tally()
    const ladder = createPollLadder({ status: { everyTicks: 3 } }, t.fire, { isVisible: () => true })
    ladder.tick()
    ladder.tick()
    expect(t.calls).toEqual([])
    ladder.tick()
    expect(t.calls).toEqual(['status'])
  })

  it('fires lanes at their own rates over a shared tick source', () => {
    const t = tally()
    const ladder = createPollLadder({
      status: { everyTicks: 3 },
      intervalCapture: { everyTicks: 5 },
    }, t.fire, { isVisible: () => true })
    for (let i = 0; i < 30; i++) ladder.tick()
    expect(t.count('status')).toBe(10)
    expect(t.count('intervalCapture')).toBe(6)
  })

  it('fires lanes in declaration order on a shared tick', () => {
    const t = tally()
    const ladder = createPollLadder({
      status: { everyTicks: 2 },
      recording: { everyTicks: 2 },
    }, t.fire, { isVisible: () => true })
    ladder.tick()
    expect(t.calls).toEqual([])
    ladder.tick()
    expect(t.calls).toEqual(['status', 'recording'])
  })
})

describe('createPollLadder visibility gating', () => {
  it('freezes every counter while invisible and resumes where it left off', () => {
    let visible = false
    const t = tally()
    const ladder = createPollLadder({ status: { everyTicks: 3 } }, t.fire, { isVisible: () => visible })
    for (let i = 0; i < 10; i++) ladder.tick()
    expect(t.calls).toEqual([])
    visible = true
    ladder.tick()
    ladder.tick()
    expect(t.calls).toEqual([])
    ladder.tick()
    expect(t.calls).toEqual(['status'])
  })
})

describe('createPollLadder enabled gate', () => {
  it('keeps counting through a closed gate and fires once when it opens', () => {
    let open = false
    const t = tally()
    const ladder = createPollLadder(
      { settings: { everyTicks: 3, enabled: () => open } },
      t.fire,
      { isVisible: () => true },
    )
    for (let i = 0; i < 5; i++) ladder.tick()
    expect(t.calls).toEqual([])
    open = true
    ladder.tick()
    expect(t.calls).toEqual(['settings'])
    ladder.tick()
    ladder.tick()
    expect(t.calls).toEqual(['settings'])
    ladder.tick()
    expect(t.calls).toEqual(['settings', 'settings'])
  })

  it('gates each lane independently', () => {
    let open = false
    const t = tally()
    const ladder = createPollLadder({
      status: { everyTicks: 2 },
      lenses: { everyTicks: 2, enabled: () => open },
    }, t.fire, { isVisible: () => true })
    ladder.tick()
    ladder.tick()
    expect(t.calls).toEqual(['status'])
    open = true
    ladder.tick()
    ladder.tick()
    ladder.tick()
    // ticks 3/4/5: lenses fires immediately (3 accumulated), then both
    // lanes resume their normal 2-tick cadence.
    expect(t.calls).toEqual(['status', 'lenses', 'status', 'lenses'])
  })
})

describe('createPollLadder start/stop', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('drives ticks from the interval and halts on stop', () => {
    vi.useFakeTimers()
    const t = tally()
    const ladder = createPollLadder({ status: { everyTicks: 3 } }, t.fire, {
      isVisible: () => true,
      tickMs: 1000,
    })
    expect(ladder.isRunning()).toBe(false)
    ladder.start()
    expect(ladder.isRunning()).toBe(true)
    vi.advanceTimersByTime(3000)
    expect(t.calls).toEqual(['status'])
    ladder.stop()
    expect(ladder.isRunning()).toBe(false)
    vi.advanceTimersByTime(10_000)
    expect(t.calls).toEqual(['status'])
  })

  it('ignores repeated start calls', () => {
    vi.useFakeTimers()
    const t = tally()
    const ladder = createPollLadder({ status: { everyTicks: 1 } }, t.fire, {
      isVisible: () => true,
      tickMs: 1000,
    })
    ladder.start()
    ladder.start()
    vi.advanceTimersByTime(1000)
    expect(t.calls).toEqual(['status'])
  })
})

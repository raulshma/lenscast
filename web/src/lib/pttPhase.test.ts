import { describe, expect, it } from 'vitest'
import { type PttEvent, type PttPhase, pttBusy, pttTransition } from './pttPhase'

const PHASES: PttPhase[] = ['idle', 'arming', 'holding', 'draining']
const EVENTS: PttEvent[] = ['press', 'audioUp', 'release', 'drained', 'teardown']

describe('pttBusy', () => {
  it('reads true exactly on the press-latch phases {arming, holding, draining}', () => {
    expect(pttBusy('idle')).toBe(false)
    expect(pttBusy('arming')).toBe(true)
    expect(pttBusy('holding')).toBe(true)
    expect(pttBusy('draining')).toBe(true)
  })
})

describe('pttTransition', () => {
  it('press arms only from idle — ignored while the hold latch is busy', () => {
    expect(pttTransition('idle', 'press')).toBe('arming')
    expect(pttTransition('arming', 'press')).toBe('arming')
    expect(pttTransition('holding', 'press')).toBe('holding')
    expect(pttTransition('draining', 'press')).toBe('draining')
  })

  it('audioUp holds only from arming — a stale graph-up must not disturb other phases', () => {
    expect(pttTransition('arming', 'audioUp')).toBe('holding')
    expect(pttTransition('idle', 'audioUp')).toBe('idle')
    expect(pttTransition('holding', 'audioUp')).toBe('holding')
    expect(pttTransition('draining', 'audioUp')).toBe('draining')
  })

  it('release drains from arming and holding; from idle it is a no-op and re-release during draining stays draining', () => {
    expect(pttTransition('arming', 'release')).toBe('draining')
    expect(pttTransition('holding', 'release')).toBe('draining')
    expect(pttTransition('idle', 'release')).toBe('idle')
    expect(pttTransition('draining', 'release')).toBe('draining')
  })

  it('drained idles only from draining — teardown and holds are immune', () => {
    expect(pttTransition('draining', 'drained')).toBe('idle')
    expect(pttTransition('idle', 'drained')).toBe('idle')
    expect(pttTransition('arming', 'drained')).toBe('arming')
    expect(pttTransition('holding', 'drained')).toBe('holding')
  })

  it('teardown (unmount) lands idle from anywhere', () => {
    for (const phase of PHASES) expect(pttTransition(phase, 'teardown')).toBe('idle')
  })

  it('covers the full event × phase matrix', () => {
    const expected: Record<PttPhase, Record<PttEvent, PttPhase>> = {
      idle: { press: 'arming', audioUp: 'idle', release: 'idle', drained: 'idle', teardown: 'idle' },
      arming: { press: 'arming', audioUp: 'holding', release: 'draining', drained: 'arming', teardown: 'idle' },
      holding: { press: 'holding', audioUp: 'holding', release: 'draining', drained: 'holding', teardown: 'idle' },
      draining: { press: 'draining', audioUp: 'draining', release: 'draining', drained: 'idle', teardown: 'idle' },
    }
    for (const phase of PHASES) {
      for (const event of EVENTS) {
        expect(pttTransition(phase, event)).toBe(expected[phase][event])
      }
    }
  })
})

import { describe, expect, it } from 'vitest'
import { createUiMode, otherUiMode, parseUiMode } from './uiMode'

describe('parseUiMode', () => {
  it('defaults to classic for null, empty, and unknown values', () => {
    expect(parseUiMode(null)).toBe('classic')
    expect(parseUiMode('')).toBe('classic')
    expect(parseUiMode('classic')).toBe('classic')
    expect(parseUiMode('garbage')).toBe('classic')
  })

  it('parses the stored next mode', () => {
    expect(parseUiMode('next')).toBe('next')
  })
})

describe('otherUiMode', () => {
  it('swaps the modes both ways', () => {
    expect(otherUiMode('classic')).toBe('next')
    expect(otherUiMode('next')).toBe('classic')
  })
})

describe('createUiMode', () => {
  it('reads the initial mode from the injected store', () => {
    const store = createUiMode({ get: () => 'next', set: () => {} })
    expect(store.mode()).toBe('next')
  })

  it('persists set() and updates the signal', () => {
    const written: string[] = []
    const store = createUiMode({ get: () => null, set: (v) => written.push(v) })
    store.set('next')
    expect(store.mode()).toBe('next')
    expect(written).toEqual(['next'])
    store.set('classic')
    expect(store.mode()).toBe('classic')
    expect(written).toEqual(['next', 'classic'])
  })

  it('toggle flips the mode and persists it', () => {
    const written: string[] = []
    const store = createUiMode({ get: () => null, set: (v) => written.push(v) })
    store.toggle()
    expect(store.mode()).toBe('next')
    expect(written).toEqual(['next'])
    store.toggle()
    expect(store.mode()).toBe('classic')
    expect(written).toEqual(['next', 'classic'])
  })

  it('keeps the in-memory switch working when storage throws', () => {
    const store = createUiMode({
      get: () => null,
      set: () => {
        throw new Error('quota exceeded')
      },
    })
    store.set('next')
    expect(store.mode()).toBe('next')
  })
})

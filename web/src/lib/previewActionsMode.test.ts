import { describe, expect, it } from 'vitest'
import {
  createPreviewActionsMode,
  DEFAULT_PREVIEW_ACTIONS_MODE,
  otherPreviewActionsMode,
  parsePreviewActionsMode,
} from './previewActionsMode'

describe('previewActionsMode', () => {
  it('defaults to compact for null and unknown stored values', () => {
    expect(DEFAULT_PREVIEW_ACTIONS_MODE).toBe('compact')
    expect(parsePreviewActionsMode(null)).toBe('compact')
    expect(parsePreviewActionsMode('')).toBe('compact')
    expect(parsePreviewActionsMode('unknown')).toBe('compact')
    expect(parsePreviewActionsMode('full')).toBe('full')
  })

  it('otherPreviewActionsMode flips between compact and full', () => {
    expect(otherPreviewActionsMode('compact')).toBe('full')
    expect(otherPreviewActionsMode('full')).toBe('compact')
  })

  it('persists toggle and set calls via deps', () => {
    let saved: string | null = null
    const store = createPreviewActionsMode({
      get: () => saved,
      set: (value) => { saved = value },
    })

    expect(store.mode()).toBe('compact')
    store.toggle()
    expect(store.mode()).toBe('full')
    expect(saved).toBe('full')

    store.toggle()
    expect(store.mode()).toBe('compact')
    expect(saved).toBe('compact')

    store.set('full')
    expect(store.mode()).toBe('full')
    expect(saved).toBe('full')
  })

  it('keeps working in memory when storage throws', () => {
    const store = createPreviewActionsMode({
      get: () => null,
      set: () => { throw new Error('quota exceeded') },
    })

    expect(store.mode()).toBe('compact')
    store.toggle()
    expect(store.mode()).toBe('full')
  })
})

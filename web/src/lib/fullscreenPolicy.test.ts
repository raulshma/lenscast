import { describe, expect, it } from 'vitest'
import {
  chooseMode,
  detectSupport,
  escapeVerdict,
  hintKey,
  shouldReacquire,
  wakeLockVerdict,
  type FullscreenSupport,
} from './fullscreenPolicy'

function support(
  overrides: Partial<{ requestFullscreen: boolean; wakeLock: boolean; secureContext: boolean; cssOverlay: boolean }> = {},
): FullscreenSupport {
  return detectSupport({
    requestFullscreen: true,
    wakeLock: true,
    secureContext: true,
    cssOverlay: true,
    ...overrides,
  })
}

describe('detectSupport', () => {
  it('folds the raw probes into the support record', () => {
    expect(
      detectSupport({ requestFullscreen: false, wakeLock: true, secureContext: false, cssOverlay: true }),
    ).toEqual({ nativeFullscreen: false, wakeLock: true, secureContext: false, cssOverlay: true })
  })
})

describe('chooseMode', () => {
  it('takes native when the API exists and has not been rejected', () => {
    expect(chooseMode(support(), false)).toBe('native')
  })

  it('falls back to the CSS overlay when a native request was rejected (iOS Safari)', () => {
    expect(chooseMode(support(), true)).toBe('css')
  })

  it('falls back to the CSS overlay when there is no native API', () => {
    expect(chooseMode(support({ requestFullscreen: false }), false)).toBe('css')
  })

  it('is unavailable only when even the overlay is gated off', () => {
    expect(chooseMode(support({ requestFullscreen: false, cssOverlay: false }), false)).toBe('unavailable')
  })
})

describe('wakeLockVerdict — fullscreen-scoped only', () => {
  it('acquires while fullscreen AND live AND capable', () => {
    expect(wakeLockVerdict(support(), true, true)).toBe('acquire')
  })

  it('never acquires for an active stream without fullscreen (the load-bearing pin)', () => {
    expect(wakeLockVerdict(support(), false, true)).toBe('release')
  })

  it('releases when the stream stops while fullscreen', () => {
    expect(wakeLockVerdict(support(), true, false)).toBe('release')
  })

  it('no-ops in an insecure context even while fullscreen and live', () => {
    expect(wakeLockVerdict(support({ secureContext: false }), true, true)).toBe('noop')
  })

  it('no-ops when the wake-lock API is absent', () => {
    expect(wakeLockVerdict(support({ wakeLock: false }), true, true)).toBe('noop')
  })
})

describe('shouldReacquire on visibility regain', () => {
  it('re-acquires when visible and the acquire verdict holds', () => {
    expect(shouldReacquire(support(), true, true, true)).toBe(true)
  })

  it('does not re-acquire while the tab is hidden', () => {
    expect(shouldReacquire(support(), true, true, false)).toBe(false)
  })

  it('does not re-acquire without fullscreen', () => {
    expect(shouldReacquire(support(), false, true, true)).toBe(false)
  })

  it('does not re-acquire in an insecure context', () => {
    expect(shouldReacquire(support({ secureContext: false }), true, true, true)).toBe(false)
  })
})

describe('escapeVerdict — ownership', () => {
  it('native fullscreen leaves Escape to the browser', () => {
    expect(escapeVerdict('native', false)).toBe('noop')
    expect(escapeVerdict('native', true)).toBe('noop')
  })

  it('CSS overlay exits on Escape', () => {
    expect(escapeVerdict('css', false)).toBe('exit')
  })

  it('CSS overlay defers to a higher overlay that owns Escape', () => {
    expect(escapeVerdict('css', true)).toBe('defer')
  })
})

describe('hintKey', () => {
  it('explains a browser that cannot offer fullscreen at all', () => {
    expect(hintKey('unavailable', support(), false)).toBe('preview.fullscreenUnavailable')
  })

  it('explains the wake lock blocked by an insecure context once fullscreen is up', () => {
    expect(hintKey('native', support({ secureContext: false }), true)).toBe('preview.fullscreenNeedsHttps')
  })

  it('stays silent when the wake lock can hold the screen', () => {
    expect(hintKey('native', support(), true)).toBeNull()
  })

  it('degrades silently when the wake-lock API is simply absent', () => {
    expect(hintKey('css', support({ wakeLock: false }), true)).toBeNull()
  })

  it('stays silent while not fullscreen', () => {
    expect(hintKey('native', support({ secureContext: false }), false)).toBeNull()
  })
})
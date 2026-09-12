import { describe, expect, it, vi } from 'vitest'
import { createRoot } from 'solid-js'
import { createHashRouter, formatHash, parseHash, sameRoute, type HashRouterDeps } from './router'

describe('parseHash', () => {
  it('parses every route shape', () => {
    expect(parseHash('#/')).toEqual({ name: 'dashboard' })
    expect(parseHash('')).toEqual({ name: 'dashboard' })
    expect(parseHash('#')).toEqual({ name: 'dashboard' })
    expect(parseHash('#/settings')).toEqual({ name: 'settings' })
    expect(parseHash('#/events')).toEqual({ name: 'events' })
    expect(parseHash('#/gallery')).toEqual({ name: 'gallery' })
    expect(parseHash('#/gallery/abc-123')).toEqual({ name: 'gallery', mediaId: 'abc-123' })
  })

  it('URL-decodes the gallery mediaId', () => {
    expect(parseHash('#/gallery/clip%207')).toEqual({ name: 'gallery', mediaId: 'clip 7' })
  })

  it('treats unknown hashes as the dashboard default — nothing wedges the app', () => {
    expect(parseHash('#/nonsense')).toEqual({ name: 'dashboard' })
    expect(parseHash('#/events/extra')).toEqual({ name: 'dashboard' })
  })

  it('a bare #/gallery/ (trailing slash, no id) is the id-less gallery route', () => {
    expect(parseHash('#/gallery/')).toEqual({ name: 'gallery' })
  })
})

describe('formatHash', () => {
  it('is the canonical round-trip of parseHash', () => {
    expect(formatHash({ name: 'dashboard' })).toBe('#/')
    expect(formatHash({ name: 'settings' })).toBe('#/settings')
    expect(formatHash({ name: 'events' })).toBe('#/events')
    expect(formatHash({ name: 'gallery' })).toBe('#/gallery')
    expect(formatHash({ name: 'gallery', mediaId: 'clip 7' })).toBe('#/gallery/clip%207')
    expect(parseHash(formatHash({ name: 'gallery', mediaId: 'x/y' }))).toEqual({ name: 'gallery', mediaId: 'x/y' })
  })
})

describe('sameRoute', () => {
  it('treats an absent mediaId and an undefined one as identical', () => {
    expect(sameRoute({ name: 'gallery' }, { name: 'gallery', mediaId: undefined })).toBe(true)
    expect(sameRoute({ name: 'gallery' }, { name: 'gallery', mediaId: 'x' })).toBe(false)
    expect(sameRoute({ name: 'dashboard' }, { name: 'events' })).toBe(false)
  })
})

describe('createHashRouter', () => {
  function makeDeps() {
    const listeners: Array<() => void> = []
    const deps: HashRouterDeps = {
      getHash: () => '#/',
      pushHash: vi.fn(),
      onHashChange: (cb) => {
        listeners.push(cb)
        return () => {
          listeners.splice(listeners.indexOf(cb), 1)
        }
      },
    }
    return { deps, listeners }
  }

  it('starts at the current hash and follows external hash changes', () => {
    const { deps, listeners } = makeDeps()
    deps.getHash = () => '#/events'
    createRoot((dispose) => {
      const router = createHashRouter(deps)
      expect(router.route()).toEqual({ name: 'events' })
      // Back/forward or a notification click: the callback re-parses.
      deps.getHash = () => '#/gallery/clip1'
      listeners.forEach((cb) => cb())
      expect(router.route()).toEqual({ name: 'gallery', mediaId: 'clip1' })
      dispose()
    })
  })

  it('navigate pushes the formatted hash and updates the signal in one hop', () => {
    const { deps } = makeDeps()
    createRoot((dispose) => {
      const router = createHashRouter(deps)
      router.navigate({ name: 'gallery', mediaId: 'x' })
      expect(deps.pushHash).toHaveBeenCalledWith('#/gallery/x')
      expect(router.route()).toEqual({ name: 'gallery', mediaId: 'x' })
      dispose()
    })
  })

  it('navigate to the current route is a no-op — the state↔hash pair cannot loop', () => {
    const { deps } = makeDeps()
    createRoot((dispose) => {
      deps.getHash = () => '#/settings'
      const router = createHashRouter(deps)
      router.navigate({ name: 'settings' })
      expect(deps.pushHash).not.toHaveBeenCalled()
      dispose()
    })
  })
})

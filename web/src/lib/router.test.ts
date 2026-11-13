import { describe, expect, it, vi } from 'vitest'
import { createRoot } from 'solid-js'
import { createHashRouter, formatHash, isNextRoute, isSystemTab, parseHash, sameRoute, toNextRoute, type HashRouterDeps } from './router'

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

  it('parses the next-UI view routes', () => {
    expect(parseHash('#/live')).toEqual({ name: 'live' })
    expect(parseHash('#/media')).toEqual({ name: 'media' })
    expect(parseHash('#/detection')).toEqual({ name: 'detection' })
    expect(parseHash('#/system')).toEqual({ name: 'system' })
    expect(parseHash('#/media/clip%207')).toEqual({ name: 'media', mediaId: 'clip 7' })
    expect(parseHash('#/media/')).toEqual({ name: 'media' })
  })

  it('parses system sub-tabs; a bare #/system/ (trailing slash) is the id-less route', () => {
    expect(parseHash('#/system/camera')).toEqual({ name: 'system', tab: 'camera' })
    expect(parseHash('#/system/links')).toEqual({ name: 'system', tab: 'links' })
    expect(parseHash('#/system/')).toEqual({ name: 'system' })
  })
})

describe('isSystemTab', () => {
  it('accepts exactly the declared tabs', () => {
    expect(isSystemTab('camera')).toBe(true)
    expect(isSystemTab('capture')).toBe(true)
    expect(isSystemTab('streaming')).toBe(true)
    expect(isSystemTab('storage')).toBe(true)
    expect(isSystemTab('links')).toBe(true)
    expect(isSystemTab('admin')).toBe(true)
    expect(isSystemTab('nonsense')).toBe(false)
    expect(isSystemTab(undefined)).toBe(false)
  })
})

describe('isNextRoute / toNextRoute', () => {
  it('classifies the next-UI names', () => {
    expect(isNextRoute('live')).toBe(true)
    expect(isNextRoute('media')).toBe(true)
    expect(isNextRoute('detection')).toBe(true)
    expect(isNextRoute('system')).toBe(true)
    expect(isNextRoute('dashboard')).toBe(false)
    expect(isNextRoute('gallery')).toBe(false)
  })

  it('maps every legacy route onto its next-UI equivalent', () => {
    expect(toNextRoute({ name: 'dashboard' })).toEqual({ name: 'live' })
    expect(toNextRoute({ name: 'settings' })).toEqual({ name: 'system' })
    expect(toNextRoute({ name: 'events' })).toEqual({ name: 'detection' })
    expect(toNextRoute({ name: 'gallery' })).toEqual({ name: 'media' })
    expect(toNextRoute({ name: 'gallery', mediaId: 'x1' })).toEqual({ name: 'media', mediaId: 'x1' })
  })

  it('passes next-UI routes through unchanged', () => {
    expect(toNextRoute({ name: 'live' })).toEqual({ name: 'live' })
    expect(toNextRoute({ name: 'media', mediaId: 'x1' })).toEqual({ name: 'media', mediaId: 'x1' })
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

  it('round-trips the next-UI view routes', () => {
    expect(formatHash({ name: 'live' })).toBe('#/live')
    expect(formatHash({ name: 'media' })).toBe('#/media')
    expect(formatHash({ name: 'media', mediaId: 'clip 7' })).toBe('#/media/clip%207')
    expect(parseHash(formatHash({ name: 'media', mediaId: 'x/y' }))).toEqual({ name: 'media', mediaId: 'x/y' })
    expect(formatHash({ name: 'detection' })).toBe('#/detection')
    expect(formatHash({ name: 'system' })).toBe('#/system')
  })

  it('round-trips system tabs; an invalid tab falls back to the bare system route', () => {
    for (const tab of ['camera', 'capture', 'streaming', 'storage', 'links', 'admin'] as const) {
      const route = { name: 'system' as const, tab }
      expect(formatHash(route)).toBe(`#/system/${tab}`)
      expect(parseHash(formatHash(route))).toEqual(route)
    }
    expect(formatHash({ name: 'system', tab: 'nonsense' })).toBe('#/system')
    expect(formatHash({ name: 'system', tab: undefined })).toBe('#/system')
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

import { createSignal } from 'solid-js'

// Tiny hash router — the pollLadder pattern: parsing/formatting is pure so
// vitest pins every route shape, and the browser primitives (location,
// hashchange) arrive as injected dependencies behind a store factory. Routes:
//
// Classic UI (legacy space, unchanged behavior):
//   #/                  dashboard (tabs behave exactly as before)
//   #/settings          dashboard, App tab (settings cards live there)
//   #/events            dashboard, App tab, events feed scrolled into view
//   #/gallery           gallery overlay open
//   #/gallery/<mediaId> gallery overlay open + the media viewer at that id
//
// Next UI (opt-in redesign, one route per view):
//   #/live              live view
//   #/media[/<mediaId>] media view (+ the media viewer at that id)
//   #/detection         detection view
//   #/system[/<tab>]    system view, one sub-tab of settings
//                       (camera | capture | streaming | storage | links | admin)

export type RouteName =
  | 'dashboard'
  | 'settings'
  | 'events'
  | 'gallery'
  | 'live'
  | 'media'
  | 'detection'
  | 'system'

export interface Route {
  name: RouteName
  /** Present only on #/gallery/<id> and #/media/<id>: the media for the viewer. */
  mediaId?: string
  /** Present only on #/system/<tab>: which settings sub-tab is open. */
  tab?: string
}

const GALLERY_PREFIX = '#/gallery/'
const MEDIA_PREFIX = '#/media/'
const SYSTEM_PREFIX = 'system/'

/** The System view's sub-tabs, in display order; `admin` is rendered only for admins. */
export const SYSTEM_TABS = ['camera', 'capture', 'streaming', 'storage', 'links', 'admin'] as const

export type SystemTab = (typeof SYSTEM_TABS)[number]

export function isSystemTab(tab: string | undefined): tab is SystemTab {
  return tab != null && (SYSTEM_TABS as readonly string[]).includes(tab)
}

/** The route names the next (redesigned) shell understands. */
const NEXT_ROUTE_NAMES: readonly RouteName[] = ['live', 'media', 'detection', 'system']

export function isNextRoute(name: RouteName): boolean {
  return NEXT_ROUTE_NAMES.includes(name)
}

/**
 * The next-UI equivalent of any route — the legacy names redirect one-to-one
 * so old bookmarks and notification deep links keep working after the switch.
 */
export function toNextRoute(route: Route): Route {
  switch (route.name) {
    case 'dashboard':
      return { name: 'live' }
    case 'settings':
      return { name: 'system' }
    case 'events':
      return { name: 'detection' }
    case 'gallery':
      return route.mediaId ? { name: 'media', mediaId: route.mediaId } : { name: 'media' }
    default:
      return route
  }
}

/**
 * Any hash that is not a known route (including '' and '#') is the dashboard
 * default — an old bookmark or a hand-typed path can never wedge the app.
 */
export function parseHash(hash: string): Route {
  const trimmed = hash.replace(/^#\/?/, '')
  if (trimmed === '') return { name: 'dashboard' }
  if (trimmed === 'settings') return { name: 'settings' }
  if (trimmed === 'events') return { name: 'events' }
  if (trimmed === 'gallery') return { name: 'gallery' }
  if (trimmed === 'live') return { name: 'live' }
  if (trimmed === 'media') return { name: 'media' }
  if (trimmed === 'detection') return { name: 'detection' }
  if (trimmed === 'system') return { name: 'system' }
  if (trimmed.startsWith('gallery/')) {
    const mediaId = decodeURIComponent(trimmed.slice('gallery/'.length))
    return mediaId ? { name: 'gallery', mediaId } : { name: 'gallery' }
  }
  if (trimmed.startsWith('media/')) {
    const mediaId = decodeURIComponent(trimmed.slice('media/'.length))
    return mediaId ? { name: 'media', mediaId } : { name: 'media' }
  }
  if (trimmed.startsWith(SYSTEM_PREFIX)) {
    const tab = decodeURIComponent(trimmed.slice(SYSTEM_PREFIX.length))
    return tab ? { name: 'system', tab } : { name: 'system' }
  }
  return { name: 'dashboard' }
}

/** The canonical hash for a route; the round-trip of parseHash. */
export function formatHash(route: Route): string {
  if (route.name === 'gallery' && route.mediaId) {
    return `${GALLERY_PREFIX}${encodeURIComponent(route.mediaId)}`
  }
  if (route.name === 'media' && route.mediaId) {
    return `${MEDIA_PREFIX}${encodeURIComponent(route.mediaId)}`
  }
  if (route.name === 'media') return '#/media'
  if (route.name === 'gallery') return '#/gallery'
  if (route.name === 'settings') return '#/settings'
  if (route.name === 'events') return '#/events'
  if (route.name === 'live') return '#/live'
  if (route.name === 'detection') return '#/detection'
  if (route.name === 'system') {
    return isSystemTab(route.tab) ? `#/system/${route.tab}` : '#/system'
  }
  return '#/'
}

/**
 * True when two routes describe the same application state — a mediaId-less
 * gallery route and one without the field are identical.
 */
export function sameRoute(a: Route, b: Route): boolean {
  return formatHash(a) === formatHash(b)
}

export interface HashRouterDeps {
  getHash(): string
  /** Push a new hash (history entry) — must not recurse into the caller. */
  pushHash(hash: string): void
  /** Subscribe to external hash changes (browser back/forward, links). */
  onHashChange(callback: () => void): () => void
}

export interface HashRouter {
  /** The current route, as a reactive signal. */
  route(): Route
  /** Navigate when the target differs from the current route (no-op loop guard). */
  navigate(route: Route): void
}

/**
 * The reactive half of the router over injected primitives: `route` tracks
 * every hash source (navigate here, the browser's back/forward, a typed link,
 * a notification click), and `navigate` only writes when the formatted hash
 * actually changes, so the state→hash→state feedback settles in one hop.
 */
export function createHashRouter(deps: HashRouterDeps): HashRouter {
  const [route, setRoute] = createSignal<Route>(parseHash(deps.getHash()))

  deps.onHashChange(() => setRoute(parseHash(deps.getHash())))

  return {
    route,
    navigate(next: Route) {
      if (sameRoute(next, route())) return
      deps.pushHash(formatHash(next))
      setRoute(next)
    },
  }
}

// ── Browser singleton ──

function browserRouter(): HashRouter {
  return createHashRouter({
    getHash: () => (typeof location !== 'undefined' ? location.hash : ''),
    pushHash: (hash) => {
      if (typeof location !== 'undefined') location.hash = hash
    },
    onHashChange: (callback) => {
      if (typeof window === 'undefined') return () => { }
      window.addEventListener('hashchange', callback)
      return () => window.removeEventListener('hashchange', callback)
    },
  })
}

let singleton: HashRouter | null = null

/** The app-wide router; created lazily so importing in node tests is safe. */
export function hashRouter(): HashRouter {
  if (!singleton) singleton = browserRouter()
  return singleton
}

/**
 * Dev-only smoke harness for the whole dashboard: mounts the real App with a
 * stubbed window.fetch serving the contract fixtures, a fake EventSource (so
 * the poll lane drives the UI), and query-param presets for the UI mode and
 * theme. Open it on the vite dev server at /src/smoke/dashboard.html — no
 * device needed. URL params:
 *
 *   ?ui=next|classic  persisted UI mode (default classic)
 *   ?theme=dark|light persisted next-UI theme (default dark)
 *   ?view=live|media|detection|system   starting hash route
 *
 * Not part of the build (only index.html is an entry) or the test run.
 */
import { render } from 'solid-js/web'
import type { AllSettings, AutomationStateDto, DeviceStatus } from '../types'
import settingsFixture from '../../contract/settings.json'
import statusFixture from '../../contract/status.json'
import lensesFixture from '../../contract/lenses.json'
import galleryFixture from '../../contract/gallery.json'
import detectionEventsFixture from '../../contract/detection-events.json'
import detectionStatsFixture from '../../contract/detection-stats.json'
import automationFixture from '../../contract/automation.json'
import auditFixture from '../../contract/audit-log.json'
import authConfigFixture from '../../contract/auth-config.json'
import systemFixture from '../../contract/system.json'
import recordingStatusFixture from '../../contract/recording-status.json'
import recordingSessionsFixture from '../../contract/recording-sessions.json'
import intervalStatusFixture from '../../contract/interval-capture-status.json'
import App from '../App'
import '../App.css'
// The real entry loads the brand faces (index.tsx); the harness needs the
// same @fontsource imports or screenshots render in fallback system fonts.
import '@fontsource/inter/latin-300.css'
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-700.css'
import '@fontsource/inter/cyrillic-400.css'
import '@fontsource/inter/cyrillic-600.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-600.css'
import '@fontsource/golos-text/latin-400.css'
import '@fontsource/golos-text/latin-500.css'
import '@fontsource/golos-text/latin-600.css'
import '@fontsource/golos-text/cyrillic-400.css'
import '@fontsource/golos-text/cyrillic-600.css'

// ── Query-param presets (before any store reads localStorage) ──
const params = new URLSearchParams(window.location.search)
const ui = params.get('ui') === 'classic' ? 'classic' : params.get('ui') === 'next' ? 'next' : 'classic'
const nextTheme = params.get('theme') === 'dark' ? 'dark' : params.get('theme') === 'light' ? 'light' : 'dark'
const startView = params.get('view') ?? 'live'
const requireAuth = params.get('auth') === '1'
try {
  localStorage.setItem('lenscast.ui', ui)
  localStorage.setItem('lenscast.nextTheme', nextTheme)
} catch { /* private mode */ }
// ?view= presets the route, but an explicit hash (deep-link screenshots)
// always wins — the harness must not clobber what it is meant to render.
if (!window.location.hash) {
  window.location.hash = startView === 'live' || startView === 'media' || startView === 'detection' || startView === 'system'
    ? `#/${startView}`
    : '#/live'
}

// ── Mutable in-memory state ──
const settings = structuredClone(settingsFixture) as unknown as AllSettings
const status = structuredClone(statusFixture) as unknown as DeviceStatus

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const realFetch = window.fetch.bind(window)
window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const method = (init?.method ?? 'GET').toUpperCase()
  const route = (fragment: string) => url.includes(fragment)

  // Writes fold into the in-memory state so the UI observes its own edits.
  if (route('/api/settings') && method === 'PUT' && init?.body) {
    const body = JSON.parse(String(init.body)) as Partial<AllSettings>
    if (body.camera) settings.camera = { ...settings.camera, ...body.camera }
    if (body.streaming) settings.streaming = { ...settings.streaming, ...body.streaming }
    await new Promise((resolve) => setTimeout(resolve, 150))
    return json({ success: true })
  }
  if (route('/api/automation') && method === 'PUT' && init?.body) {
    const body = JSON.parse(String(init.body)) as Pick<AutomationStateDto, 'enabled' | 'rules'>
    const automation = automationFixture as unknown as AutomationStateDto
    automation.enabled = body.enabled
    automation.rules = body.rules
    await new Promise((resolve) => setTimeout(resolve, 150))
    return json(JSON.parse(JSON.stringify(automation)))
  }

  // Stream start/stop flips the live status so the UI reflects the action.
  const streamAction = url.match(/\/api\/stream\/(web|rtsp|whip|rtmp)\/(start|stop)/)
  if (streamAction) {
    const [, lane, verb] = streamAction
    const activeKey = lane === 'web' ? 'webStreamingActive' : `${lane}Active`
    ;(status.streaming as unknown as Record<string, unknown>)[activeKey] = verb === 'start'
    status.streaming.isActive = status.streaming.webStreamingActive || status.streaming.rtspStreamingActive
    return json({ success: true })
  }

  // Reads → contract fixtures.
  if (route('/api/auth/status')) return json({ required: requireAuth })
  if (route('/api/auth/session')) return json({ authenticated: false, role: 'admin' })
  if (route('/api/auth/login')) {
    if (requireAuth) {
      await new Promise((resolve) => setTimeout(resolve, 300))
      return json({ success: true, role: 'admin' })
    }
    return json({ success: true, role: 'admin' })
  }
  if (route('/api/auth/config')) return json(authConfigFixture)
  if (route('/api/auth/sessions')) return json({ sessions: [] })
  if (route('/api/status')) return json(JSON.parse(JSON.stringify(status)))
  if (route('/api/settings')) return json(JSON.parse(JSON.stringify(settings)))
  if (route('/api/camera/lenses')) return json(lensesFixture)
  if (route('/api/recording/status')) return json(recordingStatusFixture)
  if (route('/api/recordings/sessions')) return json(recordingSessionsFixture)
  if (route('/api/capture/interval/status')) return json(intervalStatusFixture)
  if (route('/api/stream/clients')) {
    return json({
      httpClients: ['192.168.1.23', '192.168.1.42'],
      httpCount: 2,
      rtspCount: 1,
      maxHttp: 8,
      rtspClients: [],
    })
  }
  if (route('/api/gallery')) return json(galleryFixture)
  if (route('/api/detection/events')) return json(detectionEventsFixture)
  if (route('/api/detection/stats')) {
    // The contract fixture's perDay dates are fixed in time, so the seven-day
    // chart zero-fills to an all-quiet week whenever the harness runs later
    // than the fixture was written. Anchor the series to today instead — a
    // fixed shape (one quiet day) whose total still matches last7d (30).
    const counts = [4, 6, 0, 2, 7, 3, 8]
    const now = new Date()
    const perDay = counts.map((count, i) => {
      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (counts.length - 1 - i))
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      return { day: key, count }
    })
    return json({ ...structuredClone(detectionStatsFixture), perDay })
  }
  if (route('/api/audit')) return json(auditFixture)
  if (route('/api/system')) return json(systemFixture)
  if (route('/api/automation')) return json(JSON.parse(JSON.stringify(automationFixture)))
  if (route('/api/push/vapid-public')) return json({ publicKey: 'BP smoke placeholder key value not-real-000000000000000' })
  if (route('/api/push/subscriptions')) return json({ subscriptions: [], count: 0 })
  if (route('/api/push/unifiedpush')) return json({ targets: [] })

  // Everything else (streams, snapshots) falls through to the network and
  // fails like it would against a powered-off device — the player ladder
  // shows its placeholder rung, which is exactly what we want to style.
  return realFetch(input, init)
}

// Fake EventSource: never opens (readyState stays CONNECTING), so the SSE
// lane reports dead and the poll ladder drives every lane from the stubs —
// deterministic and device-free. onerror/addEventListener are no-ops.
class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readyState = 0
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: (() => void) | null = null
  constructor(_url: string) { /* stays connecting forever */ }
  addEventListener(_type: string, _listener: () => void) { /* no events arrive */ }
  removeEventListener() { /* noop */ }
  close() { this.readyState = 2 }
}
// eslint-disable-next-line @typescript-eslint/no-explicit-any
;(window as any).EventSource = FakeEventSource

render(() => <App />, document.getElementById('root')!)

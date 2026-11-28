// Minimal mock of the LensCast device HTTP API, enough for the beta
// (next) web UI to boot into the Live view with the Streaming & Links
// inspector preset. No dependencies — plain node:http. Unknown routes 404
// loudly (`{"error":"not mocked: …"}`) so a boot gap is visible, not silent.
//
//   node mock-api.cjs                 # :8099
//   MOCK_PORT=8091 node mock-api.cjs  # any port
//
// Point the vite dev server at it with DEV_API_ORIGIN (see web/vite.config.ts)
// — deliberately NOT :8080, the device's real server port, so a stale local
// process can never be mistaken for this mock (a deleted-source mock once
// squatted on :8080 for days).
const http = require('node:http')

const SECTION_IDS = ['channels', 'web-stream', 'audio', 'rtsp', 'whip', 'rtmp', 'clients', 'share']

const layoutMemo = { widthPx: 340 }

const layout = () => ({
  schemaVersion: 1,
  updatedAtMs: 0,
  activePresetId: 'streaming-links',
  column: { widthPx: layoutMemo.widthPx, widthPreset: 'custom', collapsed: false },
  sections: SECTION_IDS.map((sectionId) => ({
    id: sectionId,
    kind: 'builtin',
    sectionId,
    visible: true,
    collapsed: false,
    hiddenItems: [],
    customTitle: null,
    items: [],
  })),
  presets: [],
})

const status = {
  streaming: {
    isActive: false,
    url: '',
    webStreamingEnabled: true,
    webStreamingActive: true,
    clientCount: 1,
    audioEnabled: false,
    audioUrl: '',
    rtspEnabled: true,
    rtspStreamingActive: false,
    rtspUrl: '',
    rtmpEnabled: false,
    rtmpActive: false,
    whipEnabled: false,
    whipActive: false,
    hlsActive: false,
  },
  illuminanceLux: 180,
  lightSensorAvailable: true,
}

const settings = { camera: {}, streaming: { showIlluminance: false } }

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  const path = url.pathname
  const json = (body, code = 200) => {
    res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    res.end(JSON.stringify(body))
  }

  if (path === '/api/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-store',
      Connection: 'keep-alive',
    })
    res.write(': connected\n\n')
    const timer = setInterval(() => res.write(': keepalive\n\n'), 15000)
    req.on('close', () => clearInterval(timer))
    return
  }

  if (req.method === 'GET' && path === '/api/auth/status') return json({ required: false })
  if (req.method === 'GET' && path === '/api/auth/session') return json({ authenticated: true, role: 'admin' })
  if (req.method === 'GET' && path === '/api/settings') return json(settings)
  if (req.method === 'GET' && path === '/api/status') return json(status)
  if (req.method === 'GET' && path === '/api/ui/layout') return json(layout())
  if (req.method === 'PUT' && path === '/api/ui/layout') {
    let raw = ''
    req.on('data', (chunk) => { raw += chunk })
    req.on('end', () => {
      try {
        const body = JSON.parse(raw || '{}')
        if (body && typeof body.column === 'object' && body.column != null) {
          layoutMemo.widthPx = body.column.widthPx
        }
      } catch { }
      json(layout())
    })
    return
  }
  if (req.method === 'GET' && path === '/api/camera/lenses') return json({ lenses: [] })
  if (req.method === 'POST' || req.method === 'PUT' || req.method === 'DELETE') {
    return json({ success: true })
  }
  json({ error: 'not mocked: ' + path }, 404)
})

const port = Number(process.env.MOCK_PORT || 8099)
server.listen(port, () => console.log(`mock api on :${port} — run vite with DEV_API_ORIGIN=http://localhost:${port}`))

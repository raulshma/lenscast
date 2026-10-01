// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createRoot } from 'solid-js'
import { createH264Player, h264Supported, wsUrl } from './h264Player'

// The h264 rung's use-time WebCodecs guards: jsdom has no VideoDecoder, so
// this is also the exact environment of a browser where WebCodecs is absent
// — the rung must report an error (the caller demotes to MJPEG) instead of
// opening a socket and throwing `VideoDecoder is not a constructor` inside
// the WS onmessage handler (the black-canvas wedge).

describe('h264Player without WebCodecs support', () => {
  it('h264Supported is false', () => {
    expect(h264Supported()).toBe(false)
  })

  it('start() reports an error and never opens a WebSocket', () => {
    createRoot((dispose) => {
      const statuses: string[] = []
      const opened: string[] = []
      const RealWebSocket = globalThis.WebSocket
      class RecordingWebSocket {
        constructor(url: string) { opened.push(url) }
      }
      ;(globalThis as any).WebSocket = RecordingWebSocket
      try {
        const player = createH264Player({ onStatus: (s) => statuses.push(s) })
        const canvas = document.createElement('canvas')
        player.start(canvas)
        expect(statuses).toEqual(['error'])
        expect(opened).toEqual([])
        player.stop()
      } finally {
        ;(globalThis as any).WebSocket = RealWebSocket
        dispose()
      }
    })
  })
})

// Same-origin WS URLs: the socket rides the page's own origin (host + port
// included) under /ws/, so a reverse proxy on any port forwards one hop and
// an https page upgrades the scheme to wss. The location is injectable —
// jsdom's origin is fixed per file.

describe('wsUrl same-origin construction', () => {
  const loc = (protocol: string, host: string): Location =>
    ({ protocol, host }) as unknown as Location

  it('plain LAN http with explicit port', () => {
    expect(wsUrl('video', loc('http:', '192.168.1.23:8080'))).toBe('ws://192.168.1.23:8080/ws/video')
  })

  it('https on the default port (empty location.port) — the reverse-proxy case', () => {
    expect(wsUrl('video', loc('https:', 'example.org'))).toBe('wss://example.org/ws/video')
  })

  it('https on a non-default port keeps the port and upgrades the scheme', () => {
    expect(wsUrl('video', loc('https:', 'example.org:8443'))).toBe('wss://example.org:8443/ws/video')
  })

  it('talkback path rides the same construction', () => {
    expect(wsUrl('talkback', loc('http:', '192.168.1.23:8080'))).toBe('ws://192.168.1.23:8080/ws/talkback')
  })
})

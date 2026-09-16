// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createRoot } from 'solid-js'
import { createH264Player, h264Supported } from './h264Player'

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

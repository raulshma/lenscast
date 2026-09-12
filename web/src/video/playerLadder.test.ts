import { describe, expect, it } from 'vitest'
import { PLAYER_LADDER, cyclePlayerMode, nextPlayerMode, whepSupported } from './playerLadder'

describe('nextPlayerMode automatic fallback ladder', () => {
  it('starts at whep when nothing has failed', () => {
    expect(nextPlayerMode('whep', false, false, false, true)).toBe('whep')
  })

  it('falls whep → h264 when the WebRTC rung gives up', () => {
    expect(nextPlayerMode('whep', true, false, false, true)).toBe('h264')
  })

  it('falls h264 → mjpeg when the WebSocket decoder gives up', () => {
    expect(nextPlayerMode('h264', true, true, false, true)).toBe('mjpeg')
  })

  it('falls mjpeg → hls when MJPEG exhausts its retries', () => {
    expect(nextPlayerMode('mjpeg', true, true, true, true)).toBe('hls')
  })

  it('falls whep → hls directly when h264 and mjpeg are already out of play', () => {
    expect(nextPlayerMode('whep', true, true, true, true)).toBe('hls')
  })

  it('stops at mjpeg when hls is unsupported', () => {
    expect(nextPlayerMode('whep', true, true, false, false)).toBe('mjpeg')
  })

  it('skips whep from the start when RTCPeerConnection is missing', () => {
    // The caller folds support into the flag (whepFailed = !whepSupported()).
    expect(nextPlayerMode('mjpeg', true, false, false, true)).toBe('h264')
  })

  it('stays put when every rung is exhausted', () => {
    expect(nextPlayerMode('mjpeg', true, true, true, false)).toBe('mjpeg')
    expect(nextPlayerMode('hls', true, true, true, false)).toBe('hls')
  })
})

describe('cyclePlayerMode manual toggle', () => {
  it('walks the ladder whep → h264 → mjpeg → hls and wraps back to whep', () => {
    expect(cyclePlayerMode('whep')).toBe('h264')
    expect(cyclePlayerMode('h264')).toBe('mjpeg')
    expect(cyclePlayerMode('mjpeg')).toBe('hls')
    expect(cyclePlayerMode('hls')).toBe('whep')
  })
})

describe('PLAYER_LADDER', () => {
  it('orders rungs lowest latency first with hls last', () => {
    expect(PLAYER_LADDER).toEqual(['whep', 'h264', 'mjpeg', 'hls'])
  })
})

describe('whepSupported', () => {
  it('answers a boolean without a window (node) and never throws', () => {
    // The node test env has no window: the rung is statically unsupported
    // there, which is exactly how callers fold it into the ladder flags.
    expect(whepSupported()).toBe(false)
  })
})

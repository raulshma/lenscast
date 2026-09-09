import { describe, expect, it } from 'vitest'
import { PLAYER_LADDER, cyclePlayerMode, nextPlayerMode } from './playerLadder'

describe('nextPlayerMode automatic fallback ladder', () => {
  it('starts at h264 when nothing has failed', () => {
    expect(nextPlayerMode('h264', false, false, true)).toBe('h264')
  })

  it('falls h264 → mjpeg when the WebSocket decoder gives up', () => {
    expect(nextPlayerMode('h264', true, false, true)).toBe('mjpeg')
  })

  it('falls mjpeg → hls when MJPEG exhausts its retries', () => {
    expect(nextPlayerMode('mjpeg', true, true, true)).toBe('hls')
  })

  it('falls h264 → hls directly when MJPEG is already out of play', () => {
    expect(nextPlayerMode('h264', true, true, true)).toBe('hls')
  })

  it('stops at mjpeg when hls is unsupported', () => {
    expect(nextPlayerMode('h264', true, false, false)).toBe('mjpeg')
  })

  it('stays put when every rung is exhausted', () => {
    expect(nextPlayerMode('mjpeg', true, true, false)).toBe('mjpeg')
    expect(nextPlayerMode('hls', true, true, false)).toBe('hls')
  })

  it('skips h264 from the start when WebCodecs is missing', () => {
    expect(nextPlayerMode('mjpeg', true, false, true)).toBe('mjpeg')
  })
})

describe('cyclePlayerMode manual toggle', () => {
  it('walks the ladder h264 → mjpeg → hls and wraps back to h264', () => {
    expect(cyclePlayerMode('h264')).toBe('mjpeg')
    expect(cyclePlayerMode('mjpeg')).toBe('hls')
    expect(cyclePlayerMode('hls')).toBe('h264')
  })
})

describe('PLAYER_LADDER', () => {
  it('orders rungs lowest latency first with hls last', () => {
    expect(PLAYER_LADDER).toEqual(['h264', 'mjpeg', 'hls'])
  })
})

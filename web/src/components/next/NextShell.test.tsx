// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import type { DeviceStatus } from '../../types'
import type { PlayerMode } from '../../video/playerLadder'
import { streamStatsOverlayStore } from '../../lib/streamStatsOverlay'
import type { NextState } from './NextState'
import NextShell from './NextShell'

// The statusline's TX/FPS chips, through the real shell (issue #25): they
// follow the preview's own lane and the stats toggle. The view outlet and
// the overlays are stubbed out — the chips live in the shell's own strip and
// depend on nothing those mount — so the shell needs only the few state
// accessors it reads itself.

vi.mock('./views/LiveView', () => ({ default: () => null }))
vi.mock('./views/MediaView', () => ({ default: () => null }))
vi.mock('./views/DetectionView', () => ({ default: () => null }))
vi.mock('./views/SystemView', () => ({ default: () => null }))
vi.mock('../ShortcutsOverlay', () => ({ default: () => null }))
vi.mock('../MediaViewer', () => ({ MediaViewerOverlay: () => null }))
vi.mock('./StreamPipHost', () => ({ default: () => null }))

// All three measured lanes at once (web/contract/status.json's shapes), so
// a chip reading another lane's numbers cannot hide behind an absent block.
const allLanes = {
  connectionQuality: {
    qualityLevel: 'GOOD',
    estimatedBandwidthKbps: 4200,
    avgThroughputKbps: 3900,
    minThroughputKbps: 3500,
    worstLatencyMs: 12,
    avgFrameSizeBytes: 45000,
    totalBytesSent: 0,
    activeClients: 1,
    framesPerSecond: 23.5,
    clientDetails: {},
  },
  encodedStream: { measuredBitrateKbps: 1890, fps: 23.5, targetBitrateKbps: 2000, codec: 'h264' },
  whepStream: { measuredBitrateKbps: 1700, fps: 24, codec: 'vp8' },
}
/** Adaptive Bitrate on, live with a positive congestion estimate (main's chip gate). */
const adaptiveOn = { adaptiveBitrate: { enabled: true, estimatedBandwidthKbps: 4200 } }

function renderShell(status: Record<string, unknown>) {
  const [statusSignal, setStatus] = createSignal<DeviceStatus | undefined>(status as unknown as DeviceStatus)
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>('whep')
  const state = {
    status: statusSignal,
    playerMode,
    error: () => null,
    captureMsg: () => '',
    authRequired: () => false,
    isRecording: () => false,
    isViewer: () => false,
    saving: () => false,
    showShortcuts: () => false,
    setShowShortcuts: () => {},
    connectionLost: () => false,
    handleLogout: () => {},
    recordingTimer: { formatElapsed: () => '00:00' },
  } as unknown as NextState
  render(() => <NextShell state={state} theme={() => 'dark'} toggleTheme={() => {}} />)
  return { setStatus, setPlayerMode }
}

/** The strip's TX/FPS chip texts in DOM order; the rest of the strip is other telemetry. */
function streamChips(): string[] {
  return [...document.querySelectorAll('.nx-strip-chips .nx-chip')]
    .map((el) => el.textContent?.trim() ?? '')
    .filter((text) => text.startsWith('TX ') || text.endsWith('FPS'))
}

describe('NextShell — the TX/FPS chips', () => {
  beforeEach(() => {
    // The app-wide singleton persists across a file's tests.
    streamStatsOverlayStore().set('auto')
  })
  afterEach(cleanup)

  const live = (over: Record<string, unknown> = {}) => ({ streaming: { isActive: true }, ...allLanes, ...over })

  it("auto keeps main's gate: no chips while Adaptive Bitrate is off, even with lane data", () => {
    renderShell(live())
    expect(streamChips()).toEqual([])
  })

  it('auto shows them once Adaptive Bitrate is live — the WHEP preview reads its own lane, not the encoded block', () => {
    renderShell(live(adaptiveOn))
    expect(streamChips()).toEqual(['TX 1.7M', '24FPS'])
  })

  it('follows the preview rung: the same status reads MJPEG, then H264, then WHEP', () => {
    const { setPlayerMode } = renderShell(live(adaptiveOn))
    setPlayerMode('mjpeg')
    expect(streamChips()).toEqual(['TX 3.9M', '23.5FPS'])
    setPlayerMode('h264')
    expect(streamChips()).toEqual(['TX 1.9M', '23.5FPS'])
    setPlayerMode('whep')
    expect(streamChips()).toEqual(['TX 1.7M', '24FPS'])
  })

  it('never borrows: a WHEP preview with no viewer report shows no chips, encoded block or not', () => {
    renderShell(live({ ...adaptiveOn, whepStream: undefined }))
    expect(streamChips()).toEqual([])
  })

  it('on shows the lane without Adaptive Bitrate and while the stream is down; off hides', () => {
    const overlay = streamStatsOverlayStore()
    const down = { streaming: { isActive: false }, ...allLanes }
    overlay.set('on')
    renderShell(down)
    expect(streamChips()).toEqual(['TX 1.7M', '24FPS'])
    overlay.set('off')
    expect(streamChips()).toEqual([])
  })
})

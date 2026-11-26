// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import StreamPreview from './StreamPreview'
import { streamStatsOverlayStore } from '../lib/streamStatsOverlay'
import type { PlayerMode } from '../video/playerLadder'

// The full-screen preview's browser contract, exercised through the real
// component (the talk-test model: a props factory + guarded DOM stubs). The
// pure verdicts are pinned in lib/fullscreenPolicy.test.ts; here we prove the
// hook wires them to the right primitives — the toggle fullscreens the shared
// *container* (every player rung rides inside it), a rejected native request
// falls back to the CSS overlay, Escape ownership splits native/CSS, and the
// screen wake lock is fullscreen-scoped (acquire on enter, release on
// exit/stop/unmount, re-acquire on visibility regain) with graceful insecure /
// missing-API degradation. jsdom has none of these primitives, so each is
// stubbed: Element.prototype.requestFullscreen (a vi.fn whose `this` is the
// target element), window.isSecureContext (a prototype getter — shadowed with
// an own property), and navigator.wakeLock resolving a fake sentinel.

function renderPreview(
  opts: {
    live?: boolean
    mode?: PlayerMode
    stats?: Record<string, unknown>
    /** The opt-in props (pictureOverlay, gesturesLocked, stripOverlay) a slot test passes. */
    extra?: Record<string, unknown>
  } = {},
) {
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>(opts.mode ?? 'mjpeg')
  const [live, setLive] = createSignal(opts.live ?? false)
  // The payload is a signal too: a test can change what the device reports
  // (a lane appearing, a battery tick) under an already-mounted preview.
  const [stats, setStats] = createSignal<Record<string, unknown>>(opts.stats ?? {})
  const props = {
    status: () => (live() ? ({ streaming: { webStreamingActive: true, isActive: true }, ...stats() } as any) : null),
    previewVisible: () => live(),
    streamNonce: () => 0,
    streamActionLoading: () => false,
    isRecording: () => false,
    captureMsg: () => '',
    liveAudioStatus: () => 'idle' as const,
    recordingTimer: { formatElapsed: () => '00:00' },
    playerMode,
    setPlayerMode,
    handleCapture: () => {},
    handleStartWebStream: () => {},
    handleStopWebStream: () => {},
    handleStartRtspStream: () => {},
    handleStopRtspStream: () => {},
    setPreviewVisible: () => {},
    connectionLost: () => false,
    ...opts.extra,
  }
  const utils = render(() => <StreamPreview {...props} />)
  return { ...utils, setLive, setStats, setPlayerMode }
}

interface FullscreenStubs {
  requestFullscreen: ReturnType<typeof vi.fn>
  wakeRequest: ReturnType<typeof vi.fn>
  sentinel: {
    release: ReturnType<typeof vi.fn>
    addEventListener: ReturnType<typeof vi.fn>
    removeEventListener: ReturnType<typeof vi.fn>
  }
  /** Invoke the sentinel's captured 'release' listener (the browser hiding the tab). */
  fireSentinelRelease: () => void
}

function installFullscreen(opts: { reject?: boolean; secure?: boolean; wakeLock?: boolean } = {}): FullscreenStubs {
  const requestFullscreen = vi.fn(function (this: Element) {
    return opts.reject ? Promise.reject(new Error('denied')) : Promise.resolve()
  })
  Object.defineProperty(Element.prototype, 'requestFullscreen', {
    configurable: true,
    writable: true,
    value: requestFullscreen,
  })
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: opts.secure ?? true })

  const releaseListeners: Array<() => void> = []
  const sentinel = {
    release: vi.fn(async () => {}),
    addEventListener: vi.fn((type: string, cb: () => void) => {
      if (type === 'release') releaseListeners.push(cb)
    }),
    removeEventListener: vi.fn(),
  }
  const wakeRequest = vi.fn(async () => sentinel)
  if (opts.wakeLock !== false) {
    Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { request: wakeRequest } })
  }
  return {
    requestFullscreen,
    wakeRequest,
    sentinel,
    fireSentinelRelease: () => releaseListeners.forEach((cb) => cb()),
  }
}

function container(): HTMLElement {
  return document.querySelector('.preview-container') as HTMLElement
}

function clickToggle() {
  fireEvent.click(screen.getByText('Full Screen').closest('button')!)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  delete (Element.prototype as any).requestFullscreen
  delete (window as any).isSecureContext
  delete (navigator as any).wakeLock
})

describe('Full-screen preview targeting and fallback', () => {
  it('the toggle fullscreens the preview container, not the button', async () => {
    const stubs = installFullscreen()
    renderPreview({ live: true })
    const target = container()
    clickToggle()
    await waitFor(() => expect(stubs.requestFullscreen).toHaveBeenCalledTimes(1))
    expect(stubs.requestFullscreen.mock.contexts[0]).toBe(target)
    // The native request resolved → the in-container exit affordance appears.
    await screen.findByText('Exit')
  })

  it('a rejected native request falls back to the CSS overlay', async () => {
    installFullscreen({ reject: true })
    renderPreview({ live: true })
    clickToggle()
    await waitFor(() => expect(container().classList.contains('preview-container-fullscreen')).toBe(true))
    await screen.findByText('Exit')
  })
})

describe('Escape ownership', () => {
  it('Escape exits the CSS overlay', async () => {
    installFullscreen({ reject: true })
    renderPreview({ live: true })
    clickToggle()
    await screen.findByText('Exit')
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(container().classList.contains('preview-container-fullscreen')).toBe(false))
    expect(screen.queryByText('Exit')).toBeNull()
  })

  it('native fullscreen leaves Escape to the browser', async () => {
    installFullscreen()
    renderPreview({ live: true })
    clickToggle()
    await screen.findByText('Exit')
    fireEvent.keyDown(document, { key: 'Escape' })
    // The hook no-ops in native mode (the browser owns Escape) — still active.
    expect(screen.getByText('Exit')).toBeTruthy()
  })
})

describe('Screen wake lock lifecycle', () => {
  it('an active stream alone never holds the screen (fullscreen-scoped)', () => {
    const stubs = installFullscreen()
    renderPreview({ live: true })
    // Mounted live but not fullscreen: the verdict is release, never acquire.
    expect(stubs.wakeRequest).not.toHaveBeenCalled()
  })

  it('acquires on enter and releases on exit', async () => {
    const stubs = installFullscreen()
    renderPreview({ live: true })
    clickToggle()
    await waitFor(() => expect(stubs.wakeRequest).toHaveBeenCalledWith('screen'))
    fireEvent.click(screen.getByText('Exit').closest('button')!)
    await waitFor(() => expect(stubs.sentinel.release).toHaveBeenCalledTimes(1))
  })

  it('releases on unmount mid-fullscreen', async () => {
    const stubs = installFullscreen()
    const { unmount } = renderPreview({ live: true })
    clickToggle()
    await waitFor(() => expect(stubs.wakeRequest).toHaveBeenCalledTimes(1))
    unmount()
    await waitFor(() => expect(stubs.sentinel.release).toHaveBeenCalledTimes(1))
  })

  it('stopping the stream releases the lock while still fullscreen', async () => {
    const stubs = installFullscreen()
    const { setLive } = renderPreview({ live: true })
    clickToggle()
    await waitFor(() => expect(stubs.wakeRequest).toHaveBeenCalledTimes(1))
    setLive(false)
    await waitFor(() => expect(stubs.sentinel.release).toHaveBeenCalledTimes(1))
  })

  it('returning to the tab re-acquires a released lock', async () => {
    const stubs = installFullscreen()
    renderPreview({ live: true })
    clickToggle()
    await waitFor(() => expect(stubs.wakeRequest).toHaveBeenCalledTimes(1))
    // The browser releases the screen lock when the tab hides.
    stubs.fireSentinelRelease()
    // Returning to a still-fullscreen, still-live preview requests it again.
    fireEvent(document, new Event('visibilitychange'))
    await waitFor(() => expect(stubs.wakeRequest).toHaveBeenCalledTimes(2))
  })
})

describe('Graceful degradation', () => {
  it('an insecure context fullscreens but skips the lock with an HTTPS hint', async () => {
    const stubs = installFullscreen({ secure: false })
    renderPreview({ live: true })
    clickToggle()
    await screen.findByText('Exit')
    expect(stubs.wakeRequest).not.toHaveBeenCalled()
    await screen.findByText(/Screen wake lock needs HTTPS/)
  })

  it('a browser without the wake-lock API still fullscreens, silently', async () => {
    installFullscreen({ wakeLock: false })
    renderPreview({ live: true })
    clickToggle()
    await screen.findByText('Exit')
    expect(screen.queryByText(/Screen wake lock needs HTTPS/)).toBeNull()
  })
})

// The stream-stats overlay (issue #25): the preview indicator follows the
// stats toggle (auto/on/off, persisted app-wide) and the preview rung's
// lane — auto is today's gate (Adaptive Bitrate required), on shows the
// lane's measured numbers without it, off never shows. The Stats button in
// the action bar cycles the toggle. The toggle decides whether the indicator
// is MOUNTED; the lane's data only hides it.
describe('Stream stats overlay', () => {
  const mjpegStatus = {
    connectionQuality: {
      qualityLevel: 'GOOD',
      estimatedBandwidthKbps: 0,
      avgThroughputKbps: 3900,
      minThroughputKbps: 3500,
      worstLatencyMs: 12,
      avgFrameSizeBytes: 45000,
      totalBytesSent: 0,
      activeClients: 1,
      framesPerSecond: 23.5,
      clientDetails: {},
    },
  }
  const adaptiveOn = { adaptiveBitrate: { enabled: true } }
  const encodedStatus = {
    encodedStream: {
      measuredBitrateKbps: 1890,
      fps: 23.5,
      targetBitrateKbps: 2000,
      codec: 'h264',
      width: 1280,
      height: 720,
    },
  }

  const badge = () => document.querySelector('.cq-badge')
  // Mount vs visibility: the indicator's host (the wrapper's parent) stays
  // in the DOM while the lane has no data and carries `hidden` instead.
  const indicator = () => document.querySelector('.connection-quality-wrapper')?.parentElement ?? null

  function clickStats() {
    fireEvent.click(screen.getByText(/^Stats: /).closest('button')!)
  }

  // jsdom has neither WebCodecs nor WebSocket, so the H264 rung reports
  // 'error' at start() and the ladder demotes to MJPEG. Stubbing the pair
  // keeps the rung mounted (a socket that never opens demotes nothing), the
  // same never-fires stub style installFullscreen uses.
  function installH264Rung() {
    vi.stubGlobal('VideoDecoder', function FakeDecoder() {})
    vi.stubGlobal('WebSocket', class FakeWebSocket {
      binaryType = 'blob'
      onopen: unknown = null
      onmessage: unknown = null
      onerror: unknown = null
      onclose: unknown = null
      close() {}
      send() {}
    })
  }

  beforeEach(() => {
    // The app-wide singleton persists across a file's tests — start each
    // from the default so the cycle tests are order-independent.
    streamStatsOverlayStore().set('auto')
  })

  it('auto keeps today\'s gate: no indicator without Adaptive Bitrate, even with lane data', () => {
    renderPreview({ live: true, stats: mjpegStatus })
    expect(badge()).toBeNull()
  })

  it('auto shows the indicator once Adaptive Bitrate is enabled, with the MJPEG lane\'s numbers', () => {
    renderPreview({ live: true, stats: { ...mjpegStatus, ...adaptiveOn } })
    expect(badge()).not.toBeNull()
    expect(screen.getByText('23.5 fps · 3900 kbps')).toBeTruthy()
  })

  it('on shows the indicator without Adaptive Bitrate', () => {
    streamStatsOverlayStore().set('on')
    renderPreview({ live: true, stats: mjpegStatus })
    expect(badge()).not.toBeNull()
  })

  it('off hides the indicator even with Adaptive Bitrate enabled', () => {
    streamStatsOverlayStore().set('off')
    renderPreview({ live: true, stats: { ...mjpegStatus, ...adaptiveOn } })
    expect(badge()).toBeNull()
  })

  it('a hidden preview hides the indicator in every mode', () => {
    streamStatsOverlayStore().set('on')
    renderPreview({ live: false, stats: mjpegStatus })
    expect(badge()).toBeNull()
  })

  it('the badge follows the preview rung\'s lane: an H264 rung reads the encoded lane', () => {
    installH264Rung()
    streamStatsOverlayStore().set('on')
    renderPreview({ live: true, mode: 'h264', stats: { ...mjpegStatus, ...encodedStatus } })
    expect(screen.getByText('23.5 fps · 1890 kbps')).toBeTruthy()
  })

  it('an H264 rung while not encoding shows no lane data (no MJPEG fallback numbers)', () => {
    installH264Rung()
    streamStatsOverlayStore().set('on')
    renderPreview({ live: true, mode: 'h264', stats: mjpegStatus })
    expect(indicator()?.hasAttribute('hidden')).toBe(true)
    expect(document.body.textContent).not.toContain('3900 kbps')
  })

  // The deliberate auto-gate narrowing (issue #25 review round): the old
  // gate only asked "connectionQuality present", so an encoded-rung preview
  // under Adaptive Bitrate showed the wrong lane's MJPEG numbers whenever no
  // encoded stream ran. auto now requires the picked lane's data — with the
  // encoded block absent the indicator hides instead of borrowing.
  it('auto on an encoded rung with Adaptive Bitrate on but no encoded stream hides (no wrong-lane numbers)', () => {
    installH264Rung()
    renderPreview({ live: true, mode: 'h264', stats: { ...mjpegStatus, ...adaptiveOn } })
    expect(indicator()?.hasAttribute('hidden')).toBe(true)
    expect(document.body.textContent).not.toContain('3900 kbps')
  })

  // The mount/visibility split: a lane switch reaches the gate as "the new
  // lane has no data yet" (the encoded block only exists once the encoder
  // runs, the WHEP block once a viewer reported). That hides the indicator
  // — it must not unmount it, or the panel the operator opened would
  // collapse and its sparkline history would start over on every switch.
  it('a lane switch hides the indicator without unmounting it: the panel stays open and its battery history survives', () => {
    installH264Rung()
    streamStatsOverlayStore().set('on')
    const battery = (level: number) => ({ ...mjpegStatus, battery: { level } })
    const { setStats, setPlayerMode } = renderPreview({ live: true, mode: 'mjpeg', stats: battery(80) })
    setStats(battery(79))
    // The sparklines live in the panel, so the panel opens first.
    fireEvent.click(badge()!)
    expect(document.querySelector('.cq-detail-panel')).not.toBeNull()
    const batteryPoints = () =>
      (document.querySelector('svg[aria-label="Battery level history (%)"] polyline')?.getAttribute('points') ?? '')
        .split(' ').filter(Boolean).length
    const pointsBefore = batteryPoints()
    expect(pointsBefore).toBeGreaterThan(1)

    setPlayerMode('h264') // the encoded lane — nothing has reported yet
    expect(indicator()?.hasAttribute('hidden')).toBe(true)
    expect(document.querySelector('.connection-quality-wrapper')).not.toBeNull()

    setStats({ ...encodedStatus, battery: { level: 78 } }) // the encoded lane reports
    expect(indicator()?.hasAttribute('hidden')).toBe(false)
    expect(screen.getByText('23.5 fps · 1890 kbps')).toBeTruthy()
    // Still the operator's open panel — it did not collapse with the lane.
    expect(document.querySelector('.cq-detail-panel')).not.toBeNull()
    for (const row of screen.getAllByText('Bitrate')) expect(row.parentElement?.textContent).toContain('1890 kbps')
    // One new sample per status push — the pre-switch samples are still there.
    expect(batteryPoints()).toBe(pointsBefore + 1)
  })

  it('the Stats button cycles auto → on → off → auto, gating the indicator along the way', () => {
    renderPreview({ live: true, stats: mjpegStatus })
    expect(screen.getByText('Stats: AUTO')).toBeTruthy()
    expect(badge()).toBeNull()

    clickStats() // auto → on
    expect(screen.getByText('Stats: ON')).toBeTruthy()
    expect(badge()).not.toBeNull()

    clickStats() // on → off
    expect(screen.getByText('Stats: OFF')).toBeTruthy()
    expect(badge()).toBeNull()

    clickStats() // off → auto
    expect(screen.getByText('Stats: AUTO')).toBeTruthy()
    expect(badge()).toBeNull()
  })
})

// The picture overlay slot and the gesture lock (the masking-zone editor's
// stage plumbing): both are opt-in props, and with them absent the preview
// is exactly what it was.
describe('Picture overlay slot and gesture lock', () => {
  it('the slot renders as the container\'s last element, after the strip, and on the MJPEG rung media() is the MJPEG img', () => {
    let media: (() => unknown) | null = null
    renderPreview({
      live: true,
      extra: {
        stripOverlay: () => <div data-testid="strip" />,
        pictureOverlay: (accessor: () => unknown) => {
          media = accessor
          return <div data-testid="picture-layer" />
        },
      },
    })
    const box = container()
    const layer = screen.getByTestId('picture-layer')
    expect(layer.parentElement).toBe(box)
    expect(box.lastElementChild).toBe(layer)
    expect(box.querySelector('.cam-strip-host')).not.toBeNull()
    // Strip host, then the layer: the slot is stacked above the strip.
    expect(box.querySelector('.cam-strip-host')!.nextElementSibling).toBe(layer)
    expect(media!()).toBe(box.querySelector('img.preview-img'))
  })

  it('media() is undefined while the stage shows the placeholder — never a stale element', () => {
    let media: (() => unknown) | null = null
    const { setLive } = renderPreview({
      live: true,
      extra: {
        pictureOverlay: (accessor: () => unknown) => {
          media = accessor
          return <div data-testid="picture-layer" />
        },
      },
    })
    const first = container().querySelector('img.preview-img')
    expect(media!()).toBe(first)
    // The stream stops: the stage swaps to its placeholder.
    setLive(false)
    expect(document.querySelector('img.preview-img')).toBeNull()
    expect(media!()).toBeUndefined()
    // Back on air: the rung's new element.
    setLive(true)
    expect(media!()).toBe(container().querySelector('img.preview-img'))
    expect(media!()).not.toBe(first)
  })

  it('without the slot the container ends where it always did', () => {
    renderPreview({ live: true, extra: { stripOverlay: () => <div data-testid="strip" /> } })
    expect(container().lastElementChild).toBe(container().querySelector('.cam-strip-host'))
  })

  it('a gesture lock starts from an unzoomed picture and keeps the wheel inert until it lifts', () => {
    const [locked, setLocked] = createSignal(false)
    renderPreview({ live: true, extra: { gesturesLocked: locked } })
    const box = container()
    const transform = () => (box.querySelector('img.preview-img') as HTMLElement).style.transform
    // Unzoomed and unpanned (jsdom serialises a zero translate as one length).
    const atRest = /^scale\(1\) translate\(0px(?:, 0px)?\)$/
    expect(transform()).toMatch(atRest)

    // Unlocked: the wheel zooms, as ever.
    fireEvent.wheel(box, { deltaY: -100 })
    expect(transform()).toContain('scale(1.15)')

    // The lock falls: the picture is back at scale 1 without a gesture.
    setLocked(true)
    expect(transform()).toMatch(atRest)
    const event = new WheelEvent('wheel', { deltaY: -100, bubbles: true, cancelable: true })
    box.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
    expect(transform()).toMatch(atRest)

    // The lock lifts: the wheel zooms again.
    setLocked(false)
    fireEvent.wheel(box, { deltaY: -100 })
    expect(transform()).toContain('scale(1.15)')
  })

  // The rung must hold still under a lock: the masking-zone editor maps its
  // zones onto the MJPEG frame, which the other rungs crop — so the button
  // that would move the picture under them stands down with the gestures.
  const cycleButton = () => screen.getByTitle(/^Preview player: /).closest('button') as HTMLButtonElement

  it('a gesture lock stands the player-cycle button down, and it recovers when the lock lifts', () => {
    const [locked, setLocked] = createSignal(false)
    renderPreview({ live: true, extra: { gesturesLocked: locked } })
    expect(cycleButton().disabled).toBe(false)

    // Locked: disabled — and a click cannot move the rung (Solid's own
    // delegation skips a disabled control, as a browser does).
    setLocked(true)
    expect(cycleButton().disabled).toBe(true)
    fireEvent.click(cycleButton())
    expect(screen.getByText('Preview: MJPEG')).toBeTruthy()
    expect(document.querySelector('img.preview-img')).not.toBeNull()

    // The lock lifts: the button is back, and works.
    setLocked(false)
    expect(cycleButton().disabled).toBe(false)
    fireEvent.click(cycleButton())
    expect(screen.queryByText('Preview: MJPEG')).toBeNull()
    expect(document.querySelector('img.preview-img')).toBeNull()
  })

  it('without the lock the player-cycle button is exactly what it was', () => {
    renderPreview({ live: true })
    const button = cycleButton()
    expect(button.disabled).toBe(false)
    expect(button.hasAttribute('disabled')).toBe(false)
    expect(button.getAttribute('title')).toBe('Preview player: MJPEG · click to switch to HLS')
    fireEvent.click(button)
    expect(screen.queryByText('Preview: MJPEG')).toBeNull()
  })
})
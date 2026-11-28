// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import StreamPreview from './StreamPreview'
import type { DeviceStatus } from '../types'
import type { PlayerMode } from '../video/playerLadder'

vi.mock('../lib/whepClient', () => ({
  createWhepPlayer: () => ({ start: vi.fn(), stop: vi.fn() }),
}))
vi.mock('../video/h264Player', () => ({
  createH264Player: () => ({ start: vi.fn(), stop: vi.fn() }),
  h264Supported: () => true,
  wsUrl: (path: string) => `ws://localhost/ws/${path}`,
}))

function mountPreview(initialMode: PlayerMode = 'mjpeg') {
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>(initialMode)
  const [status, setStatus] = createSignal<DeviceStatus>({
    streaming: { webStreamingActive: true },
  } as DeviceStatus)
  const [previewVisible, setPreviewVisible] = createSignal(true)
  const [streamNonce, setStreamNonce] = createSignal(0)
  const [gesturesLocked, setGesturesLocked] = createSignal(false)
  const view = render(() => (
    <StreamPreview
      status={status}
      previewVisible={previewVisible}
      streamNonce={streamNonce}
      streamActionLoading={() => false}
      isRecording={() => false}
      captureMsg={() => ''}
      liveAudioStatus={() => 'idle'}
      recordingTimer={{ formatElapsed: () => '00:00' }}
      playerMode={playerMode}
      setPlayerMode={setPlayerMode}
      handleCapture={() => {}}
      handleStartWebStream={() => {}}
      handleStopWebStream={() => {}}
      handleStartRtspStream={() => {}}
      handleStopRtspStream={() => {}}
      setPreviewVisible={setPreviewVisible}
      connectionLost={() => false}
      gesturesLocked={gesturesLocked}
    />
  ))
  const container = () => view.container.querySelector('.zoomable-container') as HTMLElement
  const media = () => view.container.querySelector('.zoomable-content') as HTMLElement
  container().getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 100 }) as DOMRect
  const wheel = () => {
    const event = new WheelEvent('wheel', {
      deltaY: -100,
      clientX: 150,
      clientY: 120,
      bubbles: true,
      cancelable: true,
    })
    container().dispatchEvent(event)
    return event
  }
  const setWebActive = (active: boolean) => setStatus((previous) => ({
    ...previous,
    streaming: { ...previous.streaming, webStreamingActive: active },
  }))
  return {
    playerMode,
    setPlayerMode,
    container,
    media,
    wheel,
    setWebActive,
    setPreviewVisible,
    setStreamNonce,
    setGesturesLocked,
    setCameraBlocked: (blocked: boolean) => setStatus((previous) => ({ ...previous, cameraBlockedByLock: blocked })),
  }
}

const RESET_TRANSFORM = 'scale(1) translate(0px)'
const ZOOMED_AT_ORIGIN = 'scale(1.15) translate(0px)'

afterEach(() => cleanup())

describe('StreamPreview gesture zoom', () => {
  it.each([
    ['mjpeg', 'img'],
    ['hls', 'video'],
    ['h264', 'canvas'],
    ['whep', 'video'],
  ] as const)('applies the same wheel transform to the %s player', (mode, tag) => {
    const preview = mountPreview(mode)
    expect(preview.media().tagName.toLowerCase()).toBe(tag)
    expect(preview.wheel().defaultPrevented).toBe(true)
    // jsdom has no layout, so the pan clamps to (0, 0); cssstyle serializes
    // the zero translate in its one-argument form.
    expect(preview.media().style.transform).toBe(ZOOMED_AT_ORIGIN)
    if (mode === 'hls') {
      // The one rung that used to carry native controls drops them: the zoom
      // transform scales/translates the element wholesale, so a control bar
      // would magnify and drift with the picture — all four transports zoom
      // as the same bare surface (issue #27).
      expect((preview.media() as HTMLVideoElement).hasAttribute('controls')).toBe(false)
    }
  })

  it('resets on manual player changes and automatic WHEP demotion', () => {
    const preview = mountPreview('whep')
    preview.wheel()
    expect(preview.media().style.transform).not.toBe(RESET_TRANSFORM)

    fireEvent.error(preview.media())
    expect(preview.playerMode()).toBe('h264')
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)

    preview.wheel()
    preview.setPlayerMode('mjpeg')
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)
  })

  it('resets when the stream nonce changes', () => {
    const preview = mountPreview()
    preview.wheel()
    expect(preview.media().style.transform).not.toBe(RESET_TRANSFORM)
    preview.setStreamNonce(1)
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)
  })

  it('resets while stopped, hidden or lock-blocked and keeps the reset on return', () => {
    const preview = mountPreview()
    preview.wheel()
    preview.setWebActive(false)
    preview.setWebActive(true)
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)

    preview.wheel()
    preview.setPreviewVisible(false)
    preview.setPreviewVisible(true)
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)

    preview.wheel()
    preview.setCameraBlocked(true)
    preview.setCameraBlocked(false)
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)
  })

  it('gates gestures on both the gesture lock and a live stage', () => {
    const preview = mountPreview()
    preview.wheel()
    preview.setGesturesLocked(true)
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)
    expect(preview.wheel().defaultPrevented).toBe(false)
    preview.setGesturesLocked(false)
    expect(preview.wheel().defaultPrevented).toBe(true)

    preview.setWebActive(false)
    expect(preview.wheel().defaultPrevented).toBe(false)
    preview.setWebActive(true)
    expect(preview.media().style.transform).toBe(RESET_TRANSFORM)
    expect(preview.wheel().defaultPrevented).toBe(true)
  })

  it('preserves zoom across ordinary status refreshes while the stage stays live', () => {
    const preview = mountPreview()
    preview.wheel()
    const transform = preview.media().style.transform
    preview.setWebActive(true)
    expect(preview.media().style.transform).toBe(transform)
  })

  it('pins preview container isolation and action-bar positioning above transformed media', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/App.css'), 'utf8')
    expect(css).toMatch(/\.preview-container\s*\{[^}]*position:\s*relative;[^}]*isolation:\s*isolate;/s)
    expect(css).toMatch(/\.preview-img\s*\{[^}]*position:\s*relative;[^}]*z-index:\s*0;/s)
    expect(css).toMatch(/\.preview-actions\s*\{[^}]*position:\s*relative;[^}]*z-index:\s*10;/s)
    expect(css).toMatch(/\.preview-actions-left\s*\{[^}]*position:\s*relative;[^}]*z-index:\s*1;/s)
  })
})

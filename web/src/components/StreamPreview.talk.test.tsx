// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import StreamPreview from './StreamPreview'

// The PTT hold's keyboard contract (the pointer path owns onPointerDown/Up;
// the keyboard path owns onKeyDown/Up): with the Talk button focused, Space
// must hold the talk open and surface the same failure copy the pointer path
// shows — previously the hold was pointer-only and keyboard/AT users had no
// way to talk at all. jsdom is insecure-context, so a hold lands on the
// 'needs HTTPS' verdict — which is exactly the observable we assert.

function renderPreview() {
  const [playerMode, setPlayerMode] = createSignal<'whep' | 'h264' | 'mjpeg' | 'hls'>('mjpeg')
  const props = {
    status: () => null,
    previewVisible: () => false,
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
    overlaySettings: () => null,
    connectionLost: () => false,
  }
  return render(() => <StreamPreview {...props} />)
}

describe('Talk button keyboard operability', () => {
  afterEach(cleanup)

  it('Space holds the talk, repeats are ignored, other keys are inert, keyup releases', async () => {
    renderPreview()
    const talk = screen.getByText('Talk').closest('button')!
    talk.focus()
    expect(document.activeElement).toBe(talk)

    // Hold: startPtt threw TalkUnavailableError('insecure') → the same
    // visible status line the pointer path flashes, on the same cleanup.
    fireEvent.keyDown(talk, { key: ' ', repeat: false })
    const verdict = await screen.findByText(/Mic needs HTTPS/)

    // Auto-repeat keydowns mid-hold must not re-trigger or disturb it.
    fireEvent.keyDown(talk, { key: ' ', repeat: true })
    expect(screen.getByText(/Mic needs HTTPS/)).toBe(verdict)

    // Foreign keys are inert (assert before keyup's cleanup pass).
    fireEvent.keyDown(talk, { key: 'a', repeat: false })
    expect(screen.getByText(/Mic needs HTTPS/)).toBe(verdict)

    // Release: no throw, verdict stays until its own flash timer expires.
    expect(() => fireEvent.keyUp(talk, { key: ' ' })).not.toThrow()
    expect(screen.getByText(/Mic needs HTTPS/)).toBe(verdict)

    // A later non-space keydown does not start a new hold.
    fireEvent.keyDown(talk, { key: 'a', repeat: false })
    expect(screen.getByText(/Mic needs HTTPS/)).toBe(verdict)
  })
})

/**
 * Unmount mid-hold: StreamPreview disappears whenever `authenticated` flips
 * false (a 401 demotes to the login screen, or the user logs out) — while the
 * hold is active that would strand the mic tracks, the AudioContext, and the
 * talkback WebSocket. The component's teardown onCleanup must stop every
 * track, close the socket, and close the audio context. jsdom has none of the
 * browser primitives a hold needs, so all three are stubbed here (the
 * AutomationCard stub style): a secure context, a getUserMedia resolving a
 * fake MediaStream whose tracks carry `stop` spies, a WebSocket fake whose
 * `close` flips readyState, and an AudioContext fake without audioWorklet
 * (driving tapPcm16's documented ScriptProcessor fallback path).
 */
class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  readonly CONNECTING = 0
  readonly OPEN = 1
  readonly CLOSING = 2
  readonly CLOSED = 3
  static instances: FakeWebSocket[] = []
  url: string
  binaryType: 'blob' | 'arraybuffer' = 'blob'
  readyState = FakeWebSocket.CONNECTING
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  send = vi.fn()
  close = vi.fn(() => { this.readyState = FakeWebSocket.CLOSED })
  constructor(url: string | URL) {
    this.url = String(url)
    FakeWebSocket.instances.push(this)
  }
}

class FakeAudioContext {
  state = 'running'
  readonly destination = {}
  close = vi.fn(async () => { this.state = 'closed' })
  createGain = vi.fn(() => ({ gain: { value: 1 }, connect: vi.fn() }))
  createMediaStreamSource = vi.fn(() => ({ connect: vi.fn() }))
  createScriptProcessor = vi.fn(() => ({ connect: vi.fn(), onaudioprocess: null }))
}

describe('Talk hold torn down on unmount', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    delete (navigator as any).mediaDevices
    delete (window as any).isSecureContext
  })

  it('unmount mid-hold stops every mic track and closes the socket', async () => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const audioContexts: FakeAudioContext[] = []
    vi.stubGlobal('AudioContext', class extends FakeAudioContext {
      constructor() {
        super()
        audioContexts.push(this)
      }
    })
    const tracks = [{ stop: vi.fn() }, { stop: vi.fn() }]
    const getUserMedia = vi.fn(async () => ({ getTracks: () => tracks }))
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } })
    // jsdom's isSecureContext is a prototype getter (false): shadow it with an
    // own property so startPtt gets past the secure-context verdict.
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true })

    const { unmount } = renderPreview()
    const talk = screen.getByText('Talk').closest('button')!
    talk.focus()
    fireEvent.keyDown(talk, { key: ' ', repeat: false })

    // Mid-hold observable: the latch flipped (button label), the mic was
    // acquired, and the audio graph is up — startPtt is past every await.
    await screen.findByText('Talking…')
    await waitFor(() => expect(audioContexts).toHaveLength(1))

    unmount()

    // The audio graph must not outlive the UI: every track stopped, the
    // socket closed (and detached from its spurious-close handler), the
    // AudioContext closed — the teardown awaits the context close before
    // the socket lines, so everything lands a microtask after unmount.
    await waitFor(() => {
      for (const track of tracks) expect(track.stop).toHaveBeenCalledTimes(1)
      expect(FakeWebSocket.instances).toHaveLength(1)
      const socket = FakeWebSocket.instances[0]
      expect(socket.close).toHaveBeenCalledTimes(1)
      expect(socket.readyState).toBe(FakeWebSocket.CLOSED)
      expect(socket.onclose).toBeNull()
      expect(audioContexts[0].close).toHaveBeenCalledTimes(1)
    })
    expect(getUserMedia).toHaveBeenCalledTimes(1)
  })
})

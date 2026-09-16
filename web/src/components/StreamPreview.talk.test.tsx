// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
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

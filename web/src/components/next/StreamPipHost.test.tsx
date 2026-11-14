// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, waitFor } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import StreamPipHost from './StreamPipHost'
import { hashRouter } from '../../lib/router'
import { registerStageSlot, resetPipStoreForTests } from '../../lib/pipStore'
import type { NextState } from './NextState'
import type { PlayerMode } from '../../video/playerLadder'

// The persistent stream host's browser contract: the player container
// re-parents between the registered stage slot, the mini-player dock and
// the holding pen as the route and the stream state flip — and the dock
// buttons hit exactly the actions they claim (stop, expand, dismiss-only).

function fixtureState(opts: { streamOn?: boolean } = {}) {
  const [status, setStatus] = createSignal<unknown>(
    opts.streamOn === false
      ? { streaming: { webStreamingActive: false, isActive: false } }
      : { streaming: { webStreamingActive: true, isActive: true } },
  )
  const [previewVisible, setPreviewVisible] = createSignal(true)
  const [lenses] = createSignal([])
  const [settings] = createSignal(null)
  const stopWebStream = vi.fn()
  const state = {
    status: status as () => unknown,
    previewVisible,
    setPreviewVisible,
    lenses,
    settings,
    updateCamera: () => {},
    handleSelectLens: async () => undefined,
    streamNonce: () => 0,
    streamActionLoading: () => false,
    isRecording: () => false,
    captureMsg: () => '',
    liveAudioStatus: () => 'idle',
    recordingTimer: { formatElapsed: () => '00:00' },
    playerMode: () => 'mjpeg' as PlayerMode,
    setPlayerMode: () => {},
    handleCapture: () => {},
    handleStartWebStream: () => {},
    handleStopWebStream: stopWebStream,
    handleStartRtspStream: () => {},
    handleStopRtspStream: () => {},
    connectionLost: () => false,
    isViewer: () => false,
  }
  return {
    setStatus: setStatus as (v: unknown) => void,
    stopWebStream,
    state: state as unknown as NextState,
  }
}

describe('StreamPipHost', () => {
  let stage: HTMLDivElement

  beforeEach(() => {
    resetPipStoreForTests()
    document.body.innerHTML = ''
    stage = document.createElement('div')
    document.body.appendChild(stage)
    registerStageSlot(stage)
    // Deterministic start: away from the live stage, stream playing.
    hashRouter().navigate({ name: 'media' })
  })

  afterEach(() => cleanup())

  function host() {
    return document.querySelector('.nx-stream-host') as HTMLElement
  }

  function dock() {
    return document.querySelector('.nx-pip') as HTMLElement
  }

  function holding() {
    return document.querySelector('.nx-pip-holding') as HTMLElement
  }

  it('docks the player while away from live with the stream playing', async () => {
    const { state } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot')))
    expect(dock().classList.contains('nx-pip-hidden')).toBe(false)
    expect(dock().getAttribute('aria-hidden')).toBe('false')
  })

  it('hands the player to the stage slot on live and back without remounting it', async () => {
    const { state } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBeTruthy())
    // Capture identity BEFORE navigating: the re-queried host/player of a
    // remount would satisfy these assertions, the captured ones cannot.
    const hostBefore = host()
    const playerBefore = hostBefore.querySelector('.preview-img')
    expect(playerBefore).toBeTruthy()

    hashRouter().navigate({ name: 'live' })
    await waitFor(() => expect(hostBefore.parentElement).toBe(stage))
    // No dock element is rendered while the stage hosts the player.
    expect(dock().classList.contains('nx-pip-hidden')).toBe(true)

    hashRouter().navigate({ name: 'media' })
    await waitFor(() => expect(hostBefore.parentElement).toBe(document.querySelector('.nx-pip-slot')))
    // The round trip moved the same elements — no host or player replacement.
    expect(host()).toBe(hostBefore)
    expect(hostBefore.querySelector('.preview-img')).toBe(playerBefore)
  })

  it('parks the player in the holding pen when the stream stops', async () => {
    const { state, setStatus } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot')))
    setStatus({ streaming: { webStreamingActive: false, isActive: false } })
    await waitFor(() => expect(host().parentElement).toBe(holding()))
    expect(dock().classList.contains('nx-pip-hidden')).toBe(true)
  })

  it('dismiss (✕) hides the dock without stopping the stream', async () => {
    const { state, stopWebStream } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot')))
    fireEvent.click(document.querySelector('.nx-pip .nx-pip-btn:last-child') as HTMLElement)
    await waitFor(() => expect(dock().classList.contains('nx-pip-hidden')).toBe(true))
    expect(host().parentElement).toBe(holding())
    expect(stopWebStream).not.toHaveBeenCalled()
    // The dismissal survives navigation while the same stream session runs.
    hashRouter().navigate({ name: 'live' })
    hashRouter().navigate({ name: 'detection' })
    expect(dock().classList.contains('nx-pip-hidden')).toBe(true)
  })

  it('the dismissal resets when the stream session restarts', async () => {
    const { state, setStatus } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot')))
    fireEvent.click(document.querySelector('.nx-pip .nx-pip-btn:last-child') as HTMLElement)
    await waitFor(() => expect(dock().classList.contains('nx-pip-hidden')).toBe(true))
    setStatus({ streaming: { webStreamingActive: false, isActive: false } })
    setStatus({ streaming: { webStreamingActive: true, isActive: true } })
    await waitFor(() => expect(dock().classList.contains('nx-pip-hidden')).toBe(false))
    expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot'))
  })

  it('the stop button calls handleStopWebStream', async () => {
    const { state, stopWebStream } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot')))
    fireEvent.click(document.querySelector('.nx-pip-btn-stop') as HTMLElement)
    expect(stopWebStream).toHaveBeenCalledTimes(1)
  })

  it('the tap layer expands back to the live view', async () => {
    const { state } = fixtureState()
    render(() => <StreamPipHost state={state} />)
    await waitFor(() => expect(host().parentElement).toBe(document.querySelector('.nx-pip-slot')))
    fireEvent.click(document.querySelector('.nx-pip-tap') as HTMLElement)
    expect(window.location.hash).toBe('#/live')
    await waitFor(() => expect(host().parentElement).toBe(stage))
  })
})

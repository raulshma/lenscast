import { describe, expect, it, vi } from 'vitest'
import {
  createWhepPlayer,
  isFatalIceState,
  isWhepAnswer,
  whepSessionIdFromLocation,
  type WhepAnswerResponse,
  type WhepStatus,
} from './whepClient'

// The WHEP rung's offer/answer state machine, driven with a fake
// RTCPeerConnection and transport: the browser primitives are injected, so
// the whole ladder (offer → POST → answer → tracks → teardown) is pinned in
// the node environment — only the actual ICE/DTLS wire is device-bound.

/** A controllable RTCPeerConnection double recording every step. */
function makeFakePc() {
  const pc: any = {
    listeners: {} as Record<string, Array<() => void>>,
    transceivers: [] as Array<{ kind: string; direction?: string }>,
    createOfferCalls: 0,
    localDescription: null as { type: string; sdp: string } | null,
    remoteDescription: null as { type: string; sdp: string } | null,
    iceGatheringState: 'new',
    iceConnectionState: 'new',
    closed: false,
    ontrack: null as ((ev: { streams?: MediaStream[]; track?: unknown }) => void) | null,
    oniceconnectionstatechange: null as (() => void) | null,
    addTransceiver(kind: string, init: { direction: string }) {
      pc.transceivers.push({ kind, direction: init.direction })
    },
    addEventListener(type: string, cb: () => void) {
      ;(pc.listeners[type] ??= []).push(cb)
    },
    removeEventListener(type: string, cb: () => void) {
      pc.listeners[type] = (pc.listeners[type] ?? []).filter((f: () => void) => f !== cb)
    },
    async createOffer() {
      pc.createOfferCalls++
      return { type: 'offer', sdp: 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n' }
    },
    async setLocalDescription(desc: { type: string; sdp: string }) {
      pc.localDescription = desc
    },
    async setRemoteDescription(desc: { type: string; sdp: string }) {
      pc.remoteDescription = desc
    },
    close() {
      pc.closed = true
    },
    /** The gather-complete beat the client's listener waits on. */
    gatherComplete() {
      pc.iceGatheringState = 'complete'
      for (const cb of pc.listeners['icegatheringstatechange'] ?? []) cb()
    },
    /** A fatal ICE state surfaces through the on* callback. */
    emitIceState(state: string) {
      pc.iceConnectionState = state
      pc.oniceconnectionstatechange?.()
    },
  }
  return pc
}

function makeVideo() {
  const listeners: Record<string, Array<() => void>> = {}
  const video = {
    srcObject: null as MediaStream | null,
    error: null as MediaError | null,
    play: vi.fn(async () => {}),
    addEventListener(type: string, cb: () => void) {
      ;(listeners[type] ??= []).push(cb)
    },
    removeEventListener(type: string, cb: () => void) {
      listeners[type] = (listeners[type] ?? []).filter((f: () => void) => f !== cb)
    },
    /** The element raises its media error with `error` set (F-10). */
    emitMediaError() {
      video.error = { code: 3 } as MediaError
      for (const cb of listeners['error'] ?? []) cb()
    },
  }
  return video as unknown as HTMLVideoElement & { emitMediaError(): void }
}

const ANSWER_SDP = 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n'

function answerResponse(overrides: Partial<WhepAnswerResponse> = {}): WhepAnswerResponse {
  return {
    status: 201,
    contentType: 'application/sdp',
    body: ANSWER_SDP,
    location: '/whep/abc123',
    ...overrides,
  }
}

// ── pure helpers ──

describe('isWhepAnswer', () => {
  it('accepts the 201 convention and a tolerant 200', () => {
    expect(isWhepAnswer(answerResponse({ status: 201 }))).toBe(true)
    expect(isWhepAnswer(answerResponse({ status: 200 }))).toBe(true)
  })

  it('refuses error statuses and non-SDP bodies', () => {
    expect(isWhepAnswer(answerResponse({ status: 400 }))).toBe(false)
    expect(isWhepAnswer(answerResponse({ status: 503 }))).toBe(false)
    expect(isWhepAnswer(answerResponse({ body: '{"error":"nope"}' }))).toBe(false)
    expect(isWhepAnswer(answerResponse({ body: '' }))).toBe(false)
  })
})

describe('whepSessionIdFromLocation', () => {
  it('reads the id from relative and absolute Location headers', () => {
    expect(whepSessionIdFromLocation('/whep/abc123')).toBe('abc123')
    expect(whepSessionIdFromLocation('http://phone:8080/whep/abc123')).toBe('abc123')
    expect(whepSessionIdFromLocation('/whep/abc123/')).toBe('abc123/')
  })

  it('returns null without a usable id', () => {
    expect(whepSessionIdFromLocation(null)).toBeNull()
    expect(whepSessionIdFromLocation('')).toBeNull()
    expect(whepSessionIdFromLocation('/whep/')).toBeNull()
    expect(whepSessionIdFromLocation('/stream')).toBeNull()
  })
})

describe('isFatalIceState', () => {
  it('ends the rung on failed/closed only', () => {
    expect(isFatalIceState('failed')).toBe(true)
    expect(isFatalIceState('closed')).toBe(true)
    expect(isFatalIceState('disconnected')).toBe(false)
    expect(isFatalIceState('connected')).toBe(false)
    expect(isFatalIceState('checking')).toBe(false)
  })
})

// ── the offer/answer state machine ──

function setupPlayer(postOffer: (offer: string) => Promise<WhepAnswerResponse>) {
  const pc = makeFakePc()
  const statuses: WhepStatus[] = []
  const deleteSession = vi.fn(async () => {})
  const player = createWhepPlayer(
    { onStatus: (s) => statuses.push(s) },
    {
      newPeerConnection: () => pc as unknown as RTCPeerConnection,
      postOffer,
      deleteSession,
      gatherTimeoutMs: 50,
    },
  )
  return { pc, statuses, deleteSession, player }
}

describe('createWhepPlayer handshake', () => {
  it('walks negotiating → playing and wires the answer into the video element', async () => {
    const { pc, statuses, deleteSession, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    const video = makeVideo()
    const settled = player.start(video)

    // The offer is built recvonly for both media, then POSTed after gather.
    expect(pc.transceivers.map((t: { kind: string; direction: string }) => `${t.kind}:${t.direction}`)).toEqual([
      'video:recvonly',
      'audio:recvonly',
    ])
    pc.gatherComplete()
    await settled

    expect(statuses).toEqual(['negotiating', 'playing'])
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: ANSWER_SDP })
    expect(deleteSession).not.toHaveBeenCalled()

    // A track lands in the video element and autoplay is attempted.
    const stream = { id: 's' } as unknown as MediaStream
    pc.ontrack?.({ streams: [stream] })
    expect(video.srcObject).toBe(stream)
    expect(video.play).toHaveBeenCalled()
  })

  it('waits out the gather cap when gathering never completes', async () => {
    const { pc, statuses, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    const settled = player.start(makeVideo())
    await new Promise((r) => setTimeout(r, 80)) // > gatherTimeoutMs(50), no gatherComplete()
    await settled
    expect(statuses).toEqual(['negotiating', 'playing'])
    expect(pc.remoteDescription).not.toBeNull()
  })

  it('a refused offer lands on error and closes the peer without a DELETE', async () => {
    const { pc, statuses, deleteSession, player } = setupPlayer(() =>
      Promise.resolve(answerResponse({ status: 403, body: '{"error":"Authentication required"}' })),
    )
    await player.start(makeVideo())
    expect(statuses).toEqual(['negotiating', 'error'])
    expect(pc.closed).toBe(true)
    expect(deleteSession).not.toHaveBeenCalled()
    expect(pc.remoteDescription).toBeNull()
  })

  it('a thrown transport failure lands on error', async () => {
    const { statuses, player } = setupPlayer(() => Promise.reject(new Error('network down')))
    await player.start(makeVideo())
    expect(statuses).toEqual(['negotiating', 'error'])
  })

  it('a fatal ICE state after playing surfaces an error; disconnected waits out', async () => {
    const { pc, statuses, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    pc.gatherComplete()
    await player.start(makeVideo())
    pc.emitIceState('disconnected')
    expect(statuses).toEqual(['negotiating', 'playing'])
    pc.emitIceState('failed')
    expect(statuses).toEqual(['negotiating', 'playing', 'error'])
  })
})

describe('createWhepPlayer teardown', () => {
  it('stop after playing DELETEs the session resource and clears the element', async () => {
    const { pc, deleteSession, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    const video = makeVideo()
    pc.gatherComplete()
    await player.start(video)
    player.stop()
    expect(deleteSession).toHaveBeenCalledWith('abc123')
    expect(pc.closed).toBe(true)
    expect(video.srcObject).toBeNull()
  })

  it('stop mid-handshake aborts at the next checkpoint and DELETEs the session the answer created', async () => {
    let resolvePost!: (r: WhepAnswerResponse) => void
    const { pc, statuses, deleteSession, player } = setupPlayer(
      () => new Promise<WhepAnswerResponse>((resolve) => { resolvePost = resolve }),
    )
    const settled = player.start(makeVideo())
    // Let the handshake reach the POST (offer built, gather awaited out).
    await vi.waitFor(() => expect(resolvePost).toBeInstanceOf(Function))
    player.stop() // the viewer navigated away while the POST was in flight
    resolvePost(answerResponse()) // the server answers (and created the session) after the stop
    await settled
    expect(statuses).toEqual(['negotiating', 'idle'])
    expect(pc.remoteDescription).toBeNull() // the answer is never applied
    expect(pc.closed).toBe(true)
    // The run is dead but its session exists server-side — the losing run
    // cleans it up instead of leaving it to the reap (the orphan leak).
    expect(deleteSession).toHaveBeenCalledWith('abc123')
  })

  it('a superseded run DELETEs the session its in-flight offer created', async () => {
    const pending: Array<(r: WhepAnswerResponse) => void> = []
    const { pc, statuses, deleteSession, player } = setupPlayer(
      () => new Promise<WhepAnswerResponse>((resolve) => { pending.push(resolve) }),
    )
    const first = player.start(makeVideo())
    await vi.waitFor(() => expect(pending.length).toBe(1))
    const second = player.start(makeVideo()) // the element re-fired: a newer run takes over
    await vi.waitFor(() => expect(pending.length).toBe(2))
    pending[0](answerResponse()) // the losing run's offer lands late — server created its session
    pending[1](answerResponse())
    await Promise.all([first, second])
    expect(statuses).toEqual(['negotiating', 'idle', 'negotiating', 'playing'])
    // Exactly one live session remains: the loser's was DELETEd, the
    // winner's answer applied.
    expect(deleteSession).toHaveBeenCalledTimes(1)
    expect(deleteSession).toHaveBeenCalledWith('abc123')
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: ANSWER_SDP })
  })

  it('stop without a session is a safe no-op', () => {
    const { deleteSession, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    expect(() => player.stop()).not.toThrow()
    expect(deleteSession).not.toHaveBeenCalled()
  })
})

// ── element media errors (F-10): the failure the handshake cannot see ──
// The answer can "succeed" while its track is unplayable in this browser,
// and the element error can land before (or without) the caller's onError —
// the rung then sat on a black canvas while the device kept the session
// alive. The player watches the element itself.

describe('createWhepPlayer element media errors', () => {
  it('an element that mounted already-dead lands on error without negotiating', async () => {
    const postOffer = vi.fn(() => Promise.resolve(answerResponse()))
    const { pc, statuses, deleteSession, player } = setupPlayer(postOffer)
    const video = makeVideo()
    ;(video as unknown as { error: MediaError | null }).error = { code: 4 } as MediaError
    await player.start(video)
    expect(statuses).toEqual(['error'])
    expect(postOffer).not.toHaveBeenCalled()
    expect(pc.createOfferCalls).toBe(0)
    expect(deleteSession).not.toHaveBeenCalled()
  })

  it('a media error after playing owns the teardown: session DELETEd, peer closed, error verdict', async () => {
    const { pc, statuses, deleteSession, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    const video = makeVideo()
    pc.gatherComplete()
    await player.start(video)
    expect(statuses).toEqual(['negotiating', 'playing'])

    video.emitMediaError()
    expect(statuses).toEqual(['negotiating', 'playing', 'idle', 'error'])
    expect(deleteSession).toHaveBeenCalledWith('abc123')
    expect(pc.closed).toBe(true)
    expect(video.srcObject).toBeNull()

    // A second error event after the release is inert.
    video.emitMediaError()
    expect(statuses).toEqual(['negotiating', 'playing', 'idle', 'error'])
    expect(deleteSession).toHaveBeenCalledTimes(1)
  })

  it('a media error mid-handshake aborts before the answer lands', async () => {
    let resolvePost!: (r: WhepAnswerResponse) => void
    const { pc, statuses, deleteSession, player } = setupPlayer(
      () => new Promise<WhepAnswerResponse>((resolve) => { resolvePost = resolve }),
    )
    const video = makeVideo()
    const settled = player.start(video)
    await vi.waitFor(() => expect(resolvePost).toBeInstanceOf(Function))

    video.emitMediaError()
    resolvePost(answerResponse())
    await settled
    expect(statuses).toEqual(['negotiating', 'idle', 'error'])
    expect(pc.remoteDescription).toBeNull()
    expect(pc.closed).toBe(true)
    // The element died before the answer landed; the session the answer
    // created still gets DELETEd by the losing run (no orphan).
    expect(deleteSession).toHaveBeenCalledWith('abc123')
  })

  it('a stale media error after an explicit stop is inert', async () => {
    const { pc, statuses, deleteSession, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    const video = makeVideo()
    pc.gatherComplete()
    await player.start(video)
    player.stop()
    expect(statuses).toEqual(['negotiating', 'playing', 'idle'])
    expect(deleteSession).toHaveBeenCalledTimes(1)

    video.emitMediaError()
    expect(statuses).toEqual(['negotiating', 'playing', 'idle'])
    expect(deleteSession).toHaveBeenCalledTimes(1)
  })
})

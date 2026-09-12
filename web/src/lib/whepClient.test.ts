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
  return { srcObject: null as MediaStream | null, play: vi.fn(async () => {}) } as unknown as HTMLVideoElement
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

  it('stop mid-handshake aborts at the next checkpoint: no answer applied, no playing', async () => {
    let resolvePost!: (r: WhepAnswerResponse) => void
    const { pc, statuses, deleteSession, player } = setupPlayer(
      () => new Promise<WhepAnswerResponse>((resolve) => { resolvePost = resolve }),
    )
    const settled = player.start(makeVideo())
    // Let the handshake reach the POST (offer built, gather awaited out).
    await vi.waitFor(() => expect(resolvePost).toBeInstanceOf(Function))
    player.stop() // the viewer navigated away while the POST was in flight
    resolvePost(answerResponse())
    await settled
    expect(statuses).toEqual(['negotiating', 'idle'])
    expect(pc.remoteDescription).toBeNull()
    expect(pc.closed).toBe(true)
    expect(deleteSession).not.toHaveBeenCalled() // the handshake never earned a session id
  })

  it('stop without a session is a safe no-op', () => {
    const { deleteSession, player } = setupPlayer(() => Promise.resolve(answerResponse()))
    expect(() => player.stop()).not.toThrow()
    expect(deleteSession).not.toHaveBeenCalled()
  })
})

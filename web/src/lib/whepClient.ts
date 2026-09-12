// The WHEP (WebRTC-HTTP Egress Protocol) viewer client for the live
// preview's top ladder rung: one SDP offer POSTed to `/whep`, the answer SDP
// back (201 + `Location: /whep/{id}`), the remote description set, and the
// video element fed from the negotiated track. The transport is deliberately
// injectable (peer-connection factory + POST/DELETE lambdas) so the whole
// offer/answer state machine runs under vitest with fakes — the production
// defaults are plain `RTCPeerConnection` and `fetch` against same-origin
// `/whep` (same-origin dashboard fetches ride the session cookie; the
// X-Requested-With header supplies the CSRF cover POST/DELETE require).
//
// One-shot ICE, no trickle: the offer carries the viewer's gathered
// candidates inline (host candidates appear instantly; the gather wait is
// capped so a slow STUN probe can never stall the rung past its usefulness —
// the server answers with its own candidates either way).

export type WhepStatus = 'idle' | 'negotiating' | 'playing' | 'error'

/** The wire shape of the HTTP answer to the SDP offer POST. */
export interface WhepAnswerResponse {
  status: number
  contentType: string
  body: string
  /** The raw `Location` header, or null when the server sent none. */
  location: string | null
}

/**
 * Pure: is this HTTP answer a usable WHEP answer SDP? 201 Created is the
 * WHEP convention; 200 is tolerated. The body must open like SDP (`v=0`) —
 * a JSON error envelope from the auth gate or the handler is a refusal.
 */
export function isWhepAnswer(response: WhepAnswerResponse): boolean {
  if (response.status !== 201 && response.status !== 200) return false
  return response.body.trimStart().startsWith('v=')
}

/**
 * Pure: the session id from the answer's `Location` header
 * (`/whep/{id}`, absolute or relative). Null when the header is absent or
 * carries no id — the session still plays; there is just nothing to DELETE
 * on teardown (the server reaps it anyway once ICE dies).
 */
export function whepSessionIdFromLocation(location: string | null): string | null {
  if (!location) return null
  const trimmed = location.trim()
  const marker = '/whep/'
  const index = trimmed.lastIndexOf(marker)
  if (index < 0) return null
  const id = trimmed.substring(index + marker.length)
  return id.length > 0 ? id : null
}

/** Pure: the ICE states that end the rung (transient DISCONNECTED waits out, like the server's hold loop). */
export function isFatalIceState(state: string): boolean {
  return state === 'failed' || state === 'closed'
}

/** The gather-complete cap: host candidates are immediate, so waiting past this never buys connectivity on the LAN. */
const GATHER_TIMEOUT_MS = 1_500

export interface WhepPlayerDeps {
  /** Default: a plain `RTCPeerConnection` with no ICE servers (LAN host candidates; the server side carries the shared STUN setting). */
  newPeerConnection?: () => RTCPeerConnection
  /** Default: `POST /whep` with the offer SDP (same-origin, CSRF cover). */
  postOffer?: (offerSdp: string) => Promise<WhepAnswerResponse>
  /** Default: `DELETE /whep/{id}` — session teardown (component dispose, rung change). */
  deleteSession?: (sessionId: string) => Promise<unknown>
  /** The one-shot gather cap in ms. */
  gatherTimeoutMs?: number
}

export function createWhepPlayer(
  options: { onStatus?: (s: WhepStatus) => void } = {},
  deps: WhepPlayerDeps = {},
) {
  let run = 0
  let pc: RTCPeerConnection | null = null
  let sessionId: string | null = null
  let target: HTMLVideoElement | null = null

  const defaultNewPeerConnection = (): RTCPeerConnection => new RTCPeerConnection()

  const defaultPostOffer = (offerSdp: string): Promise<WhepAnswerResponse> =>
    fetch('/whep', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/sdp',
        'X-Requested-With': 'XMLHttpRequest',
      },
      body: offerSdp,
      credentials: 'same-origin',
      cache: 'no-store',
    }).then(async (response) => ({
      status: response.status,
      contentType: response.headers.get('content-type') ?? '',
      body: await response.text(),
      location: response.headers.get('location'),
    }))

  const defaultDeleteSession = (id: string): Promise<unknown> =>
    fetch(`/whep/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'same-origin',
      cache: 'no-store',
    })

  function setStatus(status: WhepStatus) {
    options.onStatus?.(status)
  }

  /** Resolves on gather-complete or the cap — whichever first. */
  function awaitGatheringComplete(peer: RTCPeerConnection, timeoutMs: number): Promise<void> {
    if (peer.iceGatheringState === 'complete') return Promise.resolve()
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer)
        peer.removeEventListener('icegatheringstatechange', onChange)
        resolve()
      }
      const timer = setTimeout(done, timeoutMs)
      const onChange = () => {
        if (peer.iceGatheringState === 'complete') done()
      }
      peer.addEventListener('icegatheringstatechange', onChange)
    })
  }

  /**
   * Runs one full offer/answer handshake into [video]; resolves once the
   * handshake settled (playing or error). A `stop()` (or a second `start()`)
   * aborts an in-flight handshake at the next checkpoint.
   */
  async function start(video: HTMLVideoElement): Promise<void> {
    stop()
    const myRun = ++run
    target = video
    setStatus('negotiating')
    try {
      const peer = (deps.newPeerConnection ?? defaultNewPeerConnection)()
      pc = peer

      // Recvonly transceivers: video is the point of the rung; audio rides
      // along when the device's mic arbitration lets the server answer it
      // (a video-only server rejects the audio m-line in its answer).
      peer.addTransceiver('video', { direction: 'recvonly' })
      peer.addTransceiver('audio', { direction: 'recvonly' })

      peer.ontrack = (event) => {
        if (run !== myRun || !target) return
        target.srcObject = event.streams[0] ?? new MediaStream([event.track])
        void target.play().catch(() => {})
      }
      peer.oniceconnectionstatechange = () => {
        if (run !== myRun) return
        if (isFatalIceState(peer.iceConnectionState)) setStatus('error')
      }

      const offer = await peer.createOffer()
      await peer.setLocalDescription(offer)
      await awaitGatheringComplete(peer, deps.gatherTimeoutMs ?? GATHER_TIMEOUT_MS)
      if (run !== myRun) return

      const localSdp = peer.localDescription?.sdp ?? offer.sdp
      if (!localSdp) throw new Error('no local offer SDP')
      const response = await (deps.postOffer ?? defaultPostOffer)(localSdp)
      if (run !== myRun) return
      if (!isWhepAnswer(response)) {
        throw new Error(`WHEP offer refused (HTTP ${response.status})`)
      }
      sessionId = whepSessionIdFromLocation(response.location)
      await peer.setRemoteDescription({ type: 'answer', sdp: response.body })
      if (run !== myRun) return
      setStatus('playing')
    } catch (error) {
      if (run !== myRun) return
      // A failed handshake owns its cleanup: the peer closes and a captured
      // session id is DELETEd so the device's encoder slot frees now, not at
      // the registry's reap.
      releasePeer()
      console.warn('WHEP play failed:', error)
      setStatus('error')
    }
  }

  /**
   * Tears the session down: closes the peer connection, clears the video
   * element, and DELETEs the server-side session resource (fire-and-forget —
   * the server reaps un-deleted sessions anyway). Safe to call any number of
   * times, mid-handshake included.
   */
  function stop(): void {
    run += 1
    const wasLive = pc !== null || sessionId !== null || target !== null
    releasePeer()
    if (wasLive) setStatus('idle')
  }

  /** Closes the live pieces (if any) and invalidates the in-flight run. */
  function releasePeer(): void {
    const peer = pc
    pc = null
    const id = sessionId
    sessionId = null
    if (target) {
      target.srcObject = null
      target = null
    }
    if (peer) {
      try {
        peer.ontrack = null
        peer.oniceconnectionstatechange = null
      } catch { /* aged-out binding */ }
      try {
        peer.close()
      } catch { /* already closed */ }
    }
    if (id) {
      void (deps.deleteSession ?? defaultDeleteSession)(id).catch(() => {})
    }
  }

  return { start, stop }
}

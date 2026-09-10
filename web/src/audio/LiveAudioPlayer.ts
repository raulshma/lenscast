// Live PCM audio engine: fetches a chunked little-endian int16 PCM stream,
// re-frames it into whole samples, and schedules buffers on a Web Audio clock
// with jitter-targeted playback. Browser-facing pieces (fetch, AudioContext,
// timers) are injectable so the framing math and reconnect ladder stay
// unit-testable in a node environment.

/**
 * 'blocked' = the AudioContext is suspended by the browser's autoplay
 * policy (no user gesture on the page yet): the stream is flowing, but
 * playback starts only after the first tap/keypress.
 */
export type LiveAudioStatus = 'idle' | 'connecting' | 'live' | 'blocked' | 'error'

export interface AudioBufferLike {
  duration: number
  getChannelData(channel: number): Float32Array
}

export interface AudioBufferSourceLike {
  buffer: AudioBufferLike | null
  connect(destination: unknown): void
  start(when?: number): void
}

/** Structural subset of AudioContext the engine touches (real AudioContext satisfies it). */
export interface AudioContextLike {
  currentTime: number
  state: AudioContextState
  destination: unknown
  resume(): Promise<void>
  close(): Promise<void>
  createBuffer(channelCount: number, frameCount: number, sampleRate: number): AudioBufferLike
  createBufferSource(): AudioBufferSourceLike
}

const MAX_RECONNECT_ATTEMPTS = 3
const RECONNECT_DELAY_MS = 2000

// How long resume() gets to settle before the session is declared autoplay-
// blocked. A gesture-carrying page resumes in milliseconds; a policy-suspended
// context never settles, and the promise would hang `start()` forever.
const RESUME_GRACE_MS = 400

const GESTURE_EVENTS = ['pointerdown', 'keydown', 'touchstart'] as const

// ── Pure pieces ──

export interface BufferPool {
  clear(): void
}

/**
 * Tiny resettable allocation pool. Playback buffers are allocated through
 * `AudioContext.createBuffer`, so the pool only carries the reset/clear
 * behavior the engine's `stop()` relies on.
 */
export function createBufferPool(): BufferPool {
  let pool: ArrayBuffer[] = []
  return {
    clear() {
      pool = []
    },
  }
}

export function concatBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  const merged = new Uint8Array(a.length + b.length)
  merged.set(a, 0)
  merged.set(b, a.length)
  return merged
}

export interface PcmFramer {
  /** Feed one raw network chunk; returns zero or more whole frames worth of bytes. */
  push(chunk: Uint8Array): Uint8Array
}

/** Re-assembles stream chunks into bytesPerFrame-aligned blocks, carrying partial bytes across pushes. */
export function createPcmFramer(bytesPerFrame: number): PcmFramer {
  let pending: Uint8Array = new Uint8Array(0)
  return {
    push(chunk: Uint8Array): Uint8Array {
      const merged = pending.length > 0 ? concatBytes(pending, chunk) : chunk
      const usableLength = merged.length - (merged.length % bytesPerFrame)
      pending = usableLength < merged.length ? merged.subarray(usableLength) : new Uint8Array(0)
      return usableLength > 0 ? merged.subarray(0, usableLength) : new Uint8Array(0)
    },
  }
}

/** Decodes interleaved int16 PCM into one normalized (−1..1) Float32Array per channel. */
export function decodePcmChannels(pcmBytes: Uint8Array, channelCount: number): Float32Array[] {
  const int16 = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, Math.floor(pcmBytes.byteLength / 2))
  const frameCount = Math.floor(int16.length / channelCount)
  if (frameCount <= 0) return []
  const channels: Float32Array[] = []
  for (let channel = 0; channel < channelCount; channel += 1) {
    const data = new Float32Array(frameCount)
    for (let i = 0; i < frameCount; i += 1) {
      data[i] = int16[i * channelCount + channel] / 32768
    }
    channels.push(data)
  }
  return channels
}

/** True when the playback clock drifted out of its jitter window and must re-anchor to `now + 0.05`. */
export function shouldResetPlaybackClock(now: number, playbackTime: number): boolean {
  return playbackTime < now - 0.1 || playbackTime > now + 0.35
}

// ── Engine ──

export interface LiveAudioPlayerOptions {
  /** Receives every status transition; the caller wires this to UI state. */
  onStatus: (status: LiveAudioStatus) => void
  fetchFn?: (input: string, init: { cache: 'no-store'; signal: AbortSignal }) => Promise<Response>
  /** Constructs a fresh AudioContext; defaults to window.AudioContext / webkitAudioContext. */
  createAudioContext?: (sampleRate: number) => AudioContextLike | null
  /** Inter-reconnect sleeper; defaults to a setTimeout promise. */
  delay?: (ms: number) => Promise<void>
  /**
   * Arms a one-shot-ish user-gesture hook (any tap/keypress resumes a
   * policy-suspended AudioContext); returns its disposer. Defaults to
   * window capture listeners; injectable for tests.
   */
  armUserGesture?: (onGesture: () => void) => () => void
}

export interface LiveAudioPlayer {
  /** Dedup key of the currently-started playback request ('' while stopped). */
  readonly key: string
  /** Starts playback, aborting any previous session. `key` is remembered for caller-side dedup. */
  start(url: string, key: string): Promise<void>
  /** Aborts the session, closes the context, resets clock and pool. Default clears the key. */
  stop(resetKey?: boolean): Promise<void>
}

function constructDefaultAudioContext(sampleRate: number): AudioContextLike | null {
  if (typeof window === 'undefined') return null
  const AudioContextCtor = (window as any).AudioContext || (window as any).webkitAudioContext
  if (!AudioContextCtor) return null
  return new AudioContextCtor({ latencyHint: 'interactive', sampleRate })
}

const defaultFetch = (input: string, init: { cache: 'no-store'; signal: AbortSignal }): Promise<Response> =>
  fetch(input, init)

const defaultDelay = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

function defaultArmUserGesture(onGesture: () => void): () => void {
  if (typeof window === 'undefined') return () => { }
  const handler = () => onGesture()
  GESTURE_EVENTS.forEach((event) => window.addEventListener(event, handler, { capture: true }))
  return () => GESTURE_EVENTS.forEach((event) => window.removeEventListener(event, handler, { capture: true }))
}

export function createLiveAudioPlayer(options: LiveAudioPlayerOptions): LiveAudioPlayer {
  const {
    onStatus,
    fetchFn = defaultFetch,
    createAudioContext = constructDefaultAudioContext,
    delay = defaultDelay,
    armUserGesture = defaultArmUserGesture,
  } = options

  let abortController: AbortController | null = null
  let audioContext: AudioContextLike | null = null
  let playbackTime = 0
  let session = 0
  let currentKey = ''
  let bufferPool = createBufferPool()
  let gestureDisposer: (() => void) | null = null

  function disposeGestureArm() {
    gestureDisposer?.()
    gestureDisposer = null
  }

  async function stop(resetKey = true) {
    if (resetKey) currentKey = ''
    session += 1
    abortController?.abort()
    abortController = null
    playbackTime = 0
    bufferPool.clear()
    disposeGestureArm()
    if (audioContext) {
      try { await audioContext.close() } catch { }
      audioContext = null
    }
    onStatus('idle')
  }

  async function ensureAudioContext(sampleRate: number): Promise<AudioContextLike | null> {
    if (!audioContext || audioContext.state === 'closed') {
      audioContext = createAudioContext(sampleRate)
    }
    return audioContext
  }

  /**
   * State probe as its own function: narrowing from an earlier check must
   * not survive the awaits in between (the race below can flip the state).
   */
  function isRunning(ctx: AudioContextLike): boolean {
    return ctx.state === 'running'
  }

  /**
   * Waits a bounded time for the context to run. A policy-suspended context's
   * resume() never settles, so the race's losing arm (the delay) is the only
   * way out — `false` means "autoplay-blocked, needs a user gesture".
   */
  async function waitUntilRunning(ctx: AudioContextLike): Promise<boolean> {
    if (isRunning(ctx)) return true
    try {
      await Promise.race([ctx.resume().catch(() => { }), delay(RESUME_GRACE_MS)])
    } catch { }
    return isRunning(ctx)
  }

  /** Resumes a policy-suspended context on the first user gesture; the next streamed chunk flips the status. */
  function armResumeOnUserGesture(ctx: AudioContextLike) {
    disposeGestureArm()
    gestureDisposer = armUserGesture(() => {
      void ctx.resume().catch(() => { })
    })
  }

  function schedulePcmChunk(ctx: AudioContextLike, pcmBytes: Uint8Array, sampleRate: number, channelCount: number) {
    const channels = decodePcmChannels(pcmBytes, channelCount)
    if (channels.length === 0) return
    const frameCount = channels[0].length

    const audioBuffer = ctx.createBuffer(channelCount, frameCount, sampleRate)
    for (let channel = 0; channel < channelCount; channel += 1) {
      audioBuffer.getChannelData(channel).set(channels[channel])
    }

    const source = ctx.createBufferSource()
    source.buffer = audioBuffer
    source.connect(ctx.destination)

    const now = ctx.currentTime
    if (shouldResetPlaybackClock(now, playbackTime)) {
      playbackTime = now + 0.05
    }
    source.start(playbackTime)
    playbackTime += audioBuffer.duration
  }

  async function start(url: string, key: string) {
    currentKey = key
    await stop(false)
    const sessionId = ++session
    const controller = new AbortController()
    abortController = controller
    onStatus('connecting')

    let reconnectAttempts = 0

    async function attemptConnection() {
      try {
        const res = await fetchFn(url, { cache: 'no-store', signal: controller.signal })
        if (!res.ok || !res.body) throw new Error(`Audio stream unavailable: ${res.status}`)

        const sampleRate = parseInt(res.headers.get('X-Audio-Sample-Rate') || '48000', 10)
        const channelCount = parseInt(res.headers.get('X-Audio-Channels') || '1', 10)
        const bytesPerFrame = 2 * channelCount
        const ctx = await ensureAudioContext(sampleRate)
        if (!ctx) throw new Error('Web Audio not supported')

        const running = await waitUntilRunning(ctx)
        if (running) {
          onStatus('live')
        } else {
          onStatus('blocked')
          armResumeOnUserGesture(ctx)
        }
        playbackTime = ctx.currentTime + 0.05
        reconnectAttempts = 0

        const reader = res.body.getReader()
        const framer = createPcmFramer(bytesPerFrame)
        let announcedLive = running

        while (sessionId === session) {
          const { value, done } = await reader.read()
          if (done) break
          if (!value || value.length === 0) continue

          const aligned = framer.push(value)
          if (aligned.length === 0) continue
          // A suspended clock is frozen: scheduling against it would stack
          // every buffer on one instant and blast them all out on resume.
          // Drop chunks instead — live audio joins at the live edge.
          if (ctx.state !== 'running') continue

          if (!announcedLive) {
            announcedLive = true
            disposeGestureArm()
            onStatus('live')
            playbackTime = ctx.currentTime + 0.05
          }
          schedulePcmChunk(ctx, aligned, sampleRate, channelCount)
        }

        disposeGestureArm()
        if (!controller.signal.aborted && sessionId === session) {
          onStatus('idle')
        }
      } catch (e) {
        if (!controller.signal.aborted && sessionId === session) {
          if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            await delay(RECONNECT_DELAY_MS)
            if (sessionId === session && !controller.signal.aborted) {
              await attemptConnection()
            }
          } else {
            onStatus('error')
          }
        }
      }
    }

    await attemptConnection()
  }

  return {
    get key() { return currentKey },
    start,
    stop,
  }
}

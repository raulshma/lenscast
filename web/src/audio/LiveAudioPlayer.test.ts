import { describe, expect, it } from 'vitest'
import {
  concatBytes,
  createBufferPool,
  createLiveAudioPlayer,
  createPcmFramer,
  decodePcmChannels,
  shouldResetPlaybackClock,
  type AudioContextLike,
  type LiveAudioStatus,
} from './LiveAudioPlayer'

function u8(...bytes: number[]): Uint8Array {
  return new Uint8Array(bytes)
}

function pcmBytes(...samples: number[]): Uint8Array {
  return new Uint8Array(new Int16Array(samples).buffer)
}

function fakeAudioContext(currentTime = 10) {
  const startedAt: number[] = []
  const ctx: AudioContextLike = {
    currentTime,
    state: 'running',
    destination: null,
    resume: async () => { },
    close: async () => { },
    createBuffer: (_channelCount, frameCount, sampleRate) => ({
      duration: frameCount / sampleRate,
      getChannelData: () => new Float32Array(frameCount),
    }),
    createBufferSource: () => ({
      buffer: null,
      connect: () => { },
      start: (when?: number) => {
        if (when !== undefined) startedAt.push(when)
      },
    }),
  }
  return { ctx, startedAt }
}

function pcmResponse(headers: Record<string, string>, chunks: Uint8Array[], breakAfterReads?: number): Response {
  let reads = 0
  const body = {
    getReader() {
      return {
        read: async () => {
          if (breakAfterReads !== undefined && reads >= breakAfterReads) throw new Error('connection reset')
          if (reads < chunks.length) {
            const value = chunks[reads++]
            return { done: false, value }
          }
          return { done: true, value: undefined }
        },
      }
    },
  }
  return { ok: true, status: 200, headers: { get: (name: string) => headers[name] ?? null }, body } as unknown as Response
}

function failedResponse(status: number): Response {
  return { ok: false, status, headers: { get: () => null }, body: null } as unknown as Response
}

const MONO_4800 = { 'X-Audio-Sample-Rate': '4800', 'X-Audio-Channels': '1' }

describe('concatBytes', () => {
  it('concatenates two byte arrays', () => {
    expect(Array.from(concatBytes(u8(1, 2), u8(3, 4, 5)))).toEqual([1, 2, 3, 4, 5])
  })
})

describe('createPcmFramer', () => {
  it('carries partial frames across pushes', () => {
    const framer = createPcmFramer(4)
    expect(Array.from(framer.push(u8(1, 2, 3)))).toEqual([])
    expect(Array.from(framer.push(u8(4, 5)))).toEqual([1, 2, 3, 4])
    expect(Array.from(framer.push(u8(6, 7, 8)))).toEqual([5, 6, 7, 8])
    expect(Array.from(framer.push(u8()))).toEqual([])
  })

  it('passes aligned chunks straight through', () => {
    const framer = createPcmFramer(4)
    expect(Array.from(framer.push(u8(1, 2, 3, 4, 5, 6, 7, 8)))).toEqual([1, 2, 3, 4, 5, 6, 7, 8])
    expect(Array.from(framer.push(u8(9, 10, 11, 12)))).toEqual([9, 10, 11, 12])
  })
})

describe('createBufferPool', () => {
  it('clears pooled state safely and repeatedly (engine stop path)', () => {
    const pool = createBufferPool()
    expect(() => {
      pool.clear()
      pool.clear()
    }).not.toThrow()
  })
})

describe('decodePcmChannels', () => {
  it('deinterleaves int16 samples into normalized per-channel data', () => {
    const channels = decodePcmChannels(pcmBytes(1024, -1024, 2048, -2048), 2)
    expect(channels).toHaveLength(2)
    expect(Array.from(channels[0])).toEqual([1024 / 32768, 2048 / 32768])
    expect(Array.from(channels[1])).toEqual([-1024 / 32768, -2048 / 32768])
  })

  it('drops trailing samples that do not fill a whole frame', () => {
    const channels = decodePcmChannels(pcmBytes(100, 200, 300), 2)
    expect(channels).toHaveLength(2)
    expect(Array.from(channels[0])).toEqual([100 / 32768])
    expect(Array.from(channels[1])).toEqual([200 / 32768])
  })

  it('returns nothing when there is not even one frame', () => {
    expect(decodePcmChannels(pcmBytes(100), 2)).toEqual([])
  })

  it('honours non-zero byte offsets from subarray input', () => {
    // subarray(2) skips the low byte of the first sample; honouring
    // byteOffset reads [100, 200], ignoring it would read [7, 100].
    const buffer = pcmBytes(7, 100, 200)
    const channels = decodePcmChannels(buffer.subarray(2), 1)
    expect(Array.from(channels[0])).toEqual([100 / 32768, 200 / 32768])
  })
})

describe('shouldResetPlaybackClock', () => {
  it('keeps the clock inside the jitter window and resets it outside', () => {
    expect(shouldResetPlaybackClock(10, 9.95)).toBe(false)
    expect(shouldResetPlaybackClock(10, 10.3)).toBe(false)
    expect(shouldResetPlaybackClock(10, 9.5)).toBe(true)
    expect(shouldResetPlaybackClock(10, 10.5)).toBe(true)
  })
})

describe('createLiveAudioPlayer reconnect ladder', () => {
  it('retries a failed connection three times before reporting error', async () => {
    let fetches = 0
    const delays: number[] = []
    const statuses: LiveAudioStatus[] = []
    const player = createLiveAudioPlayer({
      onStatus: (s) => statuses.push(s),
      fetchFn: async () => {
        fetches += 1
        return failedResponse(503)
      },
      createAudioContext: () => fakeAudioContext().ctx,
      delay: async (ms) => { delays.push(ms) },
    })

    await player.start('/audio?t=0', 'k0')

    expect(fetches).toBe(4) // initial attempt + 3 reconnects
    expect(delays).toEqual([2000, 2000, 2000])
    expect(statuses).toEqual(['idle', 'connecting', 'error'])
  })

  it('recovers when a reconnect succeeds and schedules framed buffers', async () => {
    let fetches = 0
    const statuses: LiveAudioStatus[] = []
    const { ctx, startedAt } = fakeAudioContext(10)
    const chunk = pcmBytes(100, -100, 200, -200)
    const player = createLiveAudioPlayer({
      onStatus: (s) => statuses.push(s),
      fetchFn: async () => {
        fetches += 1
        if (fetches <= 2) return failedResponse(503)
        return pcmResponse(MONO_4800, [chunk, chunk])
      },
      createAudioContext: () => ctx,
      delay: async () => { },
    })

    await player.start('/audio?t=1', 'k1')

    expect(fetches).toBe(3)
    expect(statuses).toEqual(['idle', 'connecting', 'live', 'idle'])
    // Two 4-frame chunks scheduled back to back from the 0.05s anchor.
    expect(startedAt).toHaveLength(2)
    expect(startedAt[0]).toBeCloseTo(10.05, 6)
    expect(startedAt[1]).toBeCloseTo(10.05 + 4 / 4800, 6)
  })

  it('reconnects with a fresh attempt budget when the stream breaks mid-read', async () => {
    let fetches = 0
    const statuses: LiveAudioStatus[] = []
    const chunk = pcmBytes(1, 2, 3, 4)
    const player = createLiveAudioPlayer({
      onStatus: (s) => statuses.push(s),
      fetchFn: async () => {
        fetches += 1
        if (fetches === 1) return pcmResponse(MONO_4800, [chunk], 1)
        return pcmResponse(MONO_4800, [])
      },
      createAudioContext: () => fakeAudioContext().ctx,
      delay: async () => { },
    })

    await player.start('/audio', 'k')

    expect(fetches).toBe(2)
    expect(statuses).toEqual(['idle', 'connecting', 'live', 'live', 'idle'])
  })
})

describe('createLiveAudioPlayer session handling', () => {
  it('tracks the session key; stop(false) keeps it, stop() clears it', async () => {
    const statuses: LiveAudioStatus[] = []
    const player = createLiveAudioPlayer({
      onStatus: (s) => statuses.push(s),
      fetchFn: async () => failedResponse(503),
      createAudioContext: () => null,
      delay: async () => { },
    })

    await player.start('/audio', 'k1')
    expect(player.key).toBe('k1')

    await player.stop(false)
    expect(player.key).toBe('k1')

    await player.stop()
    expect(player.key).toBe('')
    expect(statuses[statuses.length - 1]).toBe('idle')
  })

  it('aborts the previous session when a new start replaces it', async () => {
    const signals: AbortSignal[] = []
    const player = createLiveAudioPlayer({
      onStatus: () => { },
      fetchFn: (_input, init) => new Promise<Response>((_resolve, reject) => {
        signals.push(init.signal)
        init.signal.addEventListener('abort', () => reject(new Error('Aborted')))
      }),
      createAudioContext: () => fakeAudioContext().ctx,
      delay: async () => { },
    })

    void player.start('/audio', 'a')
    await new Promise((resolve) => setTimeout(resolve, 0))
    void player.start('/audio', 'b')
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(signals.length).toBe(2)
    expect(signals[0].aborted).toBe(true)
    expect(signals[1].aborted).toBe(false)
    expect(player.key).toBe('b')

    await player.stop()
    expect(signals[1].aborted).toBe(true)
    expect(player.key).toBe('')
  })
})

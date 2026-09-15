import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { floatChunkToPcm16, PttFallbackUplink } from './pttFallback'

function pcmChunk(bytes: number[]): ArrayBuffer {
  return Uint8Array.from(bytes).buffer
}

function pcmBytes(batch: ArrayBuffer): number[] {
  return Array.from(new Uint8Array(batch))
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('floatChunkToPcm16', () => {
  it('scales the float range onto PCM16', () => {
    const out = new Int16Array(floatChunkToPcm16(Float32Array.of(0, 0.5, -0.25)))
    expect(Array.from(out)).toEqual([0, 16384, -8192])
  })

  it('clamps full-scale and hot-mic input instead of wrapping sign', () => {
    const out = new Int16Array(floatChunkToPcm16(Float32Array.of(1, -1, 2, -2)))
    expect(Array.from(out)).toEqual([32767, -32768, 32767, -32768])
  })
})

describe('PttFallbackUplink', () => {
  it('folds the chunks queued inside one batch window into a single POST', async () => {
    const posts: number[][] = []
    const uplink = new PttFallbackUplink(async (batch) => { posts.push(pcmBytes(batch)) })

    uplink.send(pcmChunk([1, 2]))
    uplink.send(pcmChunk([3]))
    await vi.advanceTimersByTimeAsync(600)

    expect(posts).toEqual([[1, 2, 3]])
  })

  it('opens a fresh batch for chunks arriving after the window closed', async () => {
    const posts: number[][] = []
    const uplink = new PttFallbackUplink(async (batch) => { posts.push(pcmBytes(batch)) })

    uplink.send(pcmChunk([1]))
    await vi.advanceTimersByTimeAsync(600)
    uplink.send(pcmChunk([2]))
    await vi.advanceTimersByTimeAsync(600)

    expect(posts).toEqual([[1], [2]])
  })

  it('drain flushes the pending queue without waiting for the window', async () => {
    const posts: number[][] = []
    const uplink = new PttFallbackUplink(async (batch) => { posts.push(pcmBytes(batch)) })

    uplink.send(pcmChunk([7, 8]))
    await uplink.drain()
    await vi.advanceTimersByTimeAsync(1_000)

    expect(posts).toEqual([[7, 8]])
  })

  it('serializes overlapping batches even when a POST is slow', async () => {
    const order: string[] = []
    let releaseFirst!: () => void
    const first = new Promise<void>((resolve) => { releaseFirst = resolve })
    const uplink = new PttFallbackUplink(async () => {
      order.push('post-start')
      if (order.length === 1) await first
      order.push('post-end')
    })

    uplink.send(pcmChunk([1]))
    await vi.advanceTimersByTimeAsync(600) // first POST starts, blocks
    uplink.send(pcmChunk([2]))
    const second = vi.advanceTimersByTimeAsync(600) // second batch wants the wire
    await Promise.resolve()
    releaseFirst()
    await second
    await uplink.drain()

    // The second POST must not start until the first has landed.
    expect(order).toEqual(['post-start', 'post-end', 'post-start', 'post-end'])
  })

  it('a failed POST is swallowed; the chain keeps carrying later batches', async () => {
    const posts: number[][] = []
    let failNext = true
    const uplink = new PttFallbackUplink(async (batch) => {
      if (failNext) {
        failNext = false
        throw new Error('sidecar flapped')
      }
      posts.push(pcmBytes(batch))
    })

    uplink.send(pcmChunk([1]))
    await vi.advanceTimersByTimeAsync(600)
    uplink.send(pcmChunk([2]))
    await uplink.drain() // must not reject over the swallowed failure

    expect(posts).toEqual([[2]])
  })

  it('drain on an empty uplink posts nothing and settles', async () => {
    const post = vi.fn()
    const uplink = new PttFallbackUplink(post)
    await expect(uplink.drain()).resolves.toBeUndefined()
    expect(post).not.toHaveBeenCalled()
  })
})

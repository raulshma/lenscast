// The push-to-talk HTTP fallback uplink — the share.ts pattern: the browser
// primitives (the talkback POST, timers) stay at the call site, and every
// batching/serialization decision is vitest-pinned without a browser.

/**
 * One mic float chunk → little-endian PCM16 bytes, clamped (not wrapped) at
 * full scale — a hot mic pegged past ±1.0 must distort, never flip sign.
 */
export function floatChunkToPcm16(input: Float32Array): ArrayBuffer {
  const pcm = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) pcm[i] = Math.max(-32768, Math.min(32767, input[i] * 32768))
  return pcm.buffer
}

/**
 * Where mic chunks detour when the /ws/talkback sidecar is down: chunks
 * accumulate for [batchMs], then each batch rides the one-shot HTTP talkback
 * uplink through a serialized chain — the device speaker track writes block,
 * and overlapping uploads would interleave in the server's thread pool. A
 * failed POST is swallowed so one dropped batch never kills the chain, and
 * [drain] flushes what is pending and waits out the in-flight chain, so a
 * released hold leaks no upload into the next one.
 */
export class PttFallbackUplink {
  private queue: ArrayBuffer[] = []
  private timer: ReturnType<typeof setTimeout> | null = null
  private chain: Promise<unknown> = Promise.resolve()

  constructor(
    private readonly post: (batch: ArrayBuffer) => Promise<unknown>,
    private readonly batchMs = 600,
  ) {}

  /** Queue one PCM16 chunk; the batch timer arms on the first queued chunk. */
  send(chunk: ArrayBuffer): void {
    this.queue.push(chunk)
    if (!this.timer) {
      this.timer = setTimeout(() => this.flush(), this.batchMs)
    }
  }

  /** Fold the queue into one batch and append its POST to the chain. */
  private flush(): void {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = null
    }
    if (this.queue.length === 0) return
    const total = this.queue.reduce((n, c) => n + c.byteLength, 0)
    const batch = new Uint8Array(total)
    let off = 0
    for (const c of this.queue) {
      batch.set(new Uint8Array(c), off)
      off += c.byteLength
    }
    this.queue = []
    const uploaded = this.post(batch.buffer).catch(() => {})
    this.chain = this.chain.then(() => uploaded)
  }

  /** Stop batching, flush what is queued, and wait for the chain to land. */
  async drain(): Promise<void> {
    this.flush()
    const settled = this.chain
    this.chain = Promise.resolve()
    await settled
  }
}

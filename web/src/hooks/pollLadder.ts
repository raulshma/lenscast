// Multi-rate poll scheduler: one wall-clock interval drives several named
// lanes, each firing every N ticks. Pure tick-count math — the caller owns
// the actual fetches, the visibility listener, and the effect lifetime.

export interface PollLane {
  /** Fire once the lane has accumulated this many ladder ticks. */
  everyTicks: number
  /**
   * Optional gate evaluated at fire time. While it returns false the lane
   * neither fires nor resets — ticks keep accumulating until it opens, then
   * a single fire happens (matching the old inline scheduler exactly).
   */
  enabled?: () => boolean
}

export interface PollLadderOptions {
  /** Master gate: while false a tick is a no-op and every counter freezes. */
  isVisible: () => boolean
  /** Wall time between ticks once started. Defaults to 1000ms. */
  tickMs?: number
}

export interface PollLadder<K extends string> {
  /** Advance one tick manually (start() drives this on its interval). */
  tick(): void
  start(): void
  stop(): void
  isRunning(): boolean
}

export function createPollLadder<K extends string>(
  lanes: Record<K, PollLane>,
  fire: (key: K) => void,
  options: PollLadderOptions,
): PollLadder<K> {
  const keys = Object.keys(lanes) as K[]
  const counters = {} as Record<K, number>
  for (const key of keys) counters[key] = 0

  function tick() {
    if (!options.isVisible()) return
    // All counters advance first, then lanes fire in declaration order —
    // fires are async fetches, so this keeps one tick's decisions independent.
    for (const key of keys) counters[key] += 1
    for (const key of keys) {
      const lane = lanes[key]
      if (counters[key] >= lane.everyTicks && (lane.enabled?.() ?? true)) {
        counters[key] = 0
        fire(key)
      }
    }
  }

  let timer: ReturnType<typeof setInterval> | null = null

  return {
    tick,
    start() {
      if (timer !== null) return
      timer = setInterval(tick, options.tickMs ?? 1000)
    },
    stop() {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
    },
    isRunning: () => timer !== null,
  }
}

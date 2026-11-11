// The SSE push lane's freshness verdict, extracted pure (the pttFallback
// shape: the browser primitives — the EventSource, the wall clock — stay at
// the call site, and the decision itself is vitest-pinned without one).

/**
 * The push lane's freshness bar: the server streams a status event every
 * second, so five seconds of silence (open socket or not) counts as a dead
 * lane and hands telemetry back to the poll ladder.
 */
export const SSE_STALE_MS = 5_000

/**
 * The freshness verdict — "connected" means the socket is OPEN, was open
 * recently, and events actually arrive. The 5 s alive-grace below covers the
 * open-but-no-event-yet window; a half-open socket (readyState OPEN forever
 * after the device vanished without a TCP reset) fails the event-age check
 * and reads as dead. Both windows keep their exact production values.
 */
export function sseLaneHealthy(
  nowMs: number,
  open: boolean,
  sseAliveAtMs: number,
  lastSseEventAtMs: number,
): boolean {
  if (!open) return false
  return nowMs - sseAliveAtMs < 5_000 && nowMs - lastSseEventAtMs < SSE_STALE_MS
}

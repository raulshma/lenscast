// The MJPEG preview rung's error-budget retry policy, extracted pure (the
// sseHealth shape: the browser primitives — the <img> element and the retry
// timer — stay at the call site, and the decisions themselves are
// vitest-pinned without one).

export const MAX_PREVIEW_ERRORS = 5
export const NO_HLS_RETRY_MS = 15_000

/** The under-budget retry backoff: 2 s per error, capped at 4 errors' worth (8 s). */
export function previewRetryDelayMs(errorCount: number): number {
  return 2_000 * Math.min(errorCount, 4)
}

/** The demotion verdict: the MJPEG rung is exhausted and an HLS rung exists to demote to. */
export function shouldDemoteToHls(errorCount: number, hlsAvailable: boolean): boolean {
  return errorCount >= MAX_PREVIEW_ERRORS && hlsAvailable
}

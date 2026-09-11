// Pure storage-quota forecast for StorageCard — the pollLadder pattern: all
// the days-remaining math on plain numbers, injected from /api/gallery
// (oldest/newest capture timestamps) and /api/system (used/quota bytes), so
// vitest can pin every insufficient-data guard without a browser.

const MS_PER_DAY = 86_400_000

/** Below this many days of capture history the average daily growth is noise. */
export const MIN_HISTORY_DAYS = 2

export interface StorageForecastInput {
  /** Oldest retained capture timestamp (ms); null when nothing is retained. */
  oldestCaptureMs: number | null
  /** Newest retained capture timestamp (ms); null when nothing is retained. */
  newestCaptureMs: number | null
  /** Capture-history storage currently in use (bytes, GET /api/system). */
  usedBytes: number
  /** Capture-history quota (bytes, GET /api/system). */
  quotaBytes: number
}

export interface StorageForecast {
  /** Days of capture history the estimate rests on. */
  daysSpanned: number
  /** Average growth per day over that span (bytes). */
  bytesPerDay: number
  /** Rounded whole days until the quota fills; 0 when already past it. */
  daysRemaining: number
}

/**
 * Best-effort days-until-quota estimate: average daily growth = used bytes
 * spread over the oldest→newest capture span, projected against the
 * remaining quota. Returns null when the data cannot support an estimate
 * (nothing used, no usable quota, or under MIN_HISTORY_DAYS of history) —
 * the caller hides the line rather than showing a made-up number.
 */
export function forecastStorage(input: StorageForecastInput): StorageForecast | null {
  const { oldestCaptureMs, newestCaptureMs, usedBytes, quotaBytes } = input
  if (!(quotaBytes > 0) || !(usedBytes > 0)) return null
  if (oldestCaptureMs === null || newestCaptureMs === null) return null
  const spanMs = newestCaptureMs - oldestCaptureMs
  if (!(spanMs > 0)) return null
  const daysSpanned = spanMs / MS_PER_DAY
  if (daysSpanned < MIN_HISTORY_DAYS) return null
  const bytesPerDay = usedBytes / daysSpanned
  if (!(bytesPerDay > 0)) return null
  const remainingBytes = quotaBytes - usedBytes
  const daysRemaining = remainingBytes <= 0 ? 0 : Math.round(remainingBytes / bytesPerDay)
  return { daysSpanned, bytesPerDay, daysRemaining }
}

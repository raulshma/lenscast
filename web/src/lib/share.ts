// Pure share/deep-link helpers — the push.ts pattern: every decision takes
// structural inputs (no DOM globals), so vitest pins the URL shapes and the
// Web Share capability ladder without a browser.

/** Structural slice of `Location` for building absolute LAN URLs. */
export interface LocationLike {
  origin: string
  pathname: string
  hash?: string
}

/** The dashboard's absolute LAN URL — what another device types into its browser. */
export function dashboardUrl(location: LocationLike): string {
  return `${location.origin}${location.pathname}`
}

/** The hash deep link that opens the gallery viewer on one media item. */
export function mediaHash(mediaId: string): string {
  return `#/gallery/${encodeURIComponent(mediaId)}`
}

/** The absolute per-media share URL: dashboard URL + the viewer deep link. */
export function mediaShareUrl(location: LocationLike, mediaId: string): string {
  return `${dashboardUrl(location)}${mediaHash(mediaId)}`
}

/** Structural slice of `navigator` for the Web Share capability ladder. */
export interface NavigatorShareLike {
  share?: unknown
  canShare?: unknown
}

/** True when this browser exposes navigator.share at all. */
export function canShare(navigator: NavigatorShareLike): boolean {
  return typeof navigator.share === 'function'
}

/**
 * True when this browser can share *files* — the Web Share API Level 2
 * surface the snapshot share wants (navigator.canShare with a File payload).
 * Falls back to false on Level 1 browsers, where the caller downloads
 * instead of sharing.
 */
export function canShareFiles(navigator: NavigatorShareLike): boolean {
  if (typeof navigator.share !== 'function' || typeof navigator.canShare !== 'function') return false
  try {
    // A 0-byte File is enough for the capability probe; canShare only
    // inspects the payload's type/fields, it never reads the bytes.
    const probe = typeof File !== 'undefined'
      ? new File([], 'probe.jpg', { type: 'image/jpeg' })
      : { file: true } // tests without the File global: structural stand-in
    return (navigator.canShare as (data: unknown) => boolean)({ files: [probe] })
  } catch {
    return false
  }
}

/** A `YYYYMMDD_HHmmss` file-stamp sibling of the server's media naming. */
export function snapshotFileName(now: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `SNAP_${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}_${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}.jpg`
}

/** How a snapshot share attempt ended — the caller maps each to UI feedback. */
export type SnapshotShareOutcome = 'shared' | 'downloaded' | 'cancelled'

function isAbort(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as { name?: unknown }).name === 'AbortError'
}

/**
 * Share a fetched snapshot image: the Web Share API with a File payload when
 * the browser can share files (Level 2), a programmatic download otherwise —
 * the same trigger the gallery's batch download uses. Over injected
 * primitives so both rungs, the user-cancel case and the unsupported
 * failure are all pinned by vitest without a browser.
 */
export async function shareSnapshotImage(
  blob: Blob,
  fileName: string,
  deps: {
    shareFiles?: (data: { files: unknown[]; title: string }) => Promise<void>
    download?: (blob: Blob, fileName: string) => void
  } = {},
): Promise<SnapshotShareOutcome> {
  if (typeof deps.shareFiles === 'function' && typeof File !== 'undefined') {
    const file = new File([blob], fileName, { type: blob.type || 'image/jpeg' })
    try {
      await deps.shareFiles({ files: [file], title: fileName })
      return 'shared'
    } catch (e) {
      // The user closing the share sheet is not an error — every other
      // share failure falls through to the download rung.
      if (isAbort(e)) return 'cancelled'
    }
  }
  if (typeof deps.download === 'function') {
    deps.download(blob, fileName)
    return 'downloaded'
  }
  throw new Error('Sharing is not available in this browser')
}

/**
 * Clipboard write with a legacy fallback, over injected primitives so the
 * ordering is testable: the async Clipboard API first, then the deprecated
 * execCommand path, false when both are unavailable.
 */
export async function copyText(
  text: string,
  deps: {
    writeText?: (t: string) => Promise<void>
    execCopy?: (t: string) => boolean
  } = {},
): Promise<boolean> {
  if (typeof deps.writeText === 'function') {
    try {
      await deps.writeText(text)
      return true
    } catch {
      // fall through to the legacy path
    }
  }
  if (typeof deps.execCopy === 'function') {
    try {
      return deps.execCopy(text)
    } catch {
      return false
    }
  }
  return false
}

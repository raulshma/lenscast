import { createSignal } from 'solid-js'

// Module-level media viewer store — the one shared "open this clip" surface.
// The Gallery viewer used to be trapped inside the Gallery component, so the
// EventFeed's "View clip" and the timeline's segment links had to open raw
// /api/media/{id} routes in new tabs. Any component now calls openMedia(id)
// and the single MediaViewerOverlay (mounted once in App) renders the same
// viewer the gallery uses, without leaving the current tab.

export type ViewerMediaType = 'PHOTO' | 'VIDEO'

/**
 * What the caller knows about the media. EventFeed and the timeline know the
 * clip is a video (and usually its file name); a bare #/gallery/<id> deep
 * link knows nothing, so the overlay resolves the item from the gallery list.
 */
export interface ViewerTarget {
  id: string
  type?: ViewerMediaType
  fileName?: string | null
}

/**
 * The narrow structural shape the viewer renders — GalleryItem minus the
 * fields it does not use. Kept local (not types.ts) because types.ts mirrors
 * the server DTO surface and this is a client-only view model.
 */
export interface ViewerMedia {
  id: string
  type: ViewerMediaType
  fileName: string
  /** Full-size media route — the viewer's source for photos. */
  url: string
  thumbnailUrl: string
  downloadUrl: string
}

const [target, setTarget] = createSignal<ViewerTarget | null>(null)

/** The currently requested media, or null when the viewer is closed. */
export const viewerTarget = target

/** Open the overlay viewer on a media id, with whatever metadata is known. */
export function openMedia(id: string, hint: { type?: ViewerMediaType; fileName?: string | null } = {}) {
  if (!id) return
  setTarget({
    id,
    type: hint.type,
    fileName: hint.fileName ?? null,
  })
}

/** Close the overlay viewer (no-op when already closed). */
export function closeMedia() {
  setTarget(null)
}

// ── Pure resolution helpers ──

function isViewerType(value: unknown): value is ViewerMediaType {
  return value === 'PHOTO' || value === 'VIDEO'
}

/**
 * Resolve a target against a gallery page: a known id keeps the server's
 * rich row (name, type, thumbnails). Returns null when the page does not
 * contain the id — the caller then falls back to `fallbackViewerMedia`.
 */
export function resolveViewerMedia(
  target: ViewerTarget,
  items: ReadonlyArray<{ id: string; type: unknown; fileName: unknown; url: unknown; thumbnailUrl: unknown; downloadUrl: unknown }>,
): ViewerMedia | null {
  const row = items.find((item) => item.id === target.id)
  if (!row) return null
  const type = isViewerType(row.type) ? row.type : target.type ?? 'VIDEO'
  return {
    id: row.id,
    type,
    fileName: typeof row.fileName === 'string' ? row.fileName : target.fileName ?? target.id,
    url: typeof row.url === 'string' ? row.url : `/api/media/${encodeURIComponent(target.id)}`,
    thumbnailUrl: typeof row.thumbnailUrl === 'string' ? row.thumbnailUrl : '',
    downloadUrl: typeof row.downloadUrl === 'string'
      ? row.downloadUrl
      : `/api/media/${encodeURIComponent(target.id)}?download=1`,
  }
}

/**
 * Map a gallery row onto the viewer's view model — the gallery grid always
 * knows the full row, so its viewer renders without any resolution pass.
 * Structurally typed (not GalleryItem) to keep this module server-DTO-free.
 */
export function toViewerMedia(item: {
  id: string
  type: unknown
  fileName: string
  url: string
  thumbnailUrl: string
  downloadUrl: string
}): ViewerMedia {
  return {
    id: item.id,
    type: isViewerType(item.type) ? item.type : 'VIDEO',
    fileName: item.fileName,
    url: item.url,
    thumbnailUrl: item.thumbnailUrl,
    downloadUrl: item.downloadUrl,
  }
}

/**
 * The no-metadata view of a target: clips opened from the event feed and the
 * timeline are always videos (bounded motion/sound recordings), so an absent
 * type renders the video player — the one guess that keeps deep links and
 * external opens working without a gallery round-trip.
 */
export function fallbackViewerMedia(target: ViewerTarget): ViewerMedia {
  const id = encodeURIComponent(target.id)
  return {
    id: target.id,
    type: target.type ?? 'VIDEO',
    fileName: target.fileName ?? target.id,
    url: `/api/media/${id}`,
    thumbnailUrl: '',
    downloadUrl: `/api/media/${id}?download=1`,
  }
}

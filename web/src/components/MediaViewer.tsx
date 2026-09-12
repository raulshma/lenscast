import { createEffect, createSignal, onCleanup, Show } from 'solid-js'
import type { ViewerMedia } from '../lib/viewerStore'
import {
  closeMedia,
  fallbackViewerMedia,
  resolveViewerMedia,
  viewerTarget,
} from '../lib/viewerStore'
import { copyText, mediaShareUrl } from '../lib/share'
import { useZoomable } from '../hooks/useZoomable'
import * as api from '../api/client'
import { t } from '../lib/i18n'

/**
 * Copy-link button with an inline toast: the per-media share URL (the
 * #/gallery/<id> deep link) onto the clipboard, "Copied!" for 1.5 s.
 */
function CopyLinkButton(props: { url: () => string }) {
  const [copied, setCopied] = createSignal(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  onCleanup(() => { if (timer) clearTimeout(timer) })

  async function handleCopy() {
    const ok = await copyText(props.url(), {
      writeText: (t) => navigator.clipboard.writeText(t),
      execCopy: (t) => {
        const el = document.createElement('textarea')
        el.value = t
        el.style.position = 'fixed'
        el.style.opacity = '0'
        document.body.appendChild(el)
        el.select()
        const okExec = document.execCommand('copy')
        document.body.removeChild(el)
        return okExec
      },
    })
    if (ok) {
      setCopied(true)
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => setCopied(false), 1500)
    }
  }

  return (
    <button type="button" class="action-btn action-btn-ghost" onClick={() => void handleCopy()}>
      <Show when={copied()} fallback={
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
          <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71" />
          <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" />
        </svg>
      }>
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
          <path d="M20 6L9 17l-5-5" />
        </svg>
      </Show>
      <span>{copied() ? t('viewer.copied') : t('viewer.copyLink')}</span>
    </button>
  )
}

/**
 * The single full-screen media viewer — photos with wheel/pinch zoom and pan
 * (the same useZoomable engine as the live preview), videos with the native
 * player over the ranged /api/media route, plus download, the per-media
 * share-link copy, and an optional delete (the gallery's integration).
 */
export function MediaViewer(props: {
  item: ViewerMedia
  onClose: () => void
  canDelete?: boolean
  onDelete?: () => void
  deleting?: boolean
}) {
  const [videoLoading, setVideoLoading] = createSignal(false)
  const zoom = useZoomable({ minScale: 1, maxScale: 8, wheelZoomFactor: 0.15 })

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      props.onClose()
    }
  }

  createEffect(() => {
    document.addEventListener('keydown', handleKeyDown)
    onCleanup(() => document.removeEventListener('keydown', handleKeyDown))
  })

  const shareUrl = () => mediaShareUrl(
    { origin: location.origin, pathname: location.pathname },
    props.item.id,
  )

  return (
    <div class="gallery-viewer" onClick={props.onClose}>
      <div
        class="gallery-viewer-content zoomable-container"
        ref={zoom.containerRef}
        onClick={(e) => e.stopPropagation()}
      >
        <Show when={props.item.type === 'PHOTO'} fallback={
          <div style={{ position: 'relative', display: 'flex', 'align-items': 'center', 'justify-content': 'center', width: '100%', height: '100%' }}>
            <Show when={videoLoading()}>
              <div style={{ position: 'absolute', 'z-index': 10, display: 'flex', 'align-items': 'center', 'justify-content': 'center', background: 'rgba(0,0,0,0.5)', inset: 0, 'border-radius': 'var(--lc-radius)' }}>
                <div class="login-spinner" style={{ width: '40px', height: '40px', 'border-color': 'rgba(255,255,255,0.3)', 'border-top-color': '#fff' }}></div>
              </div>
            </Show>
            <video
              src={props.item.url}
              controls
              autoplay
              preload="metadata"
              class="gallery-viewer-media"
              poster={props.item.thumbnailUrl ? `${props.item.thumbnailUrl}` : undefined}
              onLoadStart={() => setVideoLoading(true)}
              onWaiting={() => setVideoLoading(true)}
              onCanPlay={() => setVideoLoading(false)}
              onPlaying={() => setVideoLoading(false)}
            />
          </div>
        }>
          <img
            src={props.item.url}
            alt={props.item.fileName}
            class="gallery-viewer-media zoomable-content"
            draggable={false}
            style={{
              transform: `scale(${zoom.scale()}) translate(${zoom.translateX()}px, ${zoom.translateY()}px)`,
            }}
          />
        </Show>
      </div>
      <div class="gallery-viewer-bar" onClick={(e) => e.stopPropagation()}>
        <div class="viewer-info">
          <span class="viewer-name">{props.item.fileName}</span>
        </div>
        <div class="flex items-center gap-2">
          <CopyLinkButton url={shareUrl} />
          <a class="action-btn action-btn-ghost" href={props.item.downloadUrl} download="">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>{t('gallery.download')}</span>
          </a>
          <Show when={props.canDelete && props.onDelete}>
            <button
              class="action-btn"
              style={{ color: 'var(--lc-danger)', 'border-color': 'rgba(244, 63, 94, 0.3)' }}
              onClick={() => props.onDelete!()}
              disabled={props.deleting}
            >
              <span>{props.deleting ? t('viewer.deleting') : t('common.delete')}</span>
            </button>
          </Show>
          <button class="navbar-icon-btn" onClick={props.onClose} title={t('common.close')} aria-label={t('viewer.closeAria')}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * The store-driven twin: mounted once in App, it renders MediaViewer for
 * whatever component called openMedia() — the event feed's "View clip", the
 * timeline's segment links, and #/gallery/<id> deep links — without leaving
 * the current tab. A target without a type hint resolves its metadata from
 * the gallery list first (names/thumbnails/typed rows); a hinted target
 * renders straight away with no round-trip.
 */
export function MediaViewerOverlay(props: { canDelete?: boolean }) {
  const target = viewerTarget
  const [resolved, setResolved] = createSignal<ViewerMedia | null>(null)

  createEffect(() => {
    const t = target()
    if (!t) {
      setResolved(null)
      return
    }
    if (t.type) {
      // The caller knew what it opened (event-feed/timeline clips are videos).
      setResolved(fallbackViewerMedia(t))
      return
    }
    let cancelled = false
    setResolved(null)
    void api.getGallery(undefined, 0, 100)
      .then((res) => {
        if (cancelled || target()?.id !== t.id) return
        setResolved(resolveViewerMedia(t, res.items) ?? fallbackViewerMedia(t))
      })
      .catch(() => {
        if (cancelled) return
        setResolved(fallbackViewerMedia(t))
      })
    onCleanup(() => { cancelled = true })
  })

  return (
    <Show when={target() && resolved()}>
      <MediaViewer item={resolved()!} onClose={closeMedia} canDelete={props.canDelete} />
    </Show>
  )
}

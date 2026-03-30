import { createSignal, createEffect, For, Show, onCleanup } from 'solid-js'
import * as api from './api/client'
import type { GalleryItem, GalleryFilter } from './types'

function formatFileSize(bytes: number): string {
  if (bytes <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

function formatDate(ts: number): string {
  const d = new Date(ts)
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) +
    ' ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function formatDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  const min = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  return `${min}:${sec.toString().padStart(2, '0')}`
}

export default function Gallery(props: { onClose: () => void }) {
  const [filter, setFilter] = createSignal<GalleryFilter>('ALL')
  const [items, setItems] = createSignal<GalleryItem[]>([])
  const [loading, setLoading] = createSignal(true)
  const [error, setError] = createSignal('')
  const [viewer, setViewer] = createSignal<GalleryItem | null>(null)
  const [deleting, setDeleting] = createSignal<string | null>(null)
  const [selectMode, setSelectMode] = createSignal(false)
  const [selectedIds, setSelectedIds] = createSignal<Set<string>>(new Set())
  const [batchDeleting, setBatchDeleting] = createSignal(false)

  async function fetchGallery() {
    setLoading(true)
    setError('')
    try {
      const f = filter()
      const res = await api.getGallery(f === 'ALL' ? undefined : f)
      setItems(res.items)
    } catch (e: any) {
      setError(e.message || 'Failed to load gallery')
    } finally {
      setLoading(false)
    }
  }

  createEffect(() => {
    filter()
    fetchGallery()
  })

  function toggleSelectMode() {
    setSelectMode(!selectMode())
    setSelectedIds(new Set())
  }

  function toggleSelect(id: string) {
    const next = new Set(selectedIds())
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelectedIds(next)
  }

  function selectAll() {
    setSelectedIds(new Set(items().map(i => i.id)))
  }

  function selectNone() {
    setSelectedIds(new Set())
  }

  async function handleDelete(item: GalleryItem, e: Event) {
    e.stopPropagation()
    if (deleting()) return
    setDeleting(item.id)
    try {
      await api.deleteMedia(item.id)
      setItems(items().filter(i => i.id !== item.id))
      if (viewer()?.id === item.id) setViewer(null)
    } catch (e: any) {
      setError(e.message || 'Failed to delete')
    } finally {
      setDeleting(null)
    }
  }

  async function handleBatchDelete() {
    const ids = [...selectedIds()]
    if (ids.length === 0 || batchDeleting()) return
    if (!confirm(`Delete ${ids.length} item${ids.length > 1 ? 's' : ''}?`)) return
    setBatchDeleting(true)
    setError('')
    try {
      await api.deleteMediaBatch(ids)
      setItems(items().filter(i => !selectedIds().has(i.id)))
      setSelectedIds(new Set())
    } catch {
      setError('Batch delete failed, falling back to individual deletes...')
      let failed = 0
      for (const id of ids) {
        try {
          await api.deleteMedia(id)
          setItems(prev => prev.filter(i => i.id !== id))
        } catch {
          failed++
        }
      }
      if (failed > 0) setError(`Failed to delete ${failed} item(s)`)
      setSelectedIds(new Set())
    } finally {
      setBatchDeleting(false)
    }
  }

  function handleBatchDownload() {
    const selected = items().filter(i => selectedIds().has(i.id))
    for (const item of selected) {
      const a = document.createElement('a')
      a.href = item.downloadUrl
      a.download = item.fileName
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
    }
  }

  function handleItemClick(item: GalleryItem) {
    if (selectMode()) toggleSelect(item.id)
    else setViewer(item)
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      if (selectMode()) {
        setSelectMode(false)
        setSelectedIds(new Set())
      } else if (viewer()) {
        setViewer(null)
      } else {
        props.onClose()
      }
    }
  }

  createEffect(() => {
    document.addEventListener('keydown', handleKeyDown)
    onCleanup(() => document.removeEventListener('keydown', handleKeyDown))
  })

  const selectedCount = () => selectedIds().size
  const allSelected = () => items().length > 0 && selectedIds().size === items().length

  return (
    <div class="gallery-overlay">
      {/* Header */}
      <div class="gallery-header">
        <div class="flex items-center gap-3">
          <h2>Gallery</h2>
          <span class="gallery-count">{items().length} items</span>
        </div>
        <div class="gallery-header-actions">
          <Show when={!selectMode()}>
            <div class="gallery-filters">
              <For each={[['ALL', 'All'], ['PHOTO', 'Photos'], ['VIDEO', 'Videos']] as [GalleryFilter, string][]}>
                {([key, label]) => (
                  <button
                    class="gallery-filter-btn"
                    classList={{ 'gallery-filter-active': filter() === key }}
                    onClick={() => setFilter(key)}
                  >
                    {label}
                  </button>
                )}
              </For>
            </div>
          </Show>
          <button
            class="navbar-icon-btn"
            classList={{ 'navbar-icon-btn-active': selectMode() }}
            onClick={toggleSelectMode}
            title="Select multiple"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
              <path d="M6 6.5h1M17 6.5h1M6 17.5h1M17 17.5h1" stroke-width="2" stroke-linecap="round" />
            </svg>
          </button>
          <Show when={!selectMode()}>
            <button class="navbar-icon-btn" onClick={props.onClose} title="Close">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 6L6 18M6 6l12 12" />
              </svg>
            </button>
          </Show>
        </div>
      </div>

      {/* Select mode bar */}
      <Show when={selectMode()}>
        <div class="gallery-select-bar">
          <div class="flex items-center gap-3">
            <button class="action-btn action-btn-ghost" onClick={() => allSelected() ? selectNone() : selectAll()}>
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <Show when={allSelected()} fallback={
                  <rect x="3" y="3" width="18" height="18" rx="3" stroke-width="2" />
                }>
                  <rect x="3" y="3" width="18" height="18" rx="3" stroke-width="2" fill="currentColor" opacity="0.2" />
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                </Show>
              </svg>
              <span>{allSelected() ? 'Deselect all' : 'Select all'}</span>
            </button>
            <span class="text-xs" style={{ color: 'var(--lc-text-muted)' }}>
              {selectedCount()} selected
            </span>
          </div>
          <div class="flex items-center gap-2">
            <button
              class="action-btn action-btn-ghost"
              onClick={handleBatchDownload}
              disabled={selectedCount() === 0}
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
              </svg>
              <span>Download</span>
            </button>
            <button
              class="action-btn"
              style={{ color: 'var(--lc-danger)', 'border-color': 'rgba(244, 63, 94, 0.3)' }}
              onClick={handleBatchDelete}
              disabled={selectedCount() === 0 || batchDeleting()}
            >
              <Show when={batchDeleting()} fallback={
                <>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                  </svg>
                  <span>Delete</span>
                </>
              }>
                <span class="btn-spinner" />
                <span>Deleting...</span>
              </Show>
            </button>
            <button class="navbar-icon-btn" onClick={toggleSelectMode} title="Cancel">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 6L6 18M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
      </Show>

      {/* Content */}
      <div class="gallery-content">
        <Show when={error()}>
          <div class="error-banner" style={{ margin: '12px 16px' }}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <span>{error()}</span>
          </div>
        </Show>

        <Show when={loading()} fallback={
          <Show when={items().length > 0} fallback={
            <div class="gallery-empty">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" style={{ opacity: 0.3 }}>
                <rect x="3" y="3" width="7" height="7" rx="1.5" />
                <rect x="14" y="3" width="7" height="7" rx="1.5" />
                <rect x="3" y="14" width="7" height="7" rx="1.5" />
                <rect x="14" y="14" width="7" height="7" rx="1.5" />
              </svg>
              <span style={{ 'font-size': '14px', 'font-weight': '500' }}>No captures yet</span>
              <span style={{ 'font-size': '12px' }}>Photos and videos will appear here</span>
            </div>
          }>
            <div class="gallery-grid">
              <For each={items()}>
                {(item) => (
                  <div
                    class="gallery-item"
                    classList={{ 'gallery-item-selected': selectedIds().has(item.id) }}
                    onClick={() => handleItemClick(item)}
                    role="button"
                    tabindex="0"
                  >
                    <div class="gallery-thumb">
                      <Show when={selectMode()}>
                        <div class="gallery-select-check" classList={{ 'gallery-select-check-on': selectedIds().has(item.id) }}>
                          <Show when={selectedIds().has(item.id)}>
                            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
                            </svg>
                          </Show>
                        </div>
                      </Show>
                      <Show when={item.type === 'PHOTO'} fallback={
                        <div class="gallery-video-thumb">
                          <img
                            src={`${item.thumbnailUrl}?t=${item.timestamp}`}
                            alt={item.fileName}
                            loading="lazy"
                            onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none' }}
                          />
                          <div class="gallery-video-overlay">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                              <path d="M8 5v14l11-7z" />
                            </svg>
                            <span style={{ 'font-size': '11px', 'font-family': 'var(--lc-mono)' }}>{formatDuration(item.durationMs)}</span>
                          </div>
                        </div>
                      }>
                        <img src={item.thumbnailUrl} alt={item.fileName} loading="lazy" />
                      </Show>
                    </div>
                    <div class="gallery-item-info">
                      <span class="gallery-item-name">{item.fileName}</span>
                      <div class="gallery-item-meta">
                        <span>{formatDate(item.timestamp)}</span>
                        <Show when={item.fileSizeBytes > 0}>
                          <span>{formatFileSize(item.fileSizeBytes)}</span>
                        </Show>
                      </div>
                    </div>
                    <Show when={!selectMode()}>
                      <div class="gallery-item-actions">
                        <a
                          class="gallery-action-btn"
                          href={item.downloadUrl}
                          download
                          onClick={(e) => e.stopPropagation()}
                          title="Download"
                        >
                          <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
                            <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
                            <polyline points="7 10 12 15 17 10" />
                            <line x1="12" y1="15" x2="12" y2="3" />
                          </svg>
                        </a>
                        <button
                          class="gallery-action-btn gallery-action-btn-danger"
                          onClick={(e) => handleDelete(item, e)}
                          disabled={deleting() === item.id}
                          title="Delete"
                        >
                          <Show when={deleting() === item.id} fallback={
                            <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
                              <path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                            </svg>
                          }>
                            <span class="btn-spinner" style={{ width: '12px', height: '12px' }} />
                          </Show>
                        </button>
                      </div>
                    </Show>
                  </div>
                )}
              </For>
            </div>
          </Show>
        }>
          <div class="gallery-loading">
            <span class="btn-spinner" style={{ width: '24px', height: '24px', 'border-width': '3px' }} />
          </div>
        </Show>
      </div>

      {/* Viewer Modal */}
      <Show when={viewer()}>
        {(item) => (
          <div class="gallery-viewer" onClick={() => setViewer(null)}>
            <div class="gallery-viewer-content" onClick={(e) => e.stopPropagation()}>
              <Show when={item().type === 'PHOTO'} fallback={
                <video
                  src={`/api/media/${item().id}`}
                  controls
                  autoplay
                  preload="metadata"
                  class="gallery-viewer-media"
                  poster={item().thumbnailUrl}
                />
              }>
                <img src={item().thumbnailUrl} alt={item().fileName} class="gallery-viewer-media" />
              </Show>
            </div>
            <div class="gallery-viewer-bar" onClick={(e) => e.stopPropagation()}>
              <div class="viewer-info">
                <span class="viewer-name">{item().fileName}</span>
                <span class="viewer-date">{formatDate(item().timestamp)}</span>
              </div>
              <div class="flex items-center gap-2">
                <a class="action-btn action-btn-ghost" href={item().downloadUrl} download>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
                    <polyline points="7 10 12 15 17 10" />
                    <line x1="12" y1="15" x2="12" y2="3" />
                  </svg>
                  <span>Download</span>
                </a>
                <button
                  class="action-btn"
                  style={{ color: 'var(--lc-danger)', 'border-color': 'rgba(244, 63, 94, 0.3)' }}
                  onClick={() => handleDelete(item(), new Event('click'))}
                >
                  <span>Delete</span>
                </button>
                <button class="navbar-icon-btn" onClick={() => setViewer(null)}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M18 6L6 18M6 6l12 12" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
        )}
      </Show>
    </div>
  )
}

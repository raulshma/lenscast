import { createSignal, createMemo, createEffect, For, Show, onCleanup } from 'solid-js'
import * as api from './api/client'
import type { GalleryItem, GalleryFilter } from './types'
import { groupGalleryByDay, listCaptureDays } from './gallery/groupByDay'
import { filterByQuery } from './lib/mediaSearch'
import { toViewerMedia } from './lib/viewerStore'
import { MediaViewer } from './components/MediaViewer'
import { dayGroupLabel, formatDate, formatTime, t, tCount } from './lib/i18n'

/** How long typing waits before the search refetches the gallery. */
const SEARCH_DEBOUNCE_MS = 300

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

function formatDateLabel(ts: number): string {
  const d = new Date(ts)
  return formatDate(d, { month: 'short', day: 'numeric' }) +
    ' ' + formatTime(d, { hour: '2-digit', minute: '2-digit' })
}

function formatDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  const min = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  return `${min}:${sec.toString().padStart(2, '0')}`
}

export default function Gallery(props: {
  onClose: () => void
  readOnly?: () => boolean
  /** True while a higher overlay (shortcuts help, the shared media viewer) owns Escape. */
  overlayActive?: () => boolean
}) {
  // A viewer-role session hides the delete controls; downloads (reads) stay.
  const canDelete = () => props.readOnly?.() !== true
  const [filter, setFilter] = createSignal<GalleryFilter>('ALL')
  const [items, setItems] = createSignal<GalleryItem[]>([])
  const [loading, setLoading] = createSignal(true)
  const [error, setError] = createSignal('')
  const [viewer, setViewer] = createSignal<GalleryItem | null>(null)
  const [deleting, setDeleting] = createSignal<string | null>(null)
  const [selectMode, setSelectMode] = createSignal(false)
  const [selectedIds, setSelectedIds] = createSignal<Set<string>>(new Set<string>())
  const [batchDeleting, setBatchDeleting] = createSignal(false)
  const [page, setPage] = createSignal(0)
  const [hasMore, setHasMore] = createSignal(false)
  const [totalItems, setTotalItems] = createSignal(0)
  const [selectedDay, setSelectedDay] = createSignal('')
  // Filename search: the box owns searchInput; `query` is the debounced
  // value that both the fetch (q= param — newer servers filter server-side)
  // and the client-side filterByQuery (works against today's server) key off.
  const [searchInput, setSearchInput] = createSignal('')
  const [query, setQuery] = createSignal('')
  let searchTimer: ReturnType<typeof setTimeout> | null = null
  const PAGE_SIZE = 50

  function onSearchInput(value: string) {
    setSearchInput(value)
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(() => setQuery(value), SEARCH_DEBOUNCE_MS)
  }

  onCleanup(() => { if (searchTimer) clearTimeout(searchTimer) })

  // One memo per items() change; the day picker reads it from two places in
  // the header JSX (the Show gate and the option list).
  const captureDays = createMemo(() => listCaptureDays(items(), Date.now()))

  async function fetchGallery(resetPage = false) {
    if (resetPage) setPage(0)
    const currentPage = resetPage ? 0 : page()
    setLoading(currentPage === 0)
    setError('')
    try {
      const f = filter()
      const q = query().trim()
      const res = await api.getGallery(f === 'ALL' ? undefined : f, currentPage, PAGE_SIZE, q || undefined)
      if (currentPage === 0) {
        setItems(res.items)
      } else {
        setItems((prev) => [...prev, ...res.items])
      }
      setHasMore(res.hasMore)
      setTotalItems(res.total)
    } catch (e: any) {
      setError(e.message || t('gallery.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  function loadMore() {
    if (!hasMore() || loading()) return
    setPage((p) => p + 1)
  }

  createEffect(() => {
    filter()
    setSelectedDay('')
    fetchGallery(true)
  })

  // The debounced query refetches with q= (mount is covered by the filter
  // effect above — only actual query changes fetch here).
  createEffect((prev: string | undefined) => {
    const q = query()
    if (prev !== undefined && q !== prev) fetchGallery(true)
    return q
  })

  createEffect(() => {
    page()
    if (page() > 0) fetchGallery()
  })

  // Day sections for the grid — the loaded page is filtered by the search
  // query first (the client-side half of the q= search), then optionally to
  // the date-jump selection.
  const filteredItems = createMemo(() => filterByQuery(items(), query()))

  const visibleDayGroups = () => {
    const groups = groupGalleryByDay(filteredItems(), Date.now())
    const selected = selectedDay()
    return selected ? groups.filter((g) => g.key === selected) : groups
  }

  function toggleSelectMode() {
    setSelectMode(!selectMode())
    setSelectedIds(new Set<string>())
  }

  function toggleSelect(id: string) {
    const next = new Set(selectedIds())
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelectedIds(next)
  }

  function selectAll() {
    setSelectedIds(new Set<string>(items().map(i => i.id)))
  }

  function selectNone() {
    setSelectedIds(new Set<string>())
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
      setError(e.message || t('gallery.deleteFailed'))
    } finally {
      setDeleting(null)
    }
  }

  async function handleBatchDelete() {
    const ids = [...selectedIds()]
    if (ids.length === 0 || batchDeleting()) return
    if (!confirm(tCount('gallery.deleteConfirm', ids.length))) return
    setBatchDeleting(true)
    setError('')
    try {
      await api.deleteMediaBatch(ids)
      setItems(items().filter(i => !selectedIds().has(i.id)))
      setSelectedIds(new Set<string>())
    } catch {
      setError(t('gallery.batchFailed'))
      let failed = 0
      for (const id of ids) {
        try {
          await api.deleteMedia(id)
          setItems(prev => prev.filter(i => i.id !== id))
        } catch {
          failed++
        }
      }
      if (failed > 0) setError(t('gallery.batchPartial', { count: failed }))
      setSelectedIds(new Set<string>())
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
      // A higher overlay (shortcuts help, the shared media viewer) owns
      // Escape first — the gallery only handles it when none is up.
      if (props.overlayActive?.() === true) return
      if (selectMode()) {
        setSelectMode(false)
        setSelectedIds(new Set<string>())
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
          <h2>{t('gallery.title')}</h2>
          <span class="gallery-count">
            {query().trim() ? t('gallery.countFiltered', { filtered: filteredItems().length, total: items().length }) : tCount('gallery.count', items().length)}
          </span>
        </div>
        <div class="gallery-header-actions">
          <Show when={!selectMode()}>
            <Show when={captureDays().length > 1}>
              <select
                class="field-select gallery-day-select"
                title={t('gallery.jumpDay')}
                value={selectedDay()}
                onChange={(e) => setSelectedDay(e.currentTarget.value)}
              >
                <option value="">{t('gallery.allDays')}</option>
                <For each={captureDays()}>
                  {(day) => <option value={day.key}>{dayGroupLabel(day.label)}</option>}
                </For>
              </select>
            </Show>
            <div class="gallery-filters">
              <For each={[['ALL', () => t('gallery.filter.all')], ['PHOTO', () => t('gallery.filter.photos')], ['VIDEO', () => t('gallery.filter.videos')]] as [GalleryFilter, () => string][]}>
                {([key, label]) => (
                  <button
                    class="gallery-filter-btn"
                    classList={{ 'gallery-filter-active': filter() === key }}
                    onClick={() => setFilter(key)}
                  >
                    {label()}
                  </button>
                )}
              </For>
            </div>
          </Show>
          <button
            class="navbar-icon-btn"
            classList={{ 'navbar-icon-btn-active': selectMode() }}
            onClick={toggleSelectMode}
            title={t('gallery.selectMultiple')}
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
            <button class="navbar-icon-btn" onClick={props.onClose} title={t('common.close')}>
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
              <span>{allSelected() ? t('gallery.deselectAll') : t('gallery.selectAll')}</span>
            </button>
            <span class="text-xs" style={{ color: 'var(--lc-text-muted)' }}>
              {tCount('gallery.selected', selectedCount())}
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
              <span>{t('gallery.download')}</span>
            </button>
            <Show when={canDelete()}>
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
                    <span>{t('common.delete')}</span>
                  </>
                }>
                  <span class="btn-spinner" />
                  <span>{t('gallery.deleting')}</span>
                </Show>
              </button>
            </Show>
            <button class="navbar-icon-btn" onClick={toggleSelectMode} title={t('common.cancel')}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 6L6 18M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
      </Show>

      {/* Filename search — debounced; '/' focuses here from anywhere. */}
      <div class="gallery-search-bar">
        <svg class="gallery-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
        <input
          id="gallery-search-input"
          class="field-input gallery-search-input"
          type="search"
          placeholder={t('gallery.searchPlaceholder')}
          autocomplete="off"
          spellcheck={false}
          value={searchInput()}
          onInput={(e) => onSearchInput(e.currentTarget.value)}
        />
        <Show when={searchInput()}>
          <button type="button" class="navbar-icon-btn" title={t('gallery.clearSearch')} onClick={() => onSearchInput('')}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </Show>
      </div>

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
          <Show when={filteredItems().length > 0} fallback={
            <div class="gallery-empty">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" style={{ opacity: 0.3 }}>
                <rect x="3" y="3" width="7" height="7" rx="1.5" />
                <rect x="14" y="3" width="7" height="7" rx="1.5" />
                <rect x="3" y="14" width="7" height="7" rx="1.5" />
                <rect x="14" y="14" width="7" height="7" rx="1.5" />
              </svg>
              <Show when={query().trim()} fallback={
                <>
                  <span style={{ 'font-size': '14px', 'font-weight': '500' }}>{t('gallery.emptyTitle')}</span>
                  <span style={{ 'font-size': '12px' }}>{t('gallery.emptyHint')}</span>
                </>
              }>
                <span style={{ 'font-size': '14px', 'font-weight': '500' }}>{t('gallery.noMatches', { query: query().trim() })}</span>
                <span style={{ 'font-size': '12px' }}>{t('gallery.noMatchesHint')}</span>
              </Show>
            </div>
          }>
            <For each={visibleDayGroups()}>
              {(group) => (
                <div class="gallery-day-section">
                  <div class="gallery-day-header">
                    <span class="gallery-day-label">{dayGroupLabel(group.label)}</span>
                    <span class="gallery-day-count">{tCount('gallery.count', group.items.length)}</span>
                  </div>
                  <div class="gallery-grid">
                    <For each={group.items}>
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
                              <span>{formatDateLabel(item.timestamp)}</span>
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
                                download=""
                                onClick={(e) => e.stopPropagation()}
                                title={t('gallery.download')}
                              >
                                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
                                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
                                  <polyline points="7 10 12 15 17 10" />
                                  <line x1="12" y1="15" x2="12" y2="3" />
                                </svg>
                              </a>
                              <Show when={canDelete()}>
                                <button
                                  class="gallery-action-btn gallery-action-btn-danger"
                                  onClick={(e) => handleDelete(item, e)}
                                  disabled={deleting() === item.id}
                                  title={t('common.delete')}
                                >
                                  <Show when={deleting() === item.id} fallback={
                                    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
                                      <path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                                    </svg>
                                  }>
                                    <span class="btn-spinner" style={{ width: '12px', height: '12px' }} />
                                  </Show>
                                </button>
                              </Show>
                            </div>
                          </Show>
                        </div>
                      )}
                    </For>
                  </div>
                </div>
              )}
            </For>
            <Show when={hasMore()}>
              <div style={{ display: 'flex', 'justify-content': 'center', padding: '16px' }}>
                <button class="btn btn-outline btn-sm" onClick={loadMore} disabled={loading()}>
                  <Show when={loading()} fallback={<>{t('gallery.loadMore', { count: totalItems() - items().length })}</>}>
                    <span class="btn-spinner" style={{ width: '16px', height: '16px' }} />
                  </Show>
                </button>
              </div>
            </Show>
          </Show>
        }>
          <div class="gallery-loading">
            <span class="btn-spinner" style={{ width: '24px', height: '24px', 'border-width': '3px' }} />
          </div>
        </Show>
      </div>

      {/* Viewer Modal — the shared full-screen viewer (zoom, video player,
          download, per-media share link); the delete action stays wired to
          this grid so rows and state stay in sync. */}
      <Show when={viewer()}>
        {(item) => (
          <MediaViewer
            item={toViewerMedia(item())}
            onClose={() => setViewer(null)}
            canDelete={canDelete()}
            onDelete={() => handleDelete(item(), new Event('click'))}
            deleting={deleting() === item().id}
          />
        )}
      </Show>
    </div>
  )
}

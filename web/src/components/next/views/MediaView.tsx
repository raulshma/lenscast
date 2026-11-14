import { createSignal, createMemo, createEffect, For, Show, onCleanup } from 'solid-js'
import type { NextState } from '../NextState'
import type { GalleryFilter, GalleryItem } from '../../../types'
import * as api from '../../../api/client'
import { groupGalleryByDay } from '../../../gallery/groupByDay'
import { filterByQuery } from '../../../lib/mediaSearch'
import { openMedia } from '../../../lib/viewerStore'
import { dayGroupLabel, formatTime, t, tCount } from '../../../lib/i18n'
import { formatFileSize, formatDuration } from '../../../lib/format'
import { IconPhoto, IconFilm, IconSearch, IconTrash, IconCheck } from '../../icons'
import { Panel, Segmented, TextInput, Button, Badge, Empty, Skeleton, Spinner, ConfirmDialog } from '../ui'

/** How long typing waits before the search refetches the gallery. */
const SEARCH_DEBOUNCE_MS = 300
const PAGE_SIZE = 50

/**
 * The media browser, built for the console (the classic Gallery overlay
 * stays with the classic shell): filter chips, debounced filename search,
 * day-grouped thumbnail grid, multi-select + batch delete, paged loading.
 * Tiles open the shared media viewer (lib/viewerStore) — the same overlay
 * `#/media/<id>` deep links target. A viewer-role session can browse and
 * download but every delete control is hidden.
 */
export default function MediaView(props: { state: NextState }) {
  const state = props.state
  const canDelete = () => !state.isViewer()

  const [items, setItems] = createSignal<GalleryItem[]>([])
  const [loading, setLoading] = createSignal(true)
  const [loadingMore, setLoadingMore] = createSignal(false)
  const [error, setError] = createSignal('')
  const [filter, setFilter] = createSignal<GalleryFilter>('ALL')
  const [total, setTotal] = createSignal(0)
  const [hasMore, setHasMore] = createSignal(false)
  const [page, setPage] = createSignal(0)
  const [selectMode, setSelectMode] = createSignal(false)
  const [selectedIds, setSelectedIds] = createSignal<Set<string>>(new Set())
  const [batchDeleting, setBatchDeleting] = createSignal(false)
  // The confirm dialogs' snapshots, taken at open time (the Android viewer's
  // DeleteTarget pattern): the dialog deletes against what it showed, never
  // against whatever the list has become while it sat open.
  const [pendingDelete, setPendingDelete] = createSignal<GalleryItem | null>(null)
  const [deleteOneBusy, setDeleteOneBusy] = createSignal(false)
  const [confirmBatch, setConfirmBatch] = createSignal(false)
  const [searchInput, setSearchInput] = createSignal('')
  const [query, setQuery] = createSignal('')
  let searchTimer: ReturnType<typeof setTimeout> | null = null

  onCleanup(() => { if (searchTimer) clearTimeout(searchTimer) })

  // One fetch seam: page 0 replaces (filter/search flips), later pages append.
  // The server filters by type and (newer builds) by filename; the client-side
  // filterByQuery below also covers servers that predate the q param.
  async function fetchPage(nextPage: number, append: boolean) {
    if (append) setLoadingMore(true)
    else setLoading(true)
    try {
      const type = filter() === 'ALL' ? undefined : filter()
      const res = await api.getGallery(type, nextPage, PAGE_SIZE, query() || undefined)
      setItems((prev) => (append ? [...prev, ...res.items] : res.items))
      setTotal(res.total)
      setHasMore(res.hasMore)
      setPage(res.page)
      setError('')
    } catch (e: any) {
      setError(e?.message || t('gallery.loadFailed'))
    } finally {
      setLoading(false)
      setLoadingMore(false)
    }
  }

  void fetchPage(0, false)

  // Filter and debounced-search flips restart from page 0. Reading both
  // signals here tracks them; the fetch itself reads them again inside.
  createEffect(() => {
    const type = filter()
    const q = query()
    void fetchPage(0, false)
  })

  function onSearchInput(value: string) {
    setSearchInput(value)
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(() => setQuery(value), SEARCH_DEBOUNCE_MS)
  }

  const visibleItems = () => filterByQuery(items(), query())

  const dayGroups = createMemo(() => groupGalleryByDay(visibleItems(), Date.now()))

  const selectedCount = () => selectedIds().size

  function toggleSelect(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function openTile(item: GalleryItem) {
    if (selectMode()) {
      toggleSelect(item.id)
      return
    }
    openMedia(item.id, { type: item.type, fileName: item.fileName })
  }

  function deleteOne(item: GalleryItem) {
    setPendingDelete(item)
  }

  async function confirmDeleteOne() {
    const item = pendingDelete()
    if (!item || deleteOneBusy()) return
    setDeleteOneBusy(true)
    try {
      await api.deleteMedia(item.id)
      setItems((prev) => prev.filter((i) => i.id !== item.id))
      setTotal((n) => Math.max(0, n - 1))
    } catch (e: any) {
      setError(e?.message || t('gallery.deleteFailed'))
    } finally {
      setDeleteOneBusy(false)
      setPendingDelete(null)
    }
  }

  function deleteBatch() {
    if (selectedIds().size === 0 || batchDeleting()) return
    setConfirmBatch(true)
  }

  async function confirmBatchDelete() {
    const ids = [...selectedIds()]
    if (ids.length === 0) return
    setBatchDeleting(true)
    try {
      await api.deleteMediaBatch(ids)
      setItems((prev) => prev.filter((i) => !selectedIds().has(i.id)))
      setTotal((n) => Math.max(0, n - ids.length))
      setSelectedIds(new Set<string>())
      setSelectMode(false)
    } catch (e: any) {
      setError(e?.message || t('gallery.batchFailed'))
    } finally {
      setBatchDeleting(false)
      setConfirmBatch(false)
    }
  }

  return (
    <div class="nx-view">
      <div class="nx-view-inner nx-media">
        {/* Toolbar */}
        <div class="nx-media-toolbar">
          <Segmented
            options={[
              { value: 'ALL', label: t('gallery.filter.all') },
              { value: 'PHOTO', label: t('gallery.filter.photos') },
              { value: 'VIDEO', label: t('gallery.filter.videos') },
            ]}
            value={filter()}
            onChange={(v) => setFilter(v as GalleryFilter)}
            ariaLabel={t('gallery.jumpDay')}
          />
          <div class="nx-media-search">
            <IconSearch size={14} />
            <input
              id="gallery-search-input"
              class="nx-input nx-input-sm nx-media-search-input"
              placeholder={t('gallery.searchPlaceholder')}
              aria-label={t('gallery.searchPlaceholder')}
              value={searchInput()}
              onInput={(e) => onSearchInput(e.currentTarget.value)}
            />
          </div>
          <div class="nx-media-toolbar-actions">
            <Badge tone="neutral" mono>{tCount('gallery.count', total())}</Badge>
            <Show when={canDelete()}>
              <Button
                size="sm"
                variant={selectMode() ? 'toggled' : 'ghost'}
                onClick={() => {
                  setSelectMode(!selectMode())
                  setSelectedIds(new Set<string>())
                }}
                ariaLabel={t('gallery.selectMultiple')}
              >
                {t('gallery.selectMultiple')}
              </Button>
              <Show when={selectMode()}>
                <Badge tone={selectedCount() > 0 ? 'accent' : 'neutral'} mono>
                  {tCount('gallery.selected', selectedCount())}
                </Badge>
                <Button
                  size="sm"
                  variant="danger-soft"
                  disabled={selectedCount() === 0 || batchDeleting()}
                  onClick={deleteBatch}
                  ariaLabel={t('common.delete')}
                >
                  <IconTrash size={14} />
                  {t('common.delete')}
                </Button>
              </Show>
            </Show>
          </div>
        </div>

        <Show when={error()}>
          <div class="nx-banner nx-banner-danger" role="alert">{error()}</div>
        </Show>

        {/* Body */}
        <Show when={!loading()} fallback={
          <div class="nx-media-grid nx-media-grid-skeleton">
            <For each={[0, 1, 2, 3, 4, 5, 6, 7]}>{() => <Skeleton h="140px" />}</For>
          </div>
        }>
          <Show when={dayGroups().length > 0} fallback={
            <Panel>
              <Empty
                title={t('gallery.emptyTitle')}
                hint={t('gallery.emptyHint')}
                icon={<IconPhoto size={28} />}
              />
            </Panel>
          }>
            <For each={dayGroups()}>
              {(group) => (
                <section class="nx-media-day">
                  <div class="nx-media-day-head">
                    <span class="nx-media-day-label nx-mono">{dayGroupLabel(group.label)}</span>
                    <span class="nx-section-rule" />
                    <span class="nx-media-day-count nx-mono">{group.items.length}</span>
                  </div>
                  <div class="nx-media-grid">
                    <For each={group.items}>
                      {(item) => (
                        <div
                          class="nx-tile"
                          classList={{
                            'nx-tile-selected': selectMode() && selectedIds().has(item.id),
                            'nx-tile-selectable': selectMode(),
                          }}
                          role="button"
                          tabindex={0}
                          onClick={() => openTile(item)}
                          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') openTile(item) }}
                        >
                          <img class="nx-tile-thumb" src={item.thumbnailUrl} alt={item.fileName} loading="lazy" />
                          <Show when={item.type === 'VIDEO'}>
                            <span class="nx-tile-badge nx-mono">
                              <IconFilm size={10} />
                              {formatDuration(item.durationMs)}
                            </span>
                          </Show>
                          <Show when={selectMode() && selectedIds().has(item.id)}>
                            <span class="nx-tile-check">
                              <IconCheck size={14} />
                            </span>
                          </Show>
                          <div class="nx-tile-meta">
                            <span class="nx-tile-name" title={item.fileName}>{item.fileName}</span>
                            <span class="nx-tile-sub nx-mono">
                              <span>{formatTime(new Date(item.timestamp), { hour: '2-digit', minute: '2-digit' })}</span>
                              <Show when={item.fileSizeBytes > 0}>
                                <span>{formatFileSize(item.fileSizeBytes)}</span>
                              </Show>
                            </span>
                          </div>
                          <Show when={canDelete()}>
                            <button
                              type="button"
                              class="nx-tile-delete"
                              title={t('common.delete')}
                              aria-label={t('common.delete')}
                              onClick={(e) => { e.stopPropagation(); deleteOne(item) }}
                            >
                              <IconTrash size={13} />
                            </button>
                          </Show>
                        </div>
                      )}
                    </For>
                  </div>
                </section>
              )}
            </For>

            <Show when={hasMore()}>
              <div class="nx-media-more">
                <Button variant="secondary" loading={loadingMore()} onClick={() => fetchPage(page() + 1, true)}>
                  {t('gallery.loadMore', { count: Math.max(0, total() - items().length) })}
                </Button>
              </div>
            </Show>
            <Show when={loadingMore()}>
              <div class="nx-media-more"><Spinner small /></div>
            </Show>
          </Show>
        </Show>
      </div>

      <ConfirmDialog
        open={pendingDelete() !== null}
        title={t('common.delete')}
        message={t('gallery.deleteOneConfirm', { name: pendingDelete()?.fileName ?? '' })}
        danger
        busy={deleteOneBusy()}
        onConfirm={confirmDeleteOne}
        onClose={() => setPendingDelete(null)}
      />
      <ConfirmDialog
        open={confirmBatch()}
        title={t('common.delete')}
        message={tCount('gallery.deleteConfirm', selectedIds().size)}
        danger
        busy={batchDeleting()}
        onConfirm={confirmBatchDelete}
        onClose={() => setConfirmBatch(false)}
      />
    </div>
  )
}

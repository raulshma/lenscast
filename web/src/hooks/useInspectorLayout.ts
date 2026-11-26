import { createEffect, createSignal, onCleanup, type Accessor } from 'solid-js'
import * as api from '../api/client'
import type { WebUiLayoutDto } from '../types'
import type { NextState } from '../components/next/NextState'
import {
  defaultInspectorLayout,
  INSPECTOR_LAYOUT_STORAGE_KEY,
  inspectorLayoutsEqual,
  preserveInspectorLayoutIdentity,
  sanitizeInspectorLayout,
} from '../lib/inspectorLayout'

const SAVE_DEBOUNCE_MS = 350
const REFRESH_MS = 30_000

type LayoutTransform = (layout: WebUiLayoutDto) => WebUiLayoutDto

export interface InspectorLayoutController {
  layout: Accessor<WebUiLayoutDto>
  ready: Accessor<boolean>
  saving: Accessor<boolean>
  localOnly: Accessor<boolean>
  error: Accessor<string>
  mutate: (transform: LayoutTransform) => void
  replaceLayout: (raw: unknown) => void
  reset: () => Promise<void>
  refresh: () => Promise<void>
  flush: () => Promise<void>
}

function readCachedLayout(): WebUiLayoutDto {
  try {
    const raw = localStorage.getItem(INSPECTOR_LAYOUT_STORAGE_KEY)
    return raw ? sanitizeInspectorLayout(JSON.parse(raw)) : defaultInspectorLayout()
  } catch {
    return defaultInspectorLayout()
  }
}

function writeCachedLayout(layout: WebUiLayoutDto): void {
  try {
    localStorage.setItem(INSPECTOR_LAYOUT_STORAGE_KEY, JSON.stringify(layout))
  } catch {
    // Private browsing and quota failures must not block the live editor.
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function useInspectorLayout(state: NextState): InspectorLayoutController {
  const [layout, setLayout] = createSignal<WebUiLayoutDto>(readCachedLayout())
  const [ready, setReady] = createSignal(false)
  const [saving, setSaving] = createSignal(false)
  const [localOnly, setLocalOnly] = createSignal(false)
  const [error, setError] = createSignal('')

  let epoch = 0
  let revision = 0
  let dirty = false
  let inFlight = false
  let resetInFlight = false
  let queuedResetRevision: number | null = null
  let queued = false
  let refreshing = false
  let hasServerSnapshot = false
  let saveTimer: ReturnType<typeof setTimeout> | undefined
  let refreshTimer: ReturnType<typeof setInterval> | undefined

  function cancelSave(): void {
    if (saveTimer !== undefined) clearTimeout(saveTimer)
    saveTimer = undefined
  }

  function scheduleSave(): void {
    cancelSave()
    saveTimer = setTimeout(() => { void flush() }, SAVE_DEBOUNCE_MS)
  }

  function commit(next: WebUiLayoutDto): void {
    if (state.isViewer() || inspectorLayoutsEqual(next, layout())) return
    const sanitized = sanitizeInspectorLayout({ ...next, updatedAtMs: layout().updatedAtMs })
    const preserved = preserveInspectorLayoutIdentity(layout(), sanitized)
    if (preserved === layout()) return
    revision += 1
    dirty = true
    setLayout(preserved)
    writeCachedLayout(preserved)
    setError('')
    if (!localOnly()) scheduleSave()
  }

  async function flush(): Promise<void> {
    cancelSave()
    if (state.isViewer() || localOnly() || !ready() || !dirty) return
    if (inFlight || resetInFlight) {
      queued = true
      return
    }

    const flushEpoch = epoch
    const flushRevision = revision
    const snapshot = layout()
    inFlight = true
    setSaving(true)
    try {
      const saved = sanitizeInspectorLayout(await api.saveUiLayout(snapshot))
      if (flushEpoch !== epoch) return
      hasServerSnapshot = true
      if (flushRevision === revision) {
        dirty = false
        queued = false
        setLayout(preserveInspectorLayoutIdentity(layout(), saved))
        writeCachedLayout(saved)
      } else if (saved.updatedAtMs > layout().updatedAtMs) {
        const rebased = preserveInspectorLayoutIdentity(layout(), { ...layout(), updatedAtMs: saved.updatedAtMs })
        setLayout(rebased)
        writeCachedLayout(rebased)
      }
      setError('')
    } catch (cause) {
      if (flushEpoch !== epoch) return
      if ((cause as { status?: number })?.status === 404) {
        localOnlySet()
        dirty = false
        queued = false
      } else {
        setError(errorMessage(cause))
      }
    } finally {
      if (flushEpoch === epoch) {
        inFlight = false
        setSaving(false)
        if (queuedResetRevision !== null) {
          const resetRevision = queuedResetRevision
          queuedResetRevision = null
          queued = false
          void performReset(resetRevision)
        } else if (queued && dirty && !localOnly()) {
          queued = false
          scheduleSave()
        }
      }
    }
  }

  async function performReset(resetRevision: number): Promise<void> {
    const resetEpoch = epoch
    resetInFlight = true
    setSaving(true)
    try {
      const saved = sanitizeInspectorLayout(await api.resetUiLayout())
      if (resetEpoch !== epoch) return
      hasServerSnapshot = true
      if (resetRevision === revision) {
        dirty = false
        queued = false
        setLayout(preserveInspectorLayoutIdentity(layout(), saved))
        writeCachedLayout(saved)
      } else if (saved.updatedAtMs > layout().updatedAtMs) {
        const rebased = preserveInspectorLayoutIdentity(layout(), { ...layout(), updatedAtMs: saved.updatedAtMs })
        setLayout(rebased)
        writeCachedLayout(rebased)
      }
      setError('')
    } catch (cause) {
      if (resetEpoch !== epoch) return
      if ((cause as { status?: number })?.status === 404) {
        localOnlySet()
        dirty = false
        queued = false
      } else {
        setError(errorMessage(cause))
      }
    } finally {
      if (resetEpoch === epoch) {
        resetInFlight = false
        setSaving(false)
        if (queuedResetRevision !== null) {
          const nextResetRevision = queuedResetRevision
          queuedResetRevision = null
          queued = false
          void performReset(nextResetRevision)
        } else if (dirty && !localOnly()) {
          scheduleSave()
        }
      }
    }
  }

  async function reset(): Promise<void> {
    if (state.isViewer()) return
    const defaults = defaultInspectorLayout()
    revision += 1
    dirty = true
    const resetRevision = revision
    setLayout(preserveInspectorLayoutIdentity(layout(), defaults))
    writeCachedLayout(defaults)
    setError('')
    if (localOnly()) return
    cancelSave()
    if (inFlight || resetInFlight) {
      queuedResetRevision = resetRevision
      return
    }
    await performReset(resetRevision)
  }

  function localOnlySet(): void {
    setLocalOnly(true)
    setReady(true)
  }

  async function refresh(): Promise<void> {
    if (!ready() || refreshing || inFlight || resetInFlight || localOnly() || dirty) return
    const refreshEpoch = epoch
    const refreshRevision = revision
    const previous = layout()
    refreshing = true
    try {
      const raw = sanitizeInspectorLayout(await api.getUiLayout())
      if (refreshEpoch !== epoch || refreshRevision !== revision || dirty || inFlight) return
      if (hasServerSnapshot && raw.updatedAtMs <= previous.updatedAtMs) {
        const deviceReset = raw.updatedAtMs === 0 && inspectorLayoutsEqual(raw, defaultInspectorLayout())
        if (!deviceReset) return
      }
      if (inspectorLayoutsEqual(raw, previous)) return
      hasServerSnapshot = true
      setLayout(preserveInspectorLayoutIdentity(previous, raw))
      writeCachedLayout(raw)
      setError('')
    } catch (cause) {
      if (refreshEpoch !== epoch) return
      if ((cause as { status?: number })?.status === 404) {
        localOnlySet()
      } else if (!dirty) {
        setError(errorMessage(cause))
      }
    } finally {
      if (refreshEpoch === epoch) refreshing = false
    }
  }

  async function hydrate(): Promise<void> {
    const hydrateEpoch = epoch
    const hydrateRevision = revision
    const preserveDirty = dirty
    try {
      const raw = sanitizeInspectorLayout(await api.getUiLayout())
      if (hydrateEpoch !== epoch || hydrateRevision !== revision) return
      if (!preserveDirty && !dirty) {
        hasServerSnapshot = true
        setLayout(preserveInspectorLayoutIdentity(layout(), raw))
        writeCachedLayout(raw)
        setError('')
      }
    } catch (cause) {
      if (hydrateEpoch !== epoch) return
      if ((cause as { status?: number })?.status === 404) {
        setLocalOnly(true)
      } else {
        setError(errorMessage(cause))
      }
    } finally {
      if (hydrateEpoch === epoch) {
        setReady(true)
        if (dirty && !localOnly()) scheduleSave()
      }
    }
  }

  createEffect(() => {
    state.isViewer()
    epoch += 1
    cancelSave()
    inFlight = false
    resetInFlight = false
    queuedResetRevision = null
    queued = false
    refreshing = false
    setSaving(false)
    setReady(false)
    void hydrate()
  })

  createEffect(() => {
    if (!ready() || localOnly()) return
    refreshTimer = setInterval(() => {
      if (!document.hidden) void refresh()
    }, REFRESH_MS)
    const handleVisibility = () => {
      if (!document.hidden) void refresh()
    }
    document.addEventListener('visibilitychange', handleVisibility)
    onCleanup(() => {
      if (refreshTimer !== undefined) clearInterval(refreshTimer)
      refreshTimer = undefined
      document.removeEventListener('visibilitychange', handleVisibility)
    })
  })

  onCleanup(() => {
    epoch += 1
    cancelSave()
    if (refreshTimer !== undefined) clearInterval(refreshTimer)
  })

  return {
    layout,
    ready,
    saving,
    localOnly,
    error,
    mutate: (transform) => commit(transform(layout())),
    replaceLayout: (raw) => {
      const imported = sanitizeInspectorLayout(raw)
      commit({ ...imported, updatedAtMs: layout().updatedAtMs })
    },
    reset,
    refresh,
    flush,
  }
}

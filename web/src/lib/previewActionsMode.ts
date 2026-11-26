import { createSignal } from 'solid-js'

// Preview action-bar label mode — the uiMode pattern applied to the beta
// web UI's command ledge beneath the stream preview. 'compact' (default)
// collapses secondary actions to 32px icon buttons with tooltips so the
// whole deck fits on one row while keeping important actions (Capture, Web
// Stream, RTSP Stream) as full-text buttons; 'full' shows every button's
// text label. Persisted in localStorage so the operator's choice survives
// reloads.

export type PreviewActionsMode = 'compact' | 'full'

export const DEFAULT_PREVIEW_ACTIONS_MODE: PreviewActionsMode = 'compact'

const STORAGE_KEY = 'lenscast.previewActions'

/** Anything but 'full' is compact — an unknown value can never wedge the bar. */
export function parsePreviewActionsMode(raw: string | null): PreviewActionsMode {
  return raw === 'full' ? 'full' : DEFAULT_PREVIEW_ACTIONS_MODE
}

export function otherPreviewActionsMode(mode: PreviewActionsMode): PreviewActionsMode {
  return mode === 'compact' ? 'full' : 'compact'
}

export interface PreviewActionsModeDeps {
  get(): string | null
  set(value: string): void
}

export function createPreviewActionsMode(deps: PreviewActionsModeDeps) {
  const [mode, setMode] = createSignal<PreviewActionsMode>(parsePreviewActionsMode(deps.get()))
  const store = {
    /** The current mode, as a reactive signal. */
    mode,
    set(next: PreviewActionsMode) {
      try {
        deps.set(next)
      } catch {
        // Storage failures keep the toggle working for this session.
      }
      setMode(next)
    },
    toggle() {
      store.set(otherPreviewActionsMode(store.mode()))
    },
  }
  return store
}

function localStorageDeps(): PreviewActionsModeDeps {
  return {
    get: () => (typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null),
    set: (value) => {
      if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, value)
    },
  }
}

let singleton: ReturnType<typeof createPreviewActionsMode> | null = null

/** The app-wide preview-actions mode store; lazy so node tests can import safely. */
export function previewActionsModeStore() {
  if (!singleton) singleton = createPreviewActionsMode(localStorageDeps())
  return singleton
}

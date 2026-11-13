import { createSignal } from 'solid-js'

// UI mode — the opt-in switch for the redesigned dashboard. 'classic' is the
// shipped default; 'next' is the new console shell. The choice persists to
// localStorage so a refresh keeps the user where they were, and flipping the
// constant below is the whole migration when the next UI graduates from
// opt-in to default.

export type UiMode = 'classic' | 'next'

/** Flip to 'next' when the redesign graduates from opt-in to default. */
export const DEFAULT_UI_MODE: UiMode = 'classic'

const STORAGE_KEY = 'lenscast.ui'

/** Anything but 'next' is classic — an unknown value can never wedge the app. */
export function parseUiMode(raw: string | null): UiMode {
  return raw === 'next' ? 'next' : DEFAULT_UI_MODE
}

export function otherUiMode(mode: UiMode): UiMode {
  return mode === 'next' ? 'classic' : 'next'
}

export interface UiModeDeps {
  get(): string | null
  set(value: string): void
}

export function createUiMode(deps: UiModeDeps) {
  const [mode, setMode] = createSignal<UiMode>(parseUiMode(deps.get()))
  const store = {
    /** The current mode, as a reactive signal. */
    mode,
    set(next: UiMode) {
      try {
        deps.set(next)
      } catch {
        // Storage failures keep the switch working for this session.
      }
      setMode(next)
    },
    toggle() {
      store.set(otherUiMode(store.mode()))
    },
  }
  return store
}

function localStorageDeps(): UiModeDeps {
  return {
    get: () => (typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null),
    set: (value) => {
      if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, value)
    },
  }
}

let singleton: ReturnType<typeof createUiMode> | null = null

/** The app-wide UI-mode store; created lazily so importing in node tests is safe. */
export function uiModeStore() {
  if (!singleton) singleton = createUiMode(localStorageDeps())
  return singleton
}

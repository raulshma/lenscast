import { createEffect, createSignal } from 'solid-js'

export type ThemeMode = 'dark' | 'light'

const STORAGE_KEY = 'lenscast.theme'

function initialTheme(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // Private-mode storage failures fall through to the OS preference.
  }
  if (typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: light)').matches) {
    return 'light'
  }
  return 'dark'
}

/**
 * Best-effort light/dark toggle. Dark is the absence of the attribute — the
 * dashboard's default look is driven purely by the existing :root tokens,
 * so a user who never toggles (and isn't on a light-OS) sees the exact same
 * CSS as before. Light mode only ever adds data-theme="light" on <html>,
 * which App.css overrides with a dedicated token block. The choice persists
 * to localStorage and falls back to prefers-color-scheme on first visit.
 *
 * `enabled` lets the dispatcher scope the classic attribute semantics to the
 * classic shell: while the next shell is mounted it writes its own disjoint
 * data-theme value (useNextTheme), and the guard keeps this effect from
 * fighting it. The guard is read reactively, so switching shells re-applies
 * the classic attribute.
 */
export function useTheme(enabled?: () => boolean) {
  const [theme, setTheme] = createSignal<ThemeMode>(initialTheme())

  createEffect(() => {
    if (enabled && !enabled()) return
    const root = document.documentElement
    if (theme() === 'light') root.setAttribute('data-theme', 'light')
    else root.removeAttribute('data-theme')
  })

  function toggleTheme() {
    setTheme((current) => {
      const next: ThemeMode = current === 'light' ? 'dark' : 'light'
      try {
        localStorage.setItem(STORAGE_KEY, next)
      } catch {
        // Storage failures keep the toggle working for this session.
      }
      return next
    })
  }

  return { theme, toggleTheme }
}

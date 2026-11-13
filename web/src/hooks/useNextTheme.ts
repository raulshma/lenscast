import { createSignal } from 'solid-js'

export type NextThemeMode = 'light' | 'dark'

const STORAGE_KEY = 'lenscast.nextTheme'

function initialTheme(): NextThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // Private-mode storage failures fall through to the console default.
  }
  return 'dark'
}

/**
 * Theme for the redesigned (next) UI. The console look is dark-first: dark
 * is the default (no data-theme attribute on <html>); light adds
 * data-theme="light" — the same DOM semantics as the classic useTheme,
 * just a separate storage key (lenscast.nextTheme), so the two shells
 * never clobber each other's preference. The dispatcher owns the DOM
 * attribute — it calls this hook and applies the value gated on the
 * active UI mode, so even the login screen (which the dispatcher
 * renders) sees the right theme.
 */
export function useNextTheme() {
  const [theme, setTheme] = createSignal<NextThemeMode>(initialTheme())

  function toggleTheme() {
    setTheme((current) => {
      const next: NextThemeMode = current === 'light' ? 'dark' : 'light'
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

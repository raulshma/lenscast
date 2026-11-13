import { ErrorBoundary, Show, createEffect } from 'solid-js'
import { useAppState } from './hooks/useAppState'
import { useTheme } from './hooks/useTheme'
import { useNextTheme } from './hooks/useNextTheme'
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts'
import LoginScreen from './components/LoginScreen'
import LegacyShell from './components/legacy/LegacyShell'
import NextShell from './components/next/NextShell'
import NxLogin from './components/next/NxLogin'
import { cyclePlayerMode } from './video/playerLadder'
import { uiModeStore } from './lib/uiMode'
import { hashRouter } from './lib/router'
import { t } from './lib/i18n'
import './App.css'

/** Programmatic download of a GET route, the Gallery batch-download pattern. */
function triggerDownload(url: string) {
  const a = document.createElement('a')
  a.href = url
  a.download = ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/**
 * App is the shell dispatcher: it owns the single useAppState instance, the
 * global keyboard shortcuts, the auth gate, and the persisted classic/next
 * UI-mode switch (the opt-in redesign). Everything below the auth gate is
 * one of the two shells — LegacyShell (the shipped default, unchanged) or
 * NextShell (the opt-in redesign). Only one mounts at a time; the shared
 * state instance means the switch is instant and nothing re-polls.
 */
function App() {
  const state = useAppState()
  const ui = uiModeStore()
  // Each shell's theme semantics apply only while that UI mode is active:
  // both shells use dark = attribute absent, light adds data-theme="light"
  // (they differ only in storage keys: lenscast.theme vs
  // lenscast.nextTheme). Both guards are read reactively, so switching
  // modes re-applies the right attribute — including on the login screen,
  // which the dispatcher renders itself.
  const { theme, toggleTheme } = useTheme(() => ui.mode() === 'classic')
  const { theme: nextTheme, toggleTheme: toggleNextTheme } = useNextTheme()

  // Mirror the mode + next theme onto <html> for CSS scoping and so a shell
  // switch after mount stays consistent with the pre-paint boot snippet in
  // index.html. The browser chrome follows the active surface (next's
  // charcoal canvas).
  createEffect(() => {
    const root = document.documentElement
    root.setAttribute('data-ui', ui.mode())
    if (ui.mode() === 'next') {
      if (nextTheme() === 'light') root.setAttribute('data-theme', 'light')
      else root.removeAttribute('data-theme')
      const meta = document.querySelector('meta[name="theme-color"]')
      if (meta) meta.setAttribute('content', nextTheme() === 'dark' ? '#0a0c10' : '#eff1f4')
    }
  })

  // One global keydown listener; every guard (editable focus, modifier keys,
  // the key map) is the pure lib/shortcuts module. Write shortcuts skip
  // viewer sessions — the same POSTs the buttons fire would 403 — and the
  // gating mirrors the buttons exactly (stream enabled flags, action busy,
  // active stream for capture).
  useKeyboardShortcuts({
    capture: () => {
      if (state.isViewer()) return
      if (state.status()?.streaming?.isActive) state.handleCapture()
    },
    snapshot: () => {
      triggerDownload('/snapshot?highres=1&save=1')
    },
    'toggle-web': () => {
      if (state.isViewer() || state.streamActionLoading()) return
      const s = state.status()?.streaming
      if (!s) return
      if (s.webStreamingActive) state.handleStopWebStream()
      else if (s.webStreamingEnabled) state.handleStartWebStream()
    },
    'toggle-rtsp': () => {
      if (state.isViewer() || state.streamActionLoading()) return
      const s = state.status()?.streaming
      if (!s) return
      if (s.rtspStreamingActive) state.handleStopRtspStream()
      else if (s.rtspEnabled) state.handleStartRtspStream()
    },
    'cycle-player': () => {
      state.setPlayerMode(cyclePlayerMode(state.playerMode()))
    },
    gallery: () => {
      // In the next UI the gallery is the Media view, not an overlay.
      if (ui.mode() === 'next') {
        const current = hashRouter().route()
        hashRouter().navigate(current.name === 'media' ? { name: 'live' } : { name: 'media' })
        return
      }
      state.setShowGallery(!state.showGallery())
    },
    search: () => {
      const focus = () => {
        (document.getElementById('gallery-search-input') as HTMLInputElement | null)?.focus()
      }
      if (ui.mode() === 'next') {
        if (hashRouter().route().name === 'media') focus()
        else {
          hashRouter().navigate({ name: 'media' })
          // Solid renders the view synchronously with the hash flip; one
          // macrotask later the input exists.
          setTimeout(focus, 50)
        }
        return
      }
      if (state.showGallery()) focus()
      else {
        state.setShowGallery(true)
        // Solid renders the gallery synchronously with the signal flip; one
        // macrotask later the input exists.
        setTimeout(focus, 50)
      }
    },
    help: () => {
      state.setShowShortcuts(!state.showShortcuts())
    },
  }, {
    enabled: () => state.authChecked() && (!state.authRequired() || state.authenticated()),
  })

  return (
    <div class="app">
      <Show when={!state.authChecked()} fallback={
        <Show when={state.authRequired() && !state.authenticated()} fallback={
          <ErrorBoundary fallback={(err) => (
            <div class="app-loading" style={{ padding: '24px' }}>
              <div class="error-banner" style={{ 'max-width': '720px', width: '100%' }}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span>{t('app.renderError', { error: String(err) })}</span>
              </div>
            </div>
          )}>
            <Show when={ui.mode() === 'classic'} fallback={<NextShell state={state} theme={nextTheme} toggleTheme={toggleNextTheme} />}>
              <LegacyShell state={state} theme={theme} toggleTheme={toggleTheme} />
            </Show>
          </ErrorBoundary>
        }>
          <Show when={ui.mode() === 'classic'} fallback={
            <NxLogin
              loginUser={state.loginUser}
              setLoginUser={state.setLoginUser}
              loginPass={state.loginPass}
              setLoginPass={state.setLoginPass}
              loginError={state.loginError}
              loginLoading={state.loginLoading}
              handleLogin={state.handleLogin}
            />
          }>
            <LoginScreen
              loginUser={state.loginUser}
              setLoginUser={state.setLoginUser}
              loginPass={state.loginPass}
              setLoginPass={state.setLoginPass}
              loginError={state.loginError}
              loginLoading={state.loginLoading}
              handleLogin={state.handleLogin}
            />
          </Show>
        </Show>
      }>
        <div class="app-loading">
          <div class="login-lens-ring">
            <div class="login-lens-inner">
              <img src="/logo.svg" alt="LensCast" width="36" height="36" />
            </div>
          </div>
        </div>
      </Show>
    </div>
  )
}

export default App

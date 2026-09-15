import { For, Show } from 'solid-js'
import type { DeviceStatus } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import type { ThemeMode } from '../hooks/useTheme'
import { LOCALES, LOCALE_DISPLAY_NAMES, locale, setLocale, t, type Locale } from '../lib/i18n'

interface Props {
  status: () => DeviceStatus | null
  saving: () => boolean
  authRequired: () => boolean
  handleLogout: () => void
  setShowGallery: (v: boolean) => void
  onShowShortcuts: () => void
  theme: () => ThemeMode
  toggleTheme: () => void
}

export default function Navbar(props: Props) {
  const st = () => props.status()

  return (
    <nav class="app-navbar" id="main-navbar">
      <div class="navbar-left">
        <div class="navbar-brand">
          <img src="/logo.svg" alt="LensCast" width="24" height="24" style={{ "margin-right": "8px" }} />
          <div class="brand-dot" classList={{ 'brand-dot-active': !!st()?.streaming?.isActive }} />
          <span class="brand-name">LensCast</span>
        </div>
        <Show when={st()?.camera}>
          <span class="navbar-camera-badge">{st()!.camera}</span>
        </Show>
      </div>

      <div class="navbar-right">
        {/* Battery */}
        <div
          class="status-pill"
          classList={{
            'status-pill-success': !st()?.battery?.isCharging,
            'status-pill-warning': !!st()?.battery?.isCharging,
          }}
        >
          <svg class="pill-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="7" width="18" height="10" rx="2" />
            <path d="M22 11v2" />
            <Show when={st()?.battery?.isCharging}>
              <path d="M10 7l-2 5h4l-2 5" stroke-width="1.5" />
            </Show>
          </svg>
          <span>{st()?.battery?.level ?? '--'}%</span>
        </div>

        {/* Thermal */}
        <div
          class="status-pill"
          classList={{
            'status-pill-success': !st()?.thermal || st()!.thermal === 'NORMAL',
            'status-pill-warning': st()?.thermal === 'MODERATE' || st()?.thermal === 'LIGHT',
            'status-pill-danger': st()?.thermal === 'SEVERE' || st()?.thermal === 'CRITICAL',
          }}
        >
          <svg class="pill-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 14.76V3.5a2.5 2.5 0 00-5 0v11.26a4.5 4.5 0 105 0z" />
          </svg>
          <span>{st()?.thermal || '--'}</span>
        </div>

        {/* Clients */}
        <div class="status-pill status-pill-info">
          <svg class="pill-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
            <circle cx="9" cy="7" r="4" />
            <path d="M23 21v-2a4 4 0 00-3-3.87" />
            <path d="M16 3.13a4 4 0 010 7.75" />
          </svg>
          <span>{st()?.streaming?.clientCount ?? API_DEFAULTS.clientCount}</span>
        </div>

        {/* Theme */}
        <button
          id="theme-toggle-btn"
          class="navbar-icon-btn"
          onClick={props.toggleTheme}
          title={props.theme() === 'light' ? t('nav.theme.dark') : t('nav.theme.light')}
        >
          <Show
            when={props.theme() === 'light'}
            fallback={
              /* Sun: click for light mode */
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="4" />
                <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
              </svg>
            }
          >
            {/* Moon: click for dark mode */}
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
            </svg>
          </Show>
        </button>

        {/* Gallery */}
        <button id="gallery-btn" class="navbar-icon-btn" onClick={() => props.setShowGallery(true)} title={t('nav.gallery')}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="3" y="3" width="7" height="7" rx="1.5" />
            <rect x="14" y="3" width="7" height="7" rx="1.5" />
            <rect x="3" y="14" width="7" height="7" rx="1.5" />
            <rect x="14" y="14" width="7" height="7" rx="1.5" />
          </svg>
        </button>

        {/* Keyboard shortcuts help ('?') */}
        <button
          id="shortcuts-btn"
          class="navbar-icon-btn"
          onClick={props.onShowShortcuts}
          title={t('nav.shortcuts')}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="2" y="6" width="20" height="12" rx="2" />
            <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M6 14h.01M18 14h.01M9 14h6" stroke-width="2" stroke-linecap="round" />
          </svg>
        </button>

        {/* Language */}
        <select
          class="navbar-icon-btn navbar-locale-select"
          aria-label={t('nav.language')}
          title={t('nav.language')}
          value={locale()}
          onChange={(e) => setLocale(e.currentTarget.value as Locale)}
        >
          <For each={LOCALES}>
            {(code) => <option value={code}>{LOCALE_DISPLAY_NAMES[code]}</option>}
          </For>
        </select>

        {/* Save indicator — only meaningful while a save is in flight; an
            always-on checkmark is just navbar noise. */}
        <Show when={props.saving()}>
          <div class="save-indicator save-indicator-active">
            <span class="save-spinner" />
          </div>
        </Show>

        {/* Logout */}
        <Show when={props.authRequired()}>
          <button id="logout-btn" class="navbar-icon-btn navbar-icon-btn-danger" onClick={props.handleLogout} title={t('nav.logout')}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        </Show>
      </div>
    </nav>
  )
}

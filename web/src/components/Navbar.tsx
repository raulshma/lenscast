import { Show } from 'solid-js'
import type { DeviceStatus } from '../types'

interface Props {
  status: () => DeviceStatus | null
  saving: () => boolean
  authRequired: () => boolean
  handleLogout: () => void
  setShowGallery: (v: boolean) => void
}

export default function Navbar(props: Props) {
  const st = () => props.status()

  return (
    <nav class="app-navbar" id="main-navbar">
      <div class="navbar-left">
        <div class="navbar-brand">
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
          <span>{st()?.streaming?.clientCount ?? 0}</span>
        </div>

        {/* Gallery */}
        <button id="gallery-btn" class="navbar-icon-btn" onClick={() => props.setShowGallery(true)} title="Gallery">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="3" y="3" width="7" height="7" rx="1.5" />
            <rect x="14" y="3" width="7" height="7" rx="1.5" />
            <rect x="3" y="14" width="7" height="7" rx="1.5" />
            <rect x="14" y="14" width="7" height="7" rx="1.5" />
          </svg>
        </button>

        {/* Save indicator */}
        <div class="save-indicator" classList={{ 'save-indicator-active': props.saving() }}>
          <Show when={props.saving()} fallback={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 13l4 4L19 7" />
            </svg>
          }>
            <span class="save-spinner" />
          </Show>
        </div>

        {/* Logout */}
        <Show when={props.authRequired()}>
          <button id="logout-btn" class="navbar-icon-btn navbar-icon-btn-danger" onClick={props.handleLogout} title="Logout">
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

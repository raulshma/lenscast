import { createSignal, Show } from 'solid-js'
import { exportSettings, importSettings } from '../api/client'
import SettingsCard from './SettingsCard'

function exportFileName(): string {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  return `lenscast-settings-${stamp}.json`
}

/**
 * Settings export/import: download the device's configuration (secrets
 * already blanked server-side) as a versioned JSON envelope, and restore one
 * on this or another device. Import rides the exact save path a dashboard
 * edit uses, so every validation and clamp holds; applied values appear in
 * the dashboard on the next settings poll.
 */
export default function ConfigBackupCard() {
  const [busy, setBusy] = createSignal(false)
  const [message, setMessage] = createSignal('')
  const [error, setError] = createSignal('')

  async function download() {
    if (busy()) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const exported = await exportSettings()
      const blob = new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = exportFileName()
      anchor.click()
      URL.revokeObjectURL(url)
      setMessage('Settings exported. Passwords and tokens are excluded; custom webhook headers are included.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Export failed')
    } finally {
      setBusy(false)
    }
  }

  async function upload(file: File) {
    if (busy()) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const parsed: unknown = JSON.parse(await file.text())
      await importSettings(parsed)
      setMessage('Settings imported. Values appear as the dashboard refreshes.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Import failed — is this a LensCast settings export?')
    } finally {
      setBusy(false)
      // Allow re-selecting the same file after a failed attempt.
      const input = document.getElementById('settings-import-input') as HTMLInputElement | null
      if (input) input.value = ''
    }
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
          <path d="M7 10l5 5 5-5" />
          <path d="M12 15V3" />
        </svg>
      }
      title="Config Backup"
    >
      <div class="field-group">
        <div class="deterrence-row">
          <button type="button" class="action-btn action-btn-ghost" disabled={busy()} onClick={download}>
            Export Settings
          </button>
          <label class="action-btn action-btn-ghost" for="settings-import-input">
            {busy() ? 'Working…' : 'Import Settings'}
          </label>
          <input
            id="settings-import-input"
            type="file"
            accept="application/json,.json"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.currentTarget.files?.[0]
              if (file) void upload(file)
            }}
          />
        </div>
        <Show when={message()}>
          <span class="clients-cap-row" role="status" aria-live="polite">
            {message()}
          </span>
        </Show>
        <Show when={error()}>
          <span class="clients-cap-row" role="alert">
            {error()}
          </span>
        </Show>
      </div>
    </SettingsCard>
  )
}

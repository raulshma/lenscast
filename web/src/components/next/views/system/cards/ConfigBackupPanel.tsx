import { createSignal, Show } from 'solid-js'
import { exportSettings, importSettings } from '../../../../../api/client'
import { t } from '../../../../../lib/i18n'
import { Panel, Button } from '../../../ui'

function exportFileName(): string {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  return `lenscast-settings-${stamp}.json`
}

/**
 * Settings export/import, ported from the classic ConfigBackupCard onto the
 * kit Panel: download the device's configuration (secrets already blanked
 * server-side) as a versioned JSON envelope, and restore one on this or
 * another device. Import rides the exact save path a dashboard edit uses,
 * so every validation and clamp holds; applied values appear in the
 * dashboard on the next settings poll. The import trigger stays a raw
 * `<label for>` (a kit Button can't own a file input), dressed in the kit
 * button classes; the hidden input keeps its id — upload() clears it so a
 * failed import can be retried with the same file.
 */
export default function ConfigBackupPanel() {
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
      setMessage(t('config.exported'))
    } catch (e) {
      setError(e instanceof Error ? e.message : t('config.exportFailed'))
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
      setMessage(t('config.imported'))
    } catch (e) {
      setError(e instanceof Error ? e.message : t('config.importFailed'))
    } finally {
      setBusy(false)
      // Allow re-selecting the same file after a failed attempt.
      const input = document.getElementById('settings-import-input') as HTMLInputElement | null
      if (input) input.value = ''
    }
  }

  return (
    <Panel title={t('config.title')} class="nx-panel-danger">
      <div class="nx-deterrence">
        <Button size="sm" variant="ghost" disabled={busy()} onClick={download}>
          {t('config.export')}
        </Button>
        <label class="nx-btn nx-btn-danger-soft nx-btn-sm" for="settings-import-input">
          {busy() ? t('config.working') : t('config.import')}
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
        <span class="nx-field-hint" role="status" aria-live="polite">
          {message()}
        </span>
      </Show>
      <Show when={error()}>
        <span class="nx-field-hint" role="alert">
          {error()}
        </span>
      </Show>
    </Panel>
  )
}

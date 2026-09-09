import { Show, createSignal } from 'solid-js'
import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * Capture backup destinations: WebDAV (Nextcloud/self-host friendly) or
 * Telegram (Bot API sendDocument). The target picker routes the worker; the
 * unselected target's fields stay visible but disabled, never deleted. Both
 * secret fields are write-only — an empty value keeps the stored secret, so
 * neither ever round-trips the wire. Each is bound to a local draft, not the
 * server value: responses always carry a blank secret, and binding to them
 * would wipe the input mid-typing on the next settings refresh.
 */
export default function BackupCard(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const backupOn = () => stream()?.backupEnabled ?? API_DEFAULTS.backupEnabled
  const telegramSelected = () =>
    (stream()?.backupTarget ?? API_DEFAULTS.backupTarget) === 'telegram'
  const [passwordDraft, setPasswordDraft] = createSignal('')
  const [telegramTokenDraft, setTelegramTokenDraft] = createSignal('')

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
          <polyline points="17 8 12 3 7 8" />
          <line x1="12" y1="3" x2="12" y2="15" />
        </svg>
      }
      title="Backup"
    >
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Auto-upload New Captures</span>
          <label class="toggle-switch" for="backup-toggle">
            <input
              id="backup-toggle"
              type="checkbox"
              checked={backupOn()}
              onChange={() => props.updateStreamingAndSave({ backupEnabled: !backupOn() })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      <Show when={backupOn()}>
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Target</span>
          </div>
          <div class="deterrence-row">
            <button
              type="button"
              class={`action-btn ${!telegramSelected() ? 'action-btn-primary' : 'action-btn-ghost'}`}
              onClick={() => props.updateStreamingAndSave({ backupTarget: 'webdav' })}
            >
              <span>WebDAV</span>
            </button>
            <button
              type="button"
              class={`action-btn ${telegramSelected() ? 'action-btn-primary' : 'action-btn-ghost'}`}
              onClick={() => props.updateStreamingAndSave({ backupTarget: 'telegram' })}
            >
              <span>Telegram</span>
            </button>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">WebDAV Collection URL</span>
          </div>
          <input
            id="backup-webdav-url"
            type="url"
            class="field-input field-input-full"
            placeholder="https://cloud.example.com/remote.php/dav/files/user/LensCast"
            disabled={telegramSelected()}
            value={stream()?.backupWebdavUrl ?? ''}
            onInput={(e) => props.updateStreamingDebounced({ backupWebdavUrl: e.currentTarget.value })}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Username</span>
          </div>
          <input
            id="backup-webdav-user"
            type="text"
            class="field-input field-input-full"
            autocomplete="off"
            disabled={telegramSelected()}
            value={stream()?.backupWebdavUsername ?? ''}
            onInput={(e) => props.updateStreamingDebounced({ backupWebdavUsername: e.currentTarget.value })}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Password</span>
          </div>
          <input
            id="backup-webdav-pass"
            type="password"
            class="field-input field-input-full"
            autocomplete="new-password"
            placeholder="(unchanged)"
            disabled={telegramSelected()}
            value={passwordDraft()}
            onInput={(e) => {
              setPasswordDraft(e.currentTarget.value)
              props.updateStreamingDebounced({ backupWebdavPassword: e.currentTarget.value })
            }}
          />
        </div>

        <Show when={telegramSelected()}>
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">Telegram Bot Token</span>
            </div>
            <input
              id="backup-telegram-token"
              type="password"
              class="field-input field-input-full"
              autocomplete="new-password"
              placeholder="(unchanged) — from @BotFather"
              value={telegramTokenDraft()}
              onInput={(e) => {
                setTelegramTokenDraft(e.currentTarget.value)
                props.updateStreamingDebounced({ telegramBotToken: e.currentTarget.value })
              }}
            />
          </div>

          <div class="field-group">
            <div class="field-row">
              <span class="field-label">Telegram Chat ID</span>
            </div>
            <input
              id="backup-telegram-chat"
              type="text"
              class="field-input field-input-full"
              autocomplete="off"
              placeholder="e.g. 123456789 (from @userinfobot)"
              value={stream()?.telegramChatId ?? ''}
              onInput={(e) => props.updateStreamingDebounced({ telegramChatId: e.currentTarget.value })}
            />
          </div>
        </Show>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Upload on Wi-Fi only</span>
            <label class="toggle-switch" for="backup-wifi-toggle">
              <input
                id="backup-wifi-toggle"
                type="checkbox"
                checked={stream()?.backupWifiOnly ?? API_DEFAULTS.backupWifiOnly}
                onChange={() => props.updateStreamingAndSave({ backupWifiOnly: !(stream()?.backupWifiOnly ?? API_DEFAULTS.backupWifiOnly) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </div>
      </Show>
    </SettingsCard>
  )
}

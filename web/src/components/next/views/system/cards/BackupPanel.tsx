import { Show, createSignal } from 'solid-js'
import type { AllSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, Field, ToggleField, TextInput, Button } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * Capture backup destinations, ported from the classic BackupCard onto the
 * kit: WebDAV (Nextcloud/self-host friendly) or Telegram (Bot API
 * sendDocument). The target picker routes the worker; the unselected
 * target's fields stay visible but disabled, never deleted. Both secret
 * fields are write-only — an empty value keeps the stored secret, so
 * neither ever round-trips the wire. Each is bound to a local draft, not
 * the server value: responses always carry a blank secret, and binding to
 * them would wipe the input mid-typing on the next settings refresh.
 */
export default function BackupPanel(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const backupOn = () => stream()?.backupEnabled ?? API_DEFAULTS.backupEnabled
  const telegramSelected = () =>
    (stream()?.backupTarget ?? API_DEFAULTS.backupTarget) === 'telegram'
  const [passwordDraft, setPasswordDraft] = createSignal('')
  const [telegramTokenDraft, setTelegramTokenDraft] = createSignal('')

  return (
    <Panel title={t('backup.title')}>
      <ToggleField
        label={t('backup.autoUpload')}
        checked={backupOn()}
        onChange={() => props.updateStreamingAndSave({ backupEnabled: !backupOn() })}
      />

      <Show when={backupOn()}>
        <div class="nx-deterrence">
          <Button
            size="sm"
            variant={!telegramSelected() ? 'primary' : 'ghost'}
            onClick={() => props.updateStreamingAndSave({ backupTarget: 'webdav' })}
            ariaLabel="WebDAV"
          >
            WebDAV
          </Button>
          <Button
            size="sm"
            variant={telegramSelected() ? 'primary' : 'ghost'}
            onClick={() => props.updateStreamingAndSave({ backupTarget: 'telegram' })}
            ariaLabel="Telegram"
          >
            Telegram
          </Button>
        </div>

        <Field label={t('backup.webdavUrl')}>
          <TextInput
            mono
            type="url"
            placeholder="https://cloud.example.com/remote.php/dav/files/user/LensCast"
            disabled={telegramSelected()}
            value={stream()?.backupWebdavUrl ?? ''}
            onInput={(v) => props.updateStreamingDebounced({ backupWebdavUrl: v })}
            ariaLabel={t('backup.webdavUrl')}
          />
        </Field>

        <Field label={t('common.username')}>
          <TextInput
            type="text"
            disabled={telegramSelected()}
            value={stream()?.backupWebdavUsername ?? ''}
            onInput={(v) => props.updateStreamingDebounced({ backupWebdavUsername: v })}
            ariaLabel={t('common.username')}
          />
        </Field>

        <Field label={t('common.password')}>
          <TextInput
            type="password"
            placeholder={t('common.unchanged')}
            disabled={telegramSelected()}
            value={passwordDraft()}
            onInput={(v) => {
              setPasswordDraft(v)
              props.updateStreamingDebounced({ backupWebdavPassword: v })
            }}
            ariaLabel={t('common.password')}
          />
        </Field>

        <Show when={telegramSelected()}>
          <Field label={t('backup.telegramToken')}>
            <TextInput
              type="password"
              placeholder={t('backup.telegramTokenHint')}
              value={telegramTokenDraft()}
              onInput={(v) => {
                setTelegramTokenDraft(v)
                props.updateStreamingDebounced({ telegramBotToken: v })
              }}
              ariaLabel={t('backup.telegramToken')}
            />
          </Field>

          <Field label={t('backup.telegramChat')}>
            <TextInput
              mono
              type="text"
              placeholder={t('backup.telegramChatHint')}
              value={stream()?.telegramChatId ?? ''}
              onInput={(v) => props.updateStreamingDebounced({ telegramChatId: v })}
              ariaLabel={t('backup.telegramChat')}
            />
          </Field>
        </Show>

        <ToggleField
          label={t('backup.wifiOnly')}
          checked={stream()?.backupWifiOnly ?? API_DEFAULTS.backupWifiOnly}
          onChange={() => props.updateStreamingAndSave({ backupWifiOnly: !(stream()?.backupWifiOnly ?? API_DEFAULTS.backupWifiOnly) })}
        />
      </Show>
    </Panel>
  )
}

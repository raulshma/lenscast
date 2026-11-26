import { Show, createSignal } from 'solid-js'
import type { AllSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, Field, ToggleField, TextInput, Button } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

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
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const save = (patch: Partial<AllSettings['streaming']>) => {
    if (!readOnly()) props.updateStreamingAndSave(patch)
  }
  const saveDebounced = (patch: Partial<AllSettings['streaming']>) => {
    if (!readOnly()) props.updateStreamingDebounced(patch)
  }
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const backupOn = () => stream()?.backupEnabled ?? API_DEFAULTS.backupEnabled
  const telegramSelected = () =>
    (stream()?.backupTarget ?? API_DEFAULTS.backupTarget) === 'telegram'
  const [passwordDraft, setPasswordDraft] = createSignal('')
  const [telegramTokenDraft, setTelegramTokenDraft] = createSignal('')

  return (
    <SettingItem id="backup.config">
      <Panel title={t('backup.title')}>
      <ToggleField
        disabled={readOnly()}
        label={t('backup.autoUpload')}
        checked={backupOn()}
        onChange={() => save({ backupEnabled: !backupOn() })}
      />

      <Show when={backupOn()}>
        {/* The target picker is a selection, not an action: the chosen
            target wears the amber toggled plate, the other a quiet outline. */}
        <div class="nx-deterrence">
          <Button
            size="sm"
            variant={!telegramSelected() ? 'toggled' : 'secondary'}
            ariaPressed={!telegramSelected()}
            disabled={readOnly()}
            onClick={() => save({ backupTarget: 'webdav' })}
            ariaLabel="WebDAV"
          >
            WebDAV
          </Button>
          <Button
            size="sm"
            variant={telegramSelected() ? 'toggled' : 'secondary'}
            ariaPressed={telegramSelected()}
            disabled={readOnly()}
            onClick={() => save({ backupTarget: 'telegram' })}
            ariaLabel="Telegram"
          >
            Telegram
          </Button>
        </div>

        <Field label={t('backup.webdavUrl')}>
          <TextInput
            disabled={telegramSelected() || readOnly()}
            mono
            value={stream()?.backupWebdavUrl ?? ''}
            onInput={(v) => saveDebounced({ backupWebdavUrl: v })}
            ariaLabel={t('backup.webdavUrl')}
          />
        </Field>

        <Field label={t('common.username')}>
          <TextInput
            disabled={telegramSelected() || readOnly()}
            type="text"
            value={stream()?.backupWebdavUsername ?? ''}
            onInput={(v) => saveDebounced({ backupWebdavUsername: v })}
            ariaLabel={t('common.username')}
          />
        </Field>

        <Field label={t('common.password')}>
          <TextInput
            disabled={telegramSelected() || readOnly()}
            type="password"
            placeholder={t('common.unchanged')}
            value={passwordDraft()}
            onInput={(v) => {
              setPasswordDraft(v)
              saveDebounced({ backupWebdavPassword: v })
            }}
            ariaLabel={t('common.password')}
          />
        </Field>

        <Show when={telegramSelected()}>
          <Field label={t('backup.telegramToken')}>
            <TextInput
              disabled={readOnly()}
              type="password"
              placeholder={t('backup.telegramTokenHint')}
              value={telegramTokenDraft()}
              onInput={(v) => {
                setTelegramTokenDraft(v)
                saveDebounced({ telegramBotToken: v })
              }}
              ariaLabel={t('backup.telegramToken')}
            />
          </Field>

          <Field label={t('backup.telegramChat')}>
            <TextInput
              disabled={readOnly()}
              mono
              type="text"
              placeholder={t('backup.telegramChatHint')}
              value={stream()?.telegramChatId ?? ''}
              onInput={(v) => saveDebounced({ telegramChatId: v })}
              ariaLabel={t('backup.telegramChat')}
            />
          </Field>
        </Show>

        <ToggleField
          disabled={readOnly()}
          label={t('backup.wifiOnly')}
          checked={stream()?.backupWifiOnly ?? API_DEFAULTS.backupWifiOnly}
          onChange={() => save({ backupWifiOnly: !(stream()?.backupWifiOnly ?? API_DEFAULTS.backupWifiOnly) })}
        />
      </Show>
      </Panel>
    </SettingItem>
  )
}

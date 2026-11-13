import { createSignal, For, onMount, Show } from 'solid-js'
import type { AllSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import {
  listUnifiedPushTargets,
  removeUnifiedPushTarget,
  type UnifiedPushTargetInfo,
} from '../../../../../api/client'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, ToggleField, Button, Empty } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * UnifiedPush alerting, ported from the classic UnifiedPushCard onto the
 * kit: paired receiver apps (Android distributors or any UnifiedPush
 * client) register an RFC 8030 endpoint with this camera through
 * /api/push/unifiedpush, and the camera publishes the same RFC 8291-encrypted
 * detection payload it sends to browsers. This panel owns the independent
 * device gate and the target readout; pairing a receiver itself is done by
 * the receiver with the camera's VAPID key (or the pairing JSON from the
 * Auth card).
 */
export default function UnifiedPushPanel(props: Props) {
  const s = () => props.settings()
  const enabled = () => s()?.streaming.unifiedPushEnabled ?? API_DEFAULTS.unifiedPushEnabled

  const [targets, setTargets] = createSignal<UnifiedPushTargetInfo[]>([])
  // The server owns the cap (PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS);
  // null until the first successful read, so this panel never duplicates it.
  const [maxTargets, setMaxTargets] = createSignal<number | null>(null)
  const [vapid, setVapid] = createSignal('')
  const [loading, setLoading] = createSignal(false)
  const [message, setMessage] = createSignal('')

  async function refresh() {
    setLoading(true)
    try {
      const response = await listUnifiedPushTargets()
      setTargets(response.targets ?? [])
      setMaxTargets(response.maxTargets)
      setVapid(response.vapidPublicKey ?? '')
    } catch (e) {
      setMessage(e instanceof Error ? e.message : t('unifiedPush.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  async function remove(endpoint: string) {
    try {
      await removeUnifiedPushTarget(endpoint)
      await refresh()
      setMessage(t('unifiedPush.removed'))
    } catch (e) {
      setMessage(e instanceof Error ? e.message : t('unifiedPush.removeFailed'))
    }
  }

  async function copyVapid() {
    try {
      await navigator.clipboard.writeText(vapid())
      setMessage(t('unifiedPush.copied'))
    } catch {
      setMessage(t('unifiedPush.copyFailed'))
    }
  }

  onMount(() => {
    void refresh()
  })

  return (
    <Panel title={t('unifiedPush.title')}>
      <ToggleField
        label={t('unifiedPush.deviceToggle')}
        checked={enabled()}
        onChange={() => props.updateStreamingAndSave({ unifiedPushEnabled: !enabled() })}
      />
      <div class="nx-banner nx-banner-info" role="note" aria-live="polite">
        <span>{t('unifiedPush.desc')}</span>
      </div>

      <FieldRow label={t('unifiedPush.vapidKey')}>
        <span class="nx-mono nx-info-value">{vapid() || t('unifiedPush.loading')}</span>
      </FieldRow>
      <Button size="sm" variant="ghost" disabled={!vapid()} onClick={() => void copyVapid()}>
        {t('unifiedPush.copyKey')}
      </Button>
      <div class="nx-banner nx-banner-info" role="note">
        <span>{t('unifiedPush.pairingHint')}</span>
      </div>

      <FieldRow label={t('unifiedPush.targets', { count: targets().length, max: maxTargets() ?? '—' })}>
        <Button size="sm" variant="ghost" disabled={loading()} onClick={() => void refresh()}>
          {loading() ? t('unifiedPush.loading') : t('unifiedPush.refresh')}
        </Button>
      </FieldRow>
      <Show when={targets().length === 0} fallback={
        <ul class="nx-clients">
          <For each={targets()}>
            {(target) => (
              <li class="nx-clients-row">
                <span class="nx-clients-id nx-mono" title={target.endpoint}>
                  {target.label || target.instance}
                  {target.distributor ? ` · ${target.distributor}` : ''}
                </span>
                <Button
                  size="sm"
                  variant="danger-soft"
                  onClick={() => void remove(target.endpoint)}
                  ariaLabel={t('unifiedPush.remove')}
                >
                  {t('unifiedPush.remove')}
                </Button>
              </li>
            )}
          </For>
        </ul>
      }>
        <Empty title={t('unifiedPush.noTargets')} />
      </Show>
      <Show when={message()}>
        <span class="nx-field-hint" role="status" aria-live="polite">
          {message()}
        </span>
      </Show>
    </Panel>
  )
}

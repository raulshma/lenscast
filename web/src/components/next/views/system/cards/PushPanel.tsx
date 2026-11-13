import { createSignal, onMount, Show } from 'solid-js'
import type { AllSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { permissionLabel, pushSupport, urlBase64ToUint8Array, type PushSupportReason } from '../../../../../lib/push'
import { deletePushSubscription, getVapidPublicKey, subscribePush } from '../../../../../api/client'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, ToggleField, Button } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * The reason each unsupported verdict shows, so a hidden or disabled feature
 * explains itself instead of silently vanishing — plain http:// LAN IPs are
 * the common case (Web Push needs the HTTPS dashboard mode's secure context).
 * The hints are functions so the strings track the current locale.
 */
const SUPPORT_HINTS: Record<PushSupportReason, () => string> = {
  supported: () => '',
  'insecure-context': () => t('push.insecure'),
  'no-service-worker': () => t('push.noSW'),
  'no-push-manager': () => t('push.noPushManager'),
  'permission-denied': () => t('push.permissionDeniedHint'),
}

/** Localizes lib/push's stable English permission labels at the UI layer. */
function localizedPermissionLabel(permission: string | undefined): string {
  switch (permissionLabel(permission)) {
    case 'Allowed': return t('push.allowed')
    case 'Blocked': return t('push.blocked')
    case 'Not requested': return t('push.notRequested')
    default: return t('push.unknown')
  }
}

/**
 * Web Push alerting, ported from the classic PushCard onto the kit with
 * every binding unchanged: the phone encrypts each detection event
 * (RFC 8291) and publishes it to this browser's push service, whose service
 * worker (/push-sw.js) shows the notification even with the tab closed. The
 * panel manages this browser's subscription (browser-session state through
 * the /api/push routes) and the phone-side master toggle (pushEnabled); the
 * two are independent — a stored subscription only delivers while the
 * device toggle is on.
 */
export default function PushPanel(props: Props) {
  const s = () => props.settings()
  const pushOn = () => s()?.streaming.pushEnabled ?? API_DEFAULTS.pushEnabled
  // The support verdict is a property of the browser, not of time — read once
  // at setup (the component only mounts in the browser, never on a server).
  const support: PushSupportReason = pushSupport({
    isSecureContext: window.isSecureContext,
    navigator: window.navigator,
    PushManager: window.PushManager,
    Notification: typeof Notification !== 'undefined' ? Notification : undefined,
  })
  const supported = support === 'supported'

  const [permission, setPermission] = createSignal(
    typeof Notification !== 'undefined' ? Notification.permission : undefined,
  )
  // tri-state: undefined = checking, false = not subscribed, true = subscribed.
  const [subscribed, setSubscribed] = createSignal<boolean | undefined>(undefined)
  const [busy, setBusy] = createSignal(false)
  const [message, setMessage] = createSignal('')

  onMount(() => {
    void refreshSubscription()
  })

  async function refreshSubscription() {
    try {
      const registration = await navigator.serviceWorker.getRegistration('/push-sw.js')
      const existing = await registration?.pushManager.getSubscription()
      setSubscribed(existing != null)
      if (typeof Notification !== 'undefined') setPermission(Notification.permission)
    } catch {
      setSubscribed(false)
    }
  }

  function runGuarded(action: () => Promise<unknown>) {
    if (busy()) return
    setBusy(true)
    setMessage('')
    action()
      .catch((err) => setMessage(err instanceof Error ? err.message : t('push.actionFailed')))
      .finally(() => setBusy(false))
  }

  /** Enable: permission → SW register → subscribe under the phone's VAPID key → POST. */
  function enable() {
    runGuarded(async () => {
      const permissionResult = await Notification.requestPermission()
      setPermission(permissionResult)
      if (permissionResult !== 'granted') {
        setMessage(t('push.permissionDeniedMsg'))
        return
      }
      const registration = await navigator.serviceWorker.register('/push-sw.js')
      await navigator.serviceWorker.ready
      const { publicKey } = await getVapidPublicKey()
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      })
      const keys = subscription.toJSON().keys
      const p256dh = keys?.p256dh
      const auth = keys?.auth
      if (!p256dh || !auth) throw new Error(t('push.incomplete'))
      const result = await subscribePush({ endpoint: subscription.endpoint, p256dh, auth })
      if (!result.success) throw new Error(t('push.rejected'))
      setSubscribed(true)
      setMessage(t('push.enabledMsg'))
    })
  }

  /** Disable: unsubscribe the browser, then drop the endpoint on the device. */
  function disable() {
    runGuarded(async () => {
      const registration = await navigator.serviceWorker.getRegistration('/push-sw.js')
      const existing = await registration?.pushManager.getSubscription()
      if (existing) {
        await existing.unsubscribe()
        // Best-effort: an unreachable device must not strand the browser
        // subscription, so the local unsubscribe has already happened.
        await deletePushSubscription(existing.endpoint).catch(() => undefined)
      }
      setSubscribed(false)
      setMessage(t('push.disabledMsg'))
    })
  }

  return (
    <Panel title={t('push.title')}>
      <ToggleField
        label={t('push.deviceToggle')}
        checked={pushOn()}
        onChange={() => props.updateStreamingAndSave({ pushEnabled: !pushOn() })}
      />
      <div class="nx-banner nx-banner-info" role="note" aria-live="polite">
        <span>{t('push.desc')}</span>
      </div>

      <Show
        when={supported}
        fallback={
          <div class="nx-banner nx-banner-info" role="note">
            <span>{SUPPORT_HINTS[support]()}</span>
          </div>
        }
      >
        <FieldRow label={t('push.thisBrowser')}>
          <span class="nx-mono nx-info-value">
            {subscribed() === undefined ? t('push.checking') : subscribed() ? t('push.subscribed') : t('push.notSubscribed')}
          </span>
        </FieldRow>
        <FieldRow label={t('push.notifications')}>
          <span class="nx-mono nx-info-value">{localizedPermissionLabel(permission())}</span>
        </FieldRow>
        <Show when={subscribed() === true}>
          <Button size="sm" variant="ghost" disabled={busy()} onClick={disable}>
            {busy() ? t('push.working') : t('push.disable')}
          </Button>
        </Show>
        <Show when={subscribed() === false}>
          <Button size="sm" variant="ghost" disabled={busy()} onClick={enable}>
            {busy() ? t('push.working') : t('push.enable')}
          </Button>
        </Show>
        <Show when={message()}>
          <span class="nx-field-hint" role="status" aria-live="polite">
            {message()}
          </span>
        </Show>
      </Show>
    </Panel>
  )
}

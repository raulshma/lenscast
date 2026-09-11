import { createSignal, onMount, Show } from 'solid-js'
import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import { permissionLabel, pushSupport, urlBase64ToUint8Array, type PushSupportReason } from '../lib/push'
import { deletePushSubscription, getVapidPublicKey, subscribePush } from '../api/client'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * The reason each unsupported verdict shows, so a hidden or disabled feature
 * explains itself instead of silently vanishing — plain http:// LAN IPs are
 * the common case (Web Push needs the HTTPS dashboard mode's secure context).
 */
const SUPPORT_HINTS: Record<PushSupportReason, string> = {
  supported: '',
  'insecure-context': 'Web Push needs a secure context — enable the HTTPS dashboard mode and accept its certificate, then reload.',
  'no-service-worker': 'This browser has no service worker support, so it cannot receive push notifications.',
  'no-push-manager': 'This browser does not support Web Push (no PushManager API).',
  'permission-denied': 'Notifications are blocked for this site — re-allow them in the browser’s site settings.',
}

/**
 * Web Push alerting: the phone encrypts each detection event (RFC 8291) and
 * publishes it to this browser's push service, whose service worker
 * (/push-sw.js) shows the notification even with the tab closed. The card
 * manages this browser's subscription (browser-session state through the
 * /api/push routes) and the phone-side master toggle (pushEnabled); the two
 * are independent — a stored subscription only delivers while the device
 * toggle is on.
 */
export default function PushCard(props: Props) {
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
      .catch((err) => setMessage(err instanceof Error ? err.message : 'Web Push action failed'))
      .finally(() => setBusy(false))
  }

  /** Enable: permission → SW register → subscribe under the phone's VAPID key → POST. */
  function enable() {
    runGuarded(async () => {
      const permissionResult = await Notification.requestPermission()
      setPermission(permissionResult)
      if (permissionResult !== 'granted') {
        setMessage('Notification permission was not granted')
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
      if (!p256dh || !auth) throw new Error('Browser returned an incomplete push subscription')
      const result = await subscribePush({ endpoint: subscription.endpoint, p256dh, auth })
      if (!result.success) throw new Error('The device rejected the subscription')
      setSubscribed(true)
      setMessage('This browser will now receive detection alerts')
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
      setMessage('Notifications disabled for this browser')
    })
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M18 8a6 6 0 00-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 01-3.46 0" />
        </svg>
      }
      title="Web Push"
    >
      <div class="field-group">
        <ToggleRow
          id="push-enabled-toggle"
          label="Web Push Alerts (device)"
          checked={pushOn()}
          onToggle={() => props.updateStreamingAndSave({ pushEnabled: !pushOn() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>The phone sends encrypted detection alerts to every subscribed browser — notifications arrive even with the dashboard tab closed.</span>
        </div>
      </div>

      <Show
        when={supported}
        fallback={
          <div class="field-group">
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{SUPPORT_HINTS[support]}</span>
            </div>
          </div>
        }
      >
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">This Browser</span>
            <span class="field-value">
              {subscribed() === undefined ? 'Checking…' : subscribed() ? 'Subscribed' : 'Not subscribed'}
            </span>
          </div>
          <div class="field-row">
            <span class="field-label">Notifications</span>
            <span class="field-value">{permissionLabel(permission())}</span>
          </div>
          <Show when={subscribed() === true}>
            <button type="button" class="action-btn action-btn-ghost" disabled={busy()} onClick={disable}>
              {busy() ? 'Working…' : 'Disable Notifications'}
            </button>
          </Show>
          <Show when={subscribed() === false}>
            <button type="button" class="action-btn action-btn-ghost" disabled={busy()} onClick={enable}>
              {busy() ? 'Working…' : 'Enable Notifications'}
            </button>
          </Show>
          <Show when={message()}>
            <span class="clients-cap-row" role="status" aria-live="polite">
              {message()}
            </span>
          </Show>
        </div>
      </Show>
    </SettingsCard>
  )
}

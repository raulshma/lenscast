import { Show, createSignal } from 'solid-js'
import type { AllSettings, DeviceStatus } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import { whipStatusView, whipStunField, WHIP_STATUS_LABELS_EN, WHIP_STUN_HINTS_EN } from '../lib/whip'
import { t } from '../lib/i18n'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
  streamActionLoading: () => boolean
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  handleStartWhip: () => void
  handleStopWhip: () => void
}

/**
 * WHIP Push (WebRTC-HTTP egress, RFC 9725): pushes the live feed to a
 * WHIP-ingest endpoint (MediaMTX, Cloudflare Stream, LiveKit…), independent
 * of the Web Stream and RTSP outputs. Unlike an RTMP URL the endpoint
 * carries no embedded secret — the bearer token rides whipToken, which is
 * write-only like mqttPassword: responses always carry a blank token, so the
 * input is bound to a local draft, never the server value, or the next
 * settings refresh would wipe it mid-typing. The status line and Start/Stop
 * buttons ride the status snapshot (whipActive/whipStatus/whipError) and the
 * shared stream-action pipeline, exactly like the RTSP pair.
 */
export default function WhipCard(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const whipOn = () => stream()?.whipEnabled ?? API_DEFAULTS.whipEnabled
  const whipUrl = () => stream()?.whipUrl ?? API_DEFAULTS.whipUrl
  const whipActive = () => props.status()?.streaming?.whipActive ?? false
  // Write-only token draft — see the card doc above.
  const [tokenDraft, setTokenDraft] = createSignal('')

  const statusView = () =>
    whipStatusView({
      status: props.status()?.streaming?.whipStatus,
      active: whipActive(),
      error: props.status()?.streaming?.whipError,
    }, {
      ...WHIP_STATUS_LABELS_EN,
      idle: t('status.idle'),
      connecting: t('status.connecting'),
      connected: t('status.connected'),
      error: t('status.error'),
      pushFailed: t('whip.pushFailed'),
    })
  const stunField = () => whipStunField(stream()?.whipStunServer ?? '', API_DEFAULTS.whipStunServer, {
    ...WHIP_STUN_HINTS_EN,
    invalid: t('whip.stunInvalid'),
    present: t('whip.stunSet'),
    empty: t('whip.stunEmpty'),
  })

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="17 8 12 3 7 8" />
          <line x1="12" y1="3" x2="12" y2="15" />
        </svg>
      }
      title={t('whip.title')}
    >
      <div class="field-group">
        <ToggleRow
          id="whip-toggle"
          label={t('whip.enable')}
          checked={whipOn()}
          onToggle={() => props.updateStreamingAndSave({ whipEnabled: !whipOn() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('whip.desc')}</span>
        </div>
      </div>

      <Show when={whipOn()}>
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('whip.url')}</span>
          </div>
          <input
            id="whip-url"
            type="text"
            class="field-input field-input-full"
            autocomplete="off"
            placeholder="https://ingest.example.com/live/stream-key"
            value={whipUrl()}
            onInput={(e) => props.updateStreamingDebounced({ whipUrl: e.currentTarget.value })}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('whip.token')}</span>
          </div>
          <input
            id="whip-token"
            type="password"
            class="field-input field-input-full"
            autocomplete="new-password"
            placeholder={t('common.unchanged')}
            value={tokenDraft()}
            onInput={(e) => {
              setTokenDraft(e.currentTarget.value)
              props.updateStreamingDebounced({ whipToken: e.currentTarget.value })
            }}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('whip.stun')}</span>
          </div>
          <input
            id="whip-stun-server"
            type="text"
            class="field-input field-input-full"
            autocomplete="off"
            placeholder={stunField().placeholder}
            value={stream()?.whipStunServer ?? API_DEFAULTS.whipStunServer}
            onInput={(e) => props.updateStreamingDebounced({ whipStunServer: e.currentTarget.value })}
          />
          <div
            class={`status-banner status-banner-${stunField().invalid ? 'error' : 'info'} stream-mode-hint`}
            role="note"
            aria-live="polite"
          >
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{stunField().hint}</span>
          </div>
        </div>

        {/* Live state straight from the status snapshot; the fields are
            optional, so pre-WHIP firmware reads as idle. */}
        <div class={`status-banner status-banner-${statusView().variant}`} role="status" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>
            {t('whip.statusLine', { status: statusView().label })}
            {statusView().detail ? ` — ${statusView().detail}` : ''}
          </span>
        </div>

        {/* Start/Stop */}
        <div class="card-action">
          {whipActive() ? (
            <button
              id="stop-whip-btn"
              class="card-btn card-btn-danger"
              onClick={props.handleStopWhip}
              disabled={props.streamActionLoading()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="6" y="6" width="12" height="12" rx="2" />
              </svg>
              {t('whip.stop')}
            </button>
          ) : (
            <button
              id="start-whip-btn"
              class="card-btn card-btn-primary"
              onClick={props.handleStartWhip}
              // The server refuses a start when the push is disabled or the
              // endpoint is unusable — gate on both so the reason never has
              // to round-trip.
              disabled={props.streamActionLoading() || !whipOn() || !whipUrl().trim()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              {t('whip.start')}
            </button>
          )}
        </div>
      </Show>
    </SettingsCard>
  )
}

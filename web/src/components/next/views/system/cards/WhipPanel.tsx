import { Show, createSignal } from 'solid-js'
import type { AllSettings, DeviceStatus } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { whipStatusView, whipStunField, WHIP_STATUS_LABELS_EN, WHIP_STUN_HINTS_EN } from '../../../../../lib/whip'
import { statusTone } from '../../../../../lib/statusVariant'
import { t } from '../../../../../lib/i18n'
import { VARIANT_BANNER, VARIANT_LED } from './statusMaps'
import { Panel, ToggleField, Field, TextInput, Button, Badge, Led, type BadgeTone } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

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
 * WHIP Push (WebRTC-HTTP egress, RFC 9725), ported from the classic
 * WhipCard onto the kit: pushes the live feed to a WHIP-ingest endpoint
 * (MediaMTX, Cloudflare Stream, LiveKit…), independent of the Web Stream
 * and RTSP outputs. Unlike an RTMP URL the endpoint carries no embedded
 * secret — the bearer token rides whipToken, which is write-only like
 * mqttPassword: responses always carry a blank token, so the input is bound
 * to a local draft, never the server value, or the next settings refresh
 * would wipe it mid-typing. The status line and Start/Stop buttons ride the
 * status snapshot (whipActive/whipStatus/whipError) and the shared
 * stream-action pipeline, exactly like the RTSP pair.
 */
export default function WhipPanel(props: Props) {
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
  const whipOn = () => stream()?.whipEnabled ?? API_DEFAULTS.whipEnabled
  const whipUrl = () => stream()?.whipUrl ?? API_DEFAULTS.whipUrl
  const whipActive = () => props.status()?.streaming?.whipActive ?? false
  // Write-only token draft — see the panel doc above.
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
  // Header chip: quiet while idle; anything else (connecting, live, error)
  // is a state worth seeing from across the page.
  const chipTone = (): BadgeTone | null => statusTone(statusView().variant)

  return (
    <SettingItem id="streaming.whipEnabled">
      <Panel
      title={t('whip.title')}
      badge={
        <Show when={chipTone()}>
          {(tone) => (
            <Badge tone={tone()}>
              <Led tone={VARIANT_LED[statusView().variant]} />
              {statusView().label}
            </Badge>
          )}
        </Show>
      }
    >
      <ToggleField
        disabled={readOnly()}
        label={t('whip.enable')}
        checked={whipOn()}
        onChange={() => save({ whipEnabled: !whipOn() })}
      />
      <div class="nx-banner nx-banner-neutral" role="note" aria-live="polite">
        {t('whip.desc')}
      </div>

      <Show when={whipOn()}>
        <Field label={t('whip.url')}>
          <TextInput
            disabled={readOnly()}
            mono
            placeholder="https://ingest.example.com/live/stream-key"
            value={whipUrl()}
            onInput={(v) => saveDebounced({ whipUrl: v })}
            ariaLabel={t('whip.url')}
          />
        </Field>

        <Field label={t('whip.token')}>
          <TextInput
            disabled={readOnly()}
            mono
            type="password"
            placeholder={t('common.unchanged')}
            value={tokenDraft()}
            onInput={(v) => {
              setTokenDraft(v)
              saveDebounced({ whipToken: v })
            }}
            ariaLabel={t('whip.token')}
          />
        </Field>

        <Field label={t('whip.stun')}>
          <TextInput
            disabled={readOnly()}
            mono
            placeholder={stunField().placeholder}
            value={stream()?.whipStunServer ?? API_DEFAULTS.whipStunServer}
            onInput={(v) => saveDebounced({ whipStunServer: v })}
            ariaLabel={t('whip.stun')}
          />
        </Field>
        <div
          class={`nx-banner ${stunField().invalid ? 'nx-banner-danger' : 'nx-banner-neutral'}`}
          role="note"
          aria-live="polite"
        >
          {stunField().hint}
        </div>

        {/* Live state straight from the status snapshot; the fields are
            optional, so pre-WHIP firmware reads as idle. */}
        <div class={`nx-banner ${VARIANT_BANNER[statusView().variant]}`} role="status" aria-live="polite">
          <Led tone={VARIANT_LED[statusView().variant]} />
          <span>
            {t('whip.statusLine', { status: statusView().label })}
            {statusView().detail ? ` — ${statusView().detail}` : ''}
          </span>
        </div>

        {/* Start/Stop — the start verb is a quiet outline key: the Protocols
            rack pairs it with RTMP's, and the rack has no one primary. */}
        {whipActive() ? (
          <Button
            variant="danger"
            size="sm"
            disabled={props.streamActionLoading() || readOnly()}
            onClick={() => { if (!readOnly()) props.handleStopWhip() }}
            ariaLabel={t('whip.stop')}
          >
            {t('whip.stop')}
          </Button>
        ) : (
          <Button
            variant="secondary"
            size="sm"
            // The server refuses a start when the push is disabled or the
            // endpoint is unusable — gate on both so the reason never has
            // to round-trip.
            disabled={props.streamActionLoading() || readOnly() || !whipOn() || !whipUrl().trim()}
            onClick={() => { if (!readOnly()) props.handleStartWhip() }}
            ariaLabel={t('whip.start')}
          >
            {t('whip.start')}
          </Button>
        )}
      </Show>
      </Panel>
    </SettingItem>
  )
}

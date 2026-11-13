import { Show, createSignal } from 'solid-js'
import type { AllSettings, DeviceStatus } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { rtmpStatusView, rtmpUrlField, RTMP_STATUS_LABELS_EN, RTMP_HINTS_EN } from '../../../../../lib/rtmp'
import { statusTone } from '../../../../../lib/statusVariant'
import { t } from '../../../../../lib/i18n'
import { VARIANT_BANNER, VARIANT_LED } from './statusMaps'
import { Panel, ToggleField, Field, TextInput, Button, Badge, Led, type BadgeTone } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
  streamActionLoading: () => boolean
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  handleStartRtmp: () => void
  handleStopRtmp: () => void
}

/**
 * RTMP Push (publish to an RTMP/RTMPS server), ported from the classic
 * RtmpCard onto the kit: pushes the live feed to an RTMP ingest (MediaMTX,
 * nginx-rtmp, YouTube…), independent of the Web Stream and RTSP outputs.
 * Like the WHIP bearer token the push URL is write-only (the stream key is
 * embedded in it): responses never carry it, the input binds to a local
 * draft, and an empty save keeps the stored one — the live hint below only
 * checks scheme + host on what was typed; the server's RtmpUrl.parse has
 * the final word at start time, where the H.264-only gate also refuses an
 * H.265 RTSP codec. The status line and Start/Stop buttons ride the status
 * snapshot (rtmpActive/rtmpStatus/rtmpError) and the shared stream-action
 * pipeline, exactly like the WHIP and RTSP pairs.
 */
export default function RtmpPanel(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const rtmpOn = () => stream()?.rtmpEnabled ?? API_DEFAULTS.rtmpEnabled
  const rtmpActive = () => props.status()?.streaming?.rtmpActive ?? false
  // The write-only draft: the stored URL never echoes back, so the field
  // starts blank ("(unchanged)" placeholder) like the WHIP token field.
  const [urlDraft, setUrlDraft] = createSignal('')

  const statusView = () =>
    rtmpStatusView({
      status: props.status()?.streaming?.rtmpStatus,
      active: rtmpActive(),
      error: props.status()?.streaming?.rtmpError,
    }, {
      ...RTMP_STATUS_LABELS_EN,
      idle: t('status.idle'),
      connecting: t('status.connecting'),
      connected: t('status.connected'),
      error: t('status.error'),
      pushFailed: t('rtmp.pushFailed'),
    })
  const urlField = () => rtmpUrlField(urlDraft(), {
    ...RTMP_HINTS_EN,
    empty: t('rtmp.hintEmpty'),
    scheme: t('rtmp.hintScheme'),
    host: t('rtmp.hintHost'),
    ok: t('rtmp.hintOk'),
  })
  // Header chip: quiet while idle; anything else (connecting, live, error)
  // is a state worth seeing from across the page.
  const chipTone = (): BadgeTone | null => statusTone(statusView().variant)

  return (
    <Panel
      title={t('rtmp.title')}
      badge={
        <Show when={chipTone()}>
          {(tone) => (
            <Badge tone={tone()} mono>
              <Led tone={VARIANT_LED[statusView().variant]} />
              {statusView().label}
            </Badge>
          )}
        </Show>
      }
    >
      <ToggleField
        label={t('rtmp.enable')}
        checked={rtmpOn()}
        onChange={() => props.updateStreamingAndSave({ rtmpEnabled: !rtmpOn() })}
      />
      <div class="nx-banner nx-banner-info" role="note" aria-live="polite">
        {t('rtmp.desc')}
      </div>

      <Show when={rtmpOn()}>
        <Field label={t('rtmp.url')}>
          <TextInput
            mono
            type="password"
            placeholder={`${t('common.unchanged')} rtmp://ingest.example.com/live/stream-key`}
            value={urlDraft()}
            onInput={(v) => {
              setUrlDraft(v)
              props.updateStreamingDebounced({ rtmpUrl: v })
            }}
            ariaLabel={t('rtmp.url')}
          />
        </Field>
        <div
          class={`nx-banner ${urlField().invalid ? 'nx-banner-danger' : 'nx-banner-info'}`}
          role="note"
          aria-live="polite"
        >
          {urlField().hint}
        </div>

        {/* Live state straight from the status snapshot; the fields are
            optional, so pre-RTMP firmware reads as idle. */}
        <div class={`nx-banner ${VARIANT_BANNER[statusView().variant]}`} role="status" aria-live="polite">
          <Led tone={VARIANT_LED[statusView().variant]} />
          <span>
            {t('rtmp.statusLine', { status: statusView().label })}
            {statusView().detail ? ` — ${statusView().detail}` : ''}
          </span>
        </div>

        {/* Start/Stop */}
        {rtmpActive() ? (
          <Button
            variant="danger"
            size="sm"
            disabled={props.streamActionLoading()}
            onClick={props.handleStopRtmp}
            ariaLabel={t('rtmp.stop')}
          >
            {t('rtmp.stop')}
          </Button>
        ) : (
          <Button
            variant="primary"
            size="sm"
            // The server refuses a start when the push is disabled or the
            // stored URL is unusable — the field is write-only (blank
            // never means unconfigured), so only the toggle gates here and
            // a refusal lands on the status line with its readable reason.
            disabled={props.streamActionLoading() || !rtmpOn()}
            onClick={props.handleStartRtmp}
            ariaLabel={t('rtmp.start')}
          >
            {t('rtmp.start')}
          </Button>
        )}
      </Show>
    </Panel>
  )
}

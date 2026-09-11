import { Show, createSignal } from 'solid-js'
import type { AllSettings, DeviceStatus } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import { rtmpStatusView, rtmpUrlField } from '../lib/rtmp'

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
 * RTMP Push (publish to an RTMP/RTMPS server): pushes the live feed to an
 * RTMP ingest (MediaMTX, nginx-rtmp, YouTube…), independent of the Web
 * Stream and RTSP outputs. Like the WHIP bearer token the push URL is
 * write-only (the stream key is embedded in it): responses never carry it,
 * the input binds to a local draft, and an empty save keeps the stored one —
 * the live hint below only checks scheme + host on what was typed; the
 * server's RtmpUrl.parse has the final word at start time, where the
 * H.264-only gate also refuses an H.265 RTSP codec. The status line and
 * Start/Stop buttons ride the status snapshot (rtmpActive/rtmpStatus/
 * rtmpError) and the shared stream-action pipeline, exactly like the WHIP
 * and RTSP pairs.
 */
export default function RtmpCard(props: Props) {
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
    })
  const urlField = () => rtmpUrlField(urlDraft())

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="2" />
          <path d="M16.24 7.76a6 6 0 0 1 0 8.49" />
          <path d="M7.76 16.24a6 6 0 0 1 0-8.49" />
          <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
          <path d="M4.93 19.07a10 10 0 0 1 0-14.14" />
        </svg>
      }
      title="RTMP Push"
    >
      <div class="field-group">
        <ToggleRow
          id="rtmp-toggle"
          label="Enable RTMP Push"
          checked={rtmpOn()}
          onToggle={() => props.updateStreamingAndSave({ rtmpEnabled: !rtmpOn() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>
            Pushes the live feed to an RTMP/RTMPS server (MediaMTX, nginx-rtmp, YouTube…). Independent of Web Stream
            and RTSP. RTMP is H.264-only — if the Video Codec in RTSP settings is H.265, the start is refused.
          </span>
        </div>
      </div>

      <Show when={rtmpOn()}>
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Push URL</span>
          </div>
          <input
            id="rtmp-url"
            type="password"
            class="field-input field-input-full"
            autocomplete="new-password"
            placeholder="(unchanged) rtmp://ingest.example.com/live/stream-key"
            value={urlDraft()}
            onInput={(e) => {
              setUrlDraft(e.currentTarget.value)
              props.updateStreamingDebounced({ rtmpUrl: e.currentTarget.value })
            }}
          />
          <div
            class={`status-banner status-banner-${urlField().invalid ? 'error' : 'info'} stream-mode-hint`}
            role="note"
            aria-live="polite"
          >
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{urlField().hint}</span>
          </div>
        </div>

        {/* Live state straight from the status snapshot; the fields are
            optional, so pre-RTMP firmware reads as idle. */}
        <div class={`status-banner status-banner-${statusView().variant}`} role="status" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>
            RTMP push: {statusView().label}
            {statusView().detail ? ` — ${statusView().detail}` : ''}
          </span>
        </div>

        {/* Start/Stop */}
        <div class="card-action">
          {rtmpActive() ? (
            <button
              id="stop-rtmp-btn"
              class="card-btn card-btn-danger"
              onClick={props.handleStopRtmp}
              disabled={props.streamActionLoading()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="6" y="6" width="12" height="12" rx="2" />
              </svg>
              Stop RTMP
            </button>
          ) : (
            <button
              id="start-rtmp-btn"
              class="card-btn card-btn-primary"
              onClick={props.handleStartRtmp}
              // The server refuses a start when the push is disabled or the
              // stored URL is unusable — the field is write-only (blank
              // never means unconfigured), so only the toggle gates here and
              // a refusal lands on the status line with its readable reason.
              disabled={props.streamActionLoading() || !rtmpOn()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              Start RTMP
            </button>
          )}
        </div>
      </Show>
    </SettingsCard>
  )
}

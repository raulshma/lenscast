import { Show } from 'solid-js'
import type { AllSettings, RtspInputFormat } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import SecurityCard from './SecurityCard'
import EventFeed from './EventFeed'
import BackupCard from './BackupCard'
import AuthCard from './AuthCard'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  setRecordingConfigAudio: (v: boolean) => void
}

export default function AppSettingsPanel(props: Props) {
  const s = () => props.settings()
  const webStreamingEnabled = () => s()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled

  return (
    <section class="settings-panel" id="app-settings-panel">
      {/* Streaming */}
      <SettingsCard
        icon={
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M5 12.55a11 11 0 0114.08 0" />
            <path d="M1.42 9a16 16 0 0121.16 0" />
            <path d="M8.53 16.11a6 6 0 016.95 0" />
            <circle cx="12" cy="20" r="1" />
          </svg>
        }
        title="Web Streaming"
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Enable Web Streaming</span>
            <label class="toggle-switch" for="web-stream-toggle-app">
              <input
                id="web-stream-toggle-app"
                type="checkbox"
                checked={webStreamingEnabled()}
                onChange={() => props.updateStreamingAndSave({ webStreamingEnabled: !webStreamingEnabled() })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>Web Stream and RTSP are independent. You can keep RTSP enabled while Web Stream is off.</span>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">JPEG Quality</span>
            <span class="field-value">{s()?.streaming?.jpegQuality ?? API_DEFAULTS.jpegQuality}%</span>
          </div>
          <input
            id="jpeg-quality-slider-app"
            type="range"
            class="custom-range"
            min={API_DEFAULTS.jpegQualityMin}
            max={API_DEFAULTS.jpegQualityMax}
            step={5}
            disabled={!webStreamingEnabled()}
            value={s()?.streaming?.jpegQuality ?? API_DEFAULTS.jpegQuality}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingDebounced({ jpegQuality: v })
            }}
          />
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Adaptive Bitrate</span>
            <label class="toggle-switch" for="adaptive-bitrate-toggle-app">
              <input
                id="adaptive-bitrate-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.adaptiveBitrateEnabled ?? API_DEFAULTS.adaptiveBitrateEnabled}
                onChange={() => props.updateStreamingAndSave({ adaptiveBitrateEnabled: !(s()?.streaming?.adaptiveBitrateEnabled ?? API_DEFAULTS.adaptiveBitrateEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>Automatically adjusts quality and frame rate based on network conditions.</span>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Show Preview on Device</span>
            <label class="toggle-switch" for="show-preview-toggle-app">
              <input
                id="show-preview-toggle-app"
                type="checkbox"
                disabled={!webStreamingEnabled()}
                checked={s()?.streaming?.showPreview ?? API_DEFAULTS.showPreview}
                onChange={() => props.updateStreamingAndSave({ showPreview: !(s()?.streaming?.showPreview ?? API_DEFAULTS.showPreview) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">mDNS Discovery</span>
            <label class="toggle-switch" for="mdns-toggle-app">
              <input
                id="mdns-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.mdnsEnabled ?? API_DEFAULTS.mdnsEnabled}
                onChange={() => props.updateStreamingAndSave({ mdnsEnabled: !(s()?.streaming?.mdnsEnabled ?? API_DEFAULTS.mdnsEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>Advertises the stream on the local network so clients can discover it.</span>
          </div>
        </div>

      </SettingsCard>

      {/* Audio */}
      <SettingsCard
        icon={
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M11 5L6 9H2v6h4l5 4V5z" />
            <path d="M19.07 4.93a10 10 0 010 14.14" />
            <path d="M15.54 8.46a5 5 0 010 7.07" />
          </svg>
        }
        title="Audio"
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Include Audio in Live Stream</span>
            <label class="toggle-switch" for="stream-audio-toggle-app">
              <input
                id="stream-audio-toggle-app"
                type="checkbox"
                disabled={!webStreamingEnabled()}
                checked={s()?.streaming?.streamAudioEnabled ?? API_DEFAULTS.streamAudioEnabled}
                onChange={() => props.updateStreamingAndSave({ streamAudioEnabled: !(s()?.streaming?.streamAudioEnabled ?? API_DEFAULTS.streamAudioEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Echo Cancellation & Noise Suppression</span>
            <label class="toggle-switch" for="echo-cancel-toggle-app">
              <input
                id="echo-cancel-toggle-app"
                type="checkbox"
                disabled={!webStreamingEnabled()}
                checked={s()?.streaming?.streamAudioEchoCancellation ?? API_DEFAULTS.streamAudioEchoCancellation}
                onChange={() => props.updateStreamingAndSave({ streamAudioEchoCancellation: !(s()?.streaming?.streamAudioEchoCancellation ?? API_DEFAULTS.streamAudioEchoCancellation) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Live Audio Bitrate</span>
            <span class="field-value">{s()?.streaming?.streamAudioBitrateKbps ?? API_DEFAULTS.streamAudioBitrateKbps} kbps</span>
          </div>
          <input
            id="audio-bitrate-slider-app"
            type="range"
            class="custom-range"
            min={API_DEFAULTS.audioBitrateMinKbps}
            max={API_DEFAULTS.audioBitrateMaxKbps}
            step={16}
            disabled={!webStreamingEnabled()}
            value={s()?.streaming?.streamAudioBitrateKbps ?? API_DEFAULTS.streamAudioBitrateKbps}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingDebounced({ streamAudioBitrateKbps: v })
            }}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Audio Channels</span>
          </div>
          <select
            id="audio-channels-select-app"
            class="field-select field-select-full"
            disabled={!webStreamingEnabled()}
            value={`${s()?.streaming?.streamAudioChannels ?? API_DEFAULTS.streamAudioChannels}`}
            onChange={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingAndSave({ streamAudioChannels: v })
            }}
          >
            <option value="1">Mono</option>
            <option value="2">Stereo</option>
          </select>
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Include Audio in Recordings</span>
            <label class="toggle-switch" for="rec-audio-toggle-app">
              <input
                id="rec-audio-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.recordingAudioEnabled ?? API_DEFAULTS.recordingAudioEnabled}
                onChange={() => {
                  const newVal = !(s()?.streaming?.recordingAudioEnabled ?? API_DEFAULTS.recordingAudioEnabled)
                  props.updateStreamingAndSave({ recordingAudioEnabled: newVal })
                  props.setRecordingConfigAudio(newVal)
                }}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </div>
      </SettingsCard>

      {/* HTTPS */}
      <SettingsCard
        icon={
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="3" y="11" width="18" height="11" rx="2" />
            <path d="M7 11V7a5 5 0 0110 0v4" />
          </svg>
        }
        title="HTTPS (TLS)"
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Serve Dashboard over HTTPS</span>
            <label class="toggle-switch" for="https-toggle-app">
              <input
                id="https-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.httpsEnabled ?? API_DEFAULTS.httpsEnabled}
                onChange={() => props.updateStreamingAndSave({ httpsEnabled: !(s()?.streaming?.httpsEnabled ?? API_DEFAULTS.httpsEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>Self-signed certificate. Accept the browser warning once, then verify the fingerprint shown on the phone's Connect sheet. Enables encrypted streams and microphone talkback.</span>
          </div>
        </div>
      </SettingsCard>

      {/* RTSP */}
      <SettingsCard
        icon={
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M12 2L2 7l10 5 10-5-10-5z" />
            <path d="M2 17l10 5 10-5" />
            <path d="M2 12l10 5 10-5" />
          </svg>
        }
        title="RTSP Stream"
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Enable RTSP Streaming</span>
            <label class="toggle-switch" for="rtsp-toggle-app">
              <input
                id="rtsp-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled}
                onChange={() => props.updateStreamingAndSave({ rtspEnabled: !(s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </div>

        <Show when={s()?.streaming?.rtspEnabled}>
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">RTSP Port</span>
              <span class="field-value">{s()?.streaming?.rtspPort ?? API_DEFAULTS.rtspPort}</span>
            </div>
            <input
              id="rtsp-port-slider-app"
              type="range"
              class="custom-range"
              min={API_DEFAULTS.rtspPortMin}
              max={API_DEFAULTS.rtspPortMax}
              step={1}
              value={s()?.streaming?.rtspPort ?? API_DEFAULTS.rtspPort}
              onInput={(e) => {
                const v = parseInt(e.currentTarget.value)
                props.updateStreamingDebounced({ rtspPort: v })
              }}
            />
          </div>

          <div class="field-group">
            <div class="field-row">
              <span class="field-label">Encoder Input Format</span>
            </div>
            <select
              id="rtsp-format-select-app"
              class="field-select field-select-full"
              value={s()?.streaming?.rtspInputFormat ?? API_DEFAULTS.rtspInputFormat}
              onChange={(e) => {
                props.updateStreamingAndSave({ rtspInputFormat: e.currentTarget.value as RtspInputFormat })
              }}
            >
              <option value="AUTO">Auto</option>
              <option value="NV21">NV21</option>
              <option value="NV12">NV12</option>
              <option value="I420">I420</option>
            </select>
          </div>
        </Show>
      </SettingsCard>

      <SecurityCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <EventFeed />

      <BackupCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <AuthCard />
    </section>
  )
}

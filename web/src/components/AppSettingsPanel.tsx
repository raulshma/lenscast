import { Show } from 'solid-js'
import type { AllSettings, DeviceStatus, RtspInputFormat, RtspResolution, RtspVideoCodec } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import SecurityCard from './SecurityCard'
import EventFeed from './EventFeed'
import DetectionStatsCard from './DetectionStatsCard'
import RecordingTimeline from './RecordingTimeline'
import StorageCard from './StorageCard'
import SystemPanel from './SystemPanel'
import BackupCard from './BackupCard'
import MqttCard from './MqttCard'
import PushCard from './PushCard'
import WhipCard from './WhipCard'
import RtmpCard from './RtmpCard'
import AuthCard from './AuthCard'
import AuditCard from './AuditCard'
import ConfigBackupCard from './ConfigBackupCard'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
  streamActionLoading: () => boolean
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  setRecordingConfigAudio: (v: boolean) => void
  handleStartWhip: () => void
  handleStopWhip: () => void
  handleStartRtmp: () => void
  handleStopRtmp: () => void
  /** True for a viewer-role session: the admin-only cards render nothing. */
  readOnly?: () => boolean
}

export default function AppSettingsPanel(props: Props) {
  const s = () => props.settings()
  const webStreamingEnabled = () => s()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled
  const rtspVideoCodec = () => s()?.streaming?.rtspVideoCodec ?? API_DEFAULTS.rtspVideoCodec
  // A viewer session hides the admin-only surfaces entirely; the remaining
  // write controls stay on the banner + 403 path (the server answers
  // "Admin access required" and the global error handling surfaces it).
  const isAdmin = () => !props.readOnly?.()

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
        title={t('webstream.title')}
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('webstream.enable')}</span>
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
            <span>{t('webstream.independent')}</span>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('webstream.jpegQuality')}</span>
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
            <span class="field-label">{t('webstream.adaptive')}</span>
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
            <span>{t('webstream.adaptiveDesc')}</span>
          </div>

          <div class="field-row field-row-toggle" style={{ 'margin-top': '10px' }}>
            <span class="field-label">{t('webstream.adaptiveEncoded')}</span>
            <label class="toggle-switch" for="adaptive-encoded-toggle-app">
              <input
                id="adaptive-encoded-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.adaptiveEncodedBitrateEnabled ?? API_DEFAULTS.adaptiveEncodedBitrateEnabled}
                onChange={() => props.updateStreamingAndSave({ adaptiveEncodedBitrateEnabled: !(s()?.streaming?.adaptiveEncodedBitrateEnabled ?? API_DEFAULTS.adaptiveEncodedBitrateEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('webstream.adaptiveEncodedDesc')}</span>
          </div>
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('webstream.showPreview')}</span>
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
            <span class="field-label">{t('webstream.mdns')}</span>
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
            <span>{t('webstream.mdnsDesc')}</span>
          </div>
        </div>

        <div class="field-group">
          <ToggleRow
            id="onvif-toggle"
            label={t('webstream.onvif')}
            checked={s()?.streaming?.onvifEnabled ?? API_DEFAULTS.onvifEnabled}
            onToggle={() => props.updateStreamingAndSave({ onvifEnabled: !(s()?.streaming?.onvifEnabled ?? API_DEFAULTS.onvifEnabled) })}
          />
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('webstream.onvifDesc')}</span>
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
        title={t('audio.title')}
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('audio.includeLive')}</span>
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
            <span class="field-label">{t('audio.echo')}</span>
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
            <span class="field-label">{t('audio.bitrate')}</span>
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
            <span class="field-label">{t('audio.channels')}</span>
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
            <option value="1">{t('audio.mono')}</option>
            <option value="2">{t('audio.stereo')}</option>
          </select>
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('audio.includeRecordings')}</span>
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

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('webstream.encryptCaptures')}</span>
            <label class="toggle-switch" for="media-encryption-toggle-app">
              <input
                id="media-encryption-toggle-app"
                type="checkbox"
                checked={s()?.streaming?.mediaEncryptionEnabled ?? API_DEFAULTS.mediaEncryptionEnabled}
                onChange={() => props.updateStreamingAndSave({ mediaEncryptionEnabled: !(s()?.streaming?.mediaEncryptionEnabled ?? API_DEFAULTS.mediaEncryptionEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('webstream.encryptCapturesDesc')}</span>
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
        title={t('https.title')}
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('https.serve')}</span>
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
            <span>{t('https.desc')}</span>
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
        title={t('rtsp.title')}
      >
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">{t('rtsp.enable')}</span>
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
            <div class="field-row field-row-toggle">
              <span class="field-label">{t('rtsp.subStream')}</span>
              <label class="toggle-switch" for="rtsp-substream-toggle-app">
                <input
                  id="rtsp-substream-toggle-app"
                  type="checkbox"
                  checked={s()?.streaming?.rtspSubStreamEnabled ?? API_DEFAULTS.rtspSubStreamEnabled}
                  onChange={() => props.updateStreamingAndSave({ rtspSubStreamEnabled: !(s()?.streaming?.rtspSubStreamEnabled ?? API_DEFAULTS.rtspSubStreamEnabled) })}
                />
                <span class="toggle-slider" />
              </label>
            </div>
            <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{t('rtsp.subStreamDesc')}</span>
            </div>
          </div>

          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('rtsp.port')}</span>
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
              <span class="field-label">{t('rtsp.inputFormat')}</span>
            </div>
            <select
              id="rtsp-format-select-app"
              class="field-select field-select-full"
              value={s()?.streaming?.rtspInputFormat ?? API_DEFAULTS.rtspInputFormat}
              onChange={(e) => {
                props.updateStreamingAndSave({ rtspInputFormat: e.currentTarget.value as RtspInputFormat })
              }}
            >
              <option value="AUTO">{t('common.auto')}</option>
              <option value="NV21">NV21</option>
              <option value="NV12">NV12</option>
              <option value="I420">I420</option>
            </select>
          </div>

          {/* Resolution applies with an RTSP restart — the running pipeline
              keeps streaming at the previous size until it is restarted. */}
          <div class="field-group">
            <div class="field-row">
              <span class="field-label" title={t('rtsp.restartHint')}>{t('common.resolution')}</span>
            </div>
            <select
              id="rtsp-resolution-select-app"
              class="field-select field-select-full"
              title={t('rtsp.restartHint')}
              value={s()?.streaming?.rtspResolution ?? API_DEFAULTS.rtspResolution}
              onChange={(e) => {
                props.updateStreamingAndSave({ rtspResolution: e.currentTarget.value as RtspResolution })
              }}
            >
              <option value="480p">480p</option>
              <option value="720p">720p</option>
              <option value="1080p">1080p</option>
            </select>
          </div>

          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('rtsp.codec')}</span>
            </div>
            <select
              id="rtsp-codec-select-app"
              class="field-select field-select-full"
              value={rtspVideoCodec()}
              onChange={(e) => {
                props.updateStreamingAndSave({ rtspVideoCodec: e.currentTarget.value as RtspVideoCodec })
              }}
            >
              <option value="h264">{t('rtsp.codecH264')}</option>
              <option value="h265">H.265</option>
            </select>
          </div>
        </Show>
      </SettingsCard>

      <WhipCard
        settings={props.settings}
        status={props.status}
        streamActionLoading={props.streamActionLoading}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        handleStartWhip={props.handleStartWhip}
        handleStopWhip={props.handleStopWhip}
      />

      <RtmpCard
        settings={props.settings}
        status={props.status}
        streamActionLoading={props.streamActionLoading}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        handleStartRtmp={props.handleStartRtmp}
        handleStopRtmp={props.handleStopRtmp}
      />

      <SecurityCard
        settings={props.settings}
        status={props.status}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      {/* #/events deep links scroll the feed into view via this anchor. */}
      <div id="event-feed-anchor">
        <EventFeed readOnly={props.readOnly} />
      </div>

      <DetectionStatsCard />

      <BackupCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <StorageCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <RecordingTimeline />

      <SystemPanel />

      <MqttCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <PushCard settings={props.settings} updateStreamingAndSave={props.updateStreamingAndSave} />

      <Show when={isAdmin()}>
        <AuthCard />

        <ConfigBackupCard />

        <AuditCard />
      </Show>
    </section>
  )
}

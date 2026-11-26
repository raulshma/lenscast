import { Show, createSignal } from 'solid-js'
import type { AllSettings, RtspInputFormat, RtspResolution, RtspVideoCodec } from '../../../../types'
import { API_DEFAULTS } from '../../../../api/defaults'
import { t } from '../../../../lib/i18n'
import { streamToggle } from '../../../../lib/streamToggle'
import { Panel, ToggleField, SliderField, FieldRow, Select, Led, TextInput } from '../../ui'
import { SettingItem, useInspectorSection } from '../../ui/SettingItem'

// The five inline streaming/audio/https/rtsp/keep-awake cards, ported onto
// the next UI's component kit. Same exports, same props, same bindings —
// the classic App tab (AppSettingsPanel) and the next UI's System view
// compose the same settings from one source. Only the markup moved from
// SettingsCard/ToggleRow/field-* to Panel/ToggleField/SliderField/FieldRow.

export interface StreamingCardProps {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  setRecordingConfigAudio: (v: boolean) => void
}

function useToggles(props: StreamingCardProps) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const s = () => props.settings()
  const streaming = () => s()?.streaming
  const save = (patch: Partial<AllSettings['streaming']>) => {
    if (!readOnly()) props.updateStreamingAndSave(patch)
  }
  const saveDebounced = (patch: Partial<AllSettings['streaming']>) => {
    if (!readOnly()) props.updateStreamingDebounced(patch)
  }
  const setRecordingAudio = (enabled: boolean) => {
    if (!readOnly()) props.setRecordingConfigAudio(enabled)
  }
  return {
    s,
    readOnly,
    save,
    saveDebounced,
    setRecordingAudio,
    adaptiveBitrate: streamToggle(streaming, 'adaptiveBitrateEnabled', save),
    adaptiveEncoded: streamToggle(streaming, 'adaptiveEncodedBitrateEnabled', save),
    mdns: streamToggle(streaming, 'mdnsEnabled', save),
    onvif: streamToggle(streaming, 'onvifEnabled', save),
    mediaEncryption: streamToggle(streaming, 'mediaEncryptionEnabled', save),
    httpsToggle: streamToggle(streaming, 'httpsEnabled', save),
    rtspTls: streamToggle(streaming, 'rtspTlsEnabled', save),
    rtspSubStream: streamToggle(streaming, 'rtspSubStreamEnabled', save),
    rtspBasicAuthCompat: streamToggle(streaming, 'rtspBasicAuthCompat', save),
    keepAwake: streamToggle(streaming, 'keepAwakeEnabled', save),
  }
}

export function WebStreamCard(props: StreamingCardProps) {
  const { s, readOnly, save, saveDebounced, adaptiveBitrate, adaptiveEncoded, mdns, onvif } = useToggles(props)
  const webStreamingEnabled = () => s()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled

  return (
    <Panel title={t('webstream.title')} badge={<Led tone={webStreamingEnabled() ? 'ok' : 'off'} />}>
      <SettingItem id="streaming.webEnabled">
        <ToggleField
          label={t('webstream.enable')}
          hint={t('webstream.independent')}
          checked={webStreamingEnabled()}
          disabled={readOnly()}
          onChange={() => save({ webStreamingEnabled: !webStreamingEnabled() })}
        />
      </SettingItem>

      {/* Shared stream tier across Web (MJPEG, WHEP, HLS, WS), RTSP, WHIP,
          and RTMP — keep accessible even when RTSP or Web Stream is off. */}
      <SettingItem id="streaming.resolution">
        <FieldRow label={t('webstream.resolution')} hint={t('webstream.resolutionHint')}>
          <Select
            value={s()?.streaming?.rtspResolution ?? API_DEFAULTS.rtspResolution}
            options={[
              { value: '480p', label: '480p (4:3)' },
              { value: '720p', label: '720p' },
              { value: '720p43', label: '720p (4:3)' },
              { value: '1080p', label: '1080p' },
              { value: '1080p43', label: '1080p (4:3)' },
            ]}
            disabled={readOnly()}
            onChange={(v) => save({ rtspResolution: v as RtspResolution })}
            ariaLabel={t('webstream.resolution')}
          />
        </FieldRow>
      </SettingItem>

      <SettingItem id="streaming.webQuality">
        <SliderField
          label={t('webstream.jpegQuality')}
          display={`${s()?.streaming?.jpegQuality ?? API_DEFAULTS.jpegQuality}%`}
          min={API_DEFAULTS.jpegQualityMin}
          max={API_DEFAULTS.jpegQualityMax}
          step={5}
          disabled={!webStreamingEnabled() || readOnly()}
          value={s()?.streaming?.jpegQuality ?? API_DEFAULTS.jpegQuality}
          onChange={(v) => saveDebounced({ jpegQuality: v })}
        />
      </SettingItem>

      <SettingItem id="streaming.adaptiveBitrate">
        <ToggleField
          label={t('webstream.adaptive')}
          hint={t('webstream.adaptiveDesc')}
          checked={adaptiveBitrate.checked()}
          disabled={readOnly()}
          onChange={adaptiveBitrate.onToggle}
        />
      </SettingItem>
      <ToggleField
        label={t('webstream.adaptiveEncoded')}
        hint={t('webstream.adaptiveEncodedDesc')}
        checked={adaptiveEncoded.checked()}
        disabled={readOnly()}
        onChange={adaptiveEncoded.onToggle}
      />

      <ToggleField
        label={t('webstream.showPreview')}
        checked={s()?.streaming?.showPreview ?? API_DEFAULTS.showPreview}
        disabled={!webStreamingEnabled() || readOnly()}
        onChange={() => save({ showPreview: !(s()?.streaming?.showPreview ?? API_DEFAULTS.showPreview) })}
      />

      <ToggleField
        label={t('webstream.mdns')}
        hint={t('webstream.mdnsDesc')}
        checked={mdns.checked()}
        disabled={readOnly()}
        onChange={mdns.onToggle}
      />

      <ToggleField
        label={t('webstream.onvif')}
        hint={t('webstream.onvifDesc')}
        checked={onvif.checked()}
        disabled={readOnly()}
        onChange={onvif.onToggle}
      />
    </Panel>
  )
}

export function AudioCard(props: StreamingCardProps) {
  const { s, readOnly, save, saveDebounced, setRecordingAudio, mediaEncryption } = useToggles(props)
  const webStreamingEnabled = () => s()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled

  return (
    <Panel title={t('audio.title')}>
      <SettingItem id="streaming.audioEnabled">
        <ToggleField
          label={t('audio.includeLive')}
          checked={s()?.streaming?.streamAudioEnabled ?? API_DEFAULTS.streamAudioEnabled}
          disabled={!webStreamingEnabled() || readOnly()}
          onChange={() => save({ streamAudioEnabled: !(s()?.streaming?.streamAudioEnabled ?? API_DEFAULTS.streamAudioEnabled) })}
        />
      </SettingItem>

      <ToggleField
        label={t('audio.echo')}
        checked={s()?.streaming?.streamAudioEchoCancellation ?? API_DEFAULTS.streamAudioEchoCancellation}
        disabled={!webStreamingEnabled() || readOnly()}
        onChange={() => save({ streamAudioEchoCancellation: !(s()?.streaming?.streamAudioEchoCancellation ?? API_DEFAULTS.streamAudioEchoCancellation) })}
      />

      <SliderField
        label={t('audio.bitrate')}
        display={`${s()?.streaming?.streamAudioBitrateKbps ?? API_DEFAULTS.streamAudioBitrateKbps} kbps`}
        min={API_DEFAULTS.audioBitrateMinKbps}
        max={API_DEFAULTS.audioBitrateMaxKbps}
        step={16}
        disabled={!webStreamingEnabled() || readOnly()}
        value={s()?.streaming?.streamAudioBitrateKbps ?? API_DEFAULTS.streamAudioBitrateKbps}
        onChange={(v) => saveDebounced({ streamAudioBitrateKbps: v })}
      />

      <FieldRow label={t('audio.channels')}>
        <Select
          value={`${s()?.streaming?.streamAudioChannels ?? API_DEFAULTS.streamAudioChannels}`}
          options={[
            { value: '1', label: t('audio.mono') },
            { value: '2', label: t('audio.stereo') },
          ]}
          disabled={!webStreamingEnabled() || readOnly()}
          onChange={(v) => save({ streamAudioChannels: parseInt(v) })}
          ariaLabel={t('audio.channels')}
        />
      </FieldRow>

      <ToggleField
        label={t('audio.includeRecordings')}
        checked={s()?.streaming?.recordingAudioEnabled ?? API_DEFAULTS.recordingAudioEnabled}
        disabled={readOnly()}
        onChange={() => {
          const newVal = !(s()?.streaming?.recordingAudioEnabled ?? API_DEFAULTS.recordingAudioEnabled)
          save({ recordingAudioEnabled: newVal })
          setRecordingAudio(newVal)
        }}
      />

      <ToggleField
        label={t('webstream.encryptCaptures')}
        hint={t('webstream.encryptCapturesDesc')}
        checked={mediaEncryption.checked()}
        disabled={readOnly()}
        onChange={mediaEncryption.onToggle}
      />
    </Panel>
  )
}

export function HttpsCard(props: StreamingCardProps) {
  const { readOnly, httpsToggle } = useToggles(props)

  return (
    <Panel title={t('https.title')} badge={<Led tone={httpsToggle.checked() ? 'ok' : 'off'} />}>
      <SettingItem id="streaming.httpsEnabled">
        <ToggleField
          label={t('https.serve')}
          hint={t('https.desc')}
          checked={httpsToggle.checked()}
          disabled={readOnly()}
          onChange={httpsToggle.onToggle}
        />
      </SettingItem>
    </Panel>
  )
}

export function RtspCard(props: StreamingCardProps) {
  const { s, readOnly, save, saveDebounced, rtspTls, rtspSubStream, rtspBasicAuthCompat } = useToggles(props)
  const rtspVideoCodec = () => s()?.streaming?.rtspVideoCodec ?? API_DEFAULTS.rtspVideoCodec
  // The port is typed, not just dragged: a slider cannot express 8554
  // precisely on a phone. The draft keeps an in-progress edit alive while
  // each keystroke is out of range (typing "85" on the way to "8554").
  const [portDraft, setPortDraft] = createSignal<string | null>(null)
  const commitPort = () => {
    if (readOnly()) return
    const raw = portDraft()
    if (raw === null) return
    const value = Number.parseInt(raw, 10)
    if (Number.isFinite(value) && value >= API_DEFAULTS.rtspPortMin && value <= API_DEFAULTS.rtspPortMax) {
      save({ rtspPort: value })
    }
    setPortDraft(null)
  }

  return (
    <Panel title={t('rtsp.title')} badge={<Led tone={s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled ? 'ok' : 'off'} />}>
      <SettingItem id="streaming.rtspEnabled">
        <ToggleField
          label={t('rtsp.enable')}
          checked={s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled}
          disabled={readOnly()}
          onChange={() => save({ rtspEnabled: !(s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled) })}
        />
      </SettingItem>

      <Show when={s()?.streaming?.rtspEnabled}>
        {/* TLS is the RTSP listener's own setting (issue #18): toggling the
            web HTTPS card no longer flips the RTSP scheme. A change restarts
            a live RTSP output — the same restart-time property as the port. */}
        <ToggleField
          label={t('rtsp.tls')}
          hint={t('rtsp.tlsDesc')}
          checked={rtspTls.checked()}
          disabled={readOnly()}
          onChange={rtspTls.onToggle}
        />

        <ToggleField
          label={t('rtsp.subStream')}
          hint={t('rtsp.subStreamDesc')}
          checked={rtspSubStream.checked()}
          disabled={readOnly()}
          onChange={rtspSubStream.onToggle}
        />

        <ToggleField
          label={t('rtsp.basicAuthCompat')}
          hint={t('rtsp.basicAuthCompatDesc')}
          checked={rtspBasicAuthCompat.checked()}
          disabled={readOnly()}
          onChange={rtspBasicAuthCompat.onToggle}
        />

        <FieldRow label={t('rtsp.port')}>
          <TextInput
            class="nx-input-num"
            type="number"
            small
            mono
            min={API_DEFAULTS.rtspPortMin}
            max={API_DEFAULTS.rtspPortMax}
            step={1}
            value={portDraft() ?? String(s()?.streaming?.rtspPort ?? API_DEFAULTS.rtspPort)}
            disabled={readOnly()}
            onInput={(raw) => {
              if (readOnly()) return
              setPortDraft(raw)
              const value = Number.parseInt(raw, 10)
              if (Number.isFinite(value) && value >= API_DEFAULTS.rtspPortMin && value <= API_DEFAULTS.rtspPortMax) {
                saveDebounced({ rtspPort: value })
              }
            }}
            onBlur={commitPort}
            ariaLabel={t('rtsp.port')}
          />
        </FieldRow>

        <FieldRow label={t('rtsp.inputFormat')}>
          <Select
            value={s()?.streaming?.rtspInputFormat ?? API_DEFAULTS.rtspInputFormat}
            options={[
              { value: 'AUTO', label: t('common.auto') },
              { value: 'NV21', label: 'NV21' },
              { value: 'NV12', label: 'NV12' },
              { value: 'I420', label: 'I420' },
            ]}
            disabled={readOnly()}
            onChange={(v) => save({ rtspInputFormat: v as RtspInputFormat })}
            ariaLabel={t('rtsp.inputFormat')}
          />
        </FieldRow>

        <FieldRow label={t('rtsp.codec')}>
          <Select
            value={rtspVideoCodec()}
            options={[
              { value: 'h264', label: t('rtsp.codecH264') },
              { value: 'h265', label: 'H.265' },
            ]}
            disabled={readOnly()}
            onChange={(v) => save({ rtspVideoCodec: v as RtspVideoCodec })}
            ariaLabel={t('rtsp.codec')}
          />
        </FieldRow>
      </Show>
    </Panel>
  )
}

export function KeepAwakeCard(props: StreamingCardProps) {
  const { readOnly, keepAwake } = useToggles(props)

  return (
    <Panel title={t('keepawake.title')} badge={<Led tone={keepAwake.checked() ? 'ok' : 'off'} />}>
      <SettingItem id="streaming.keepAwake">
        <ToggleField
          label={t('keepawake.enable')}
          hint={t('keepawake.desc')}
          checked={keepAwake.checked()}
          disabled={readOnly()}
          onChange={keepAwake.onToggle}
        />
      </SettingItem>
    </Panel>
  )
}

import { Show } from 'solid-js'
import type { AllSettings, RtspInputFormat, RtspResolution, RtspVideoCodec } from '../../../../types'
import { API_DEFAULTS } from '../../../../api/defaults'
import { t } from '../../../../lib/i18n'
import { streamToggle } from '../../../../lib/streamToggle'
import { Panel, ToggleField, SliderField, FieldRow, Select, Led } from '../../ui'

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
  const s = () => props.settings()
  const streaming = () => s()?.streaming
  const save = (patch: Partial<AllSettings['streaming']>) => props.updateStreamingAndSave(patch)
  return {
    s,
    adaptiveBitrate: streamToggle(streaming, 'adaptiveBitrateEnabled', save),
    adaptiveEncoded: streamToggle(streaming, 'adaptiveEncodedBitrateEnabled', save),
    mdns: streamToggle(streaming, 'mdnsEnabled', save),
    onvif: streamToggle(streaming, 'onvifEnabled', save),
    mediaEncryption: streamToggle(streaming, 'mediaEncryptionEnabled', save),
    httpsToggle: streamToggle(streaming, 'httpsEnabled', save),
    rtspSubStream: streamToggle(streaming, 'rtspSubStreamEnabled', save),
    keepAwake: streamToggle(streaming, 'keepAwakeEnabled', save),
  }
}

export function WebStreamCard(props: StreamingCardProps) {
  const { s, adaptiveBitrate, adaptiveEncoded, mdns, onvif } = useToggles(props)
  const webStreamingEnabled = () => s()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled

  return (
    <Panel title={t('webstream.title')} badge={<Led tone={webStreamingEnabled() ? 'ok' : 'off'} />}>
      <ToggleField
        label={t('webstream.enable')}
        hint={t('webstream.independent')}
        checked={webStreamingEnabled()}
        onChange={() => props.updateStreamingAndSave({ webStreamingEnabled: !webStreamingEnabled() })}
      />

      <SliderField
        label={t('webstream.jpegQuality')}
        display={`${s()?.streaming?.jpegQuality ?? API_DEFAULTS.jpegQuality}%`}
        min={API_DEFAULTS.jpegQualityMin}
        max={API_DEFAULTS.jpegQualityMax}
        step={5}
        disabled={!webStreamingEnabled()}
        value={s()?.streaming?.jpegQuality ?? API_DEFAULTS.jpegQuality}
        onChange={(v) => props.updateStreamingDebounced({ jpegQuality: v })}
      />

      <ToggleField
        label={t('webstream.adaptive')}
        hint={t('webstream.adaptiveDesc')}
        checked={adaptiveBitrate.checked()}
        onChange={adaptiveBitrate.onToggle}
      />
      <ToggleField
        label={t('webstream.adaptiveEncoded')}
        hint={t('webstream.adaptiveEncodedDesc')}
        checked={adaptiveEncoded.checked()}
        onChange={adaptiveEncoded.onToggle}
      />

      <ToggleField
        label={t('webstream.showPreview')}
        checked={s()?.streaming?.showPreview ?? API_DEFAULTS.showPreview}
        disabled={!webStreamingEnabled()}
        onChange={() => props.updateStreamingAndSave({ showPreview: !(s()?.streaming?.showPreview ?? API_DEFAULTS.showPreview) })}
      />

      <ToggleField
        label={t('webstream.mdns')}
        hint={t('webstream.mdnsDesc')}
        checked={mdns.checked()}
        onChange={mdns.onToggle}
      />

      <ToggleField
        label={t('webstream.onvif')}
        hint={t('webstream.onvifDesc')}
        checked={onvif.checked()}
        onChange={onvif.onToggle}
      />
    </Panel>
  )
}

export function AudioCard(props: StreamingCardProps) {
  const { s, mediaEncryption } = useToggles(props)
  const webStreamingEnabled = () => s()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled

  return (
    <Panel title={t('audio.title')}>
      <ToggleField
        label={t('audio.includeLive')}
        checked={s()?.streaming?.streamAudioEnabled ?? API_DEFAULTS.streamAudioEnabled}
        disabled={!webStreamingEnabled()}
        onChange={() => props.updateStreamingAndSave({ streamAudioEnabled: !(s()?.streaming?.streamAudioEnabled ?? API_DEFAULTS.streamAudioEnabled) })}
      />

      <ToggleField
        label={t('audio.echo')}
        checked={s()?.streaming?.streamAudioEchoCancellation ?? API_DEFAULTS.streamAudioEchoCancellation}
        disabled={!webStreamingEnabled()}
        onChange={() => props.updateStreamingAndSave({ streamAudioEchoCancellation: !(s()?.streaming?.streamAudioEchoCancellation ?? API_DEFAULTS.streamAudioEchoCancellation) })}
      />

      <SliderField
        label={t('audio.bitrate')}
        display={`${s()?.streaming?.streamAudioBitrateKbps ?? API_DEFAULTS.streamAudioBitrateKbps} kbps`}
        min={API_DEFAULTS.audioBitrateMinKbps}
        max={API_DEFAULTS.audioBitrateMaxKbps}
        step={16}
        disabled={!webStreamingEnabled()}
        value={s()?.streaming?.streamAudioBitrateKbps ?? API_DEFAULTS.streamAudioBitrateKbps}
        onChange={(v) => props.updateStreamingDebounced({ streamAudioBitrateKbps: v })}
      />

      <FieldRow label={t('audio.channels')}>
        <Select
          value={`${s()?.streaming?.streamAudioChannels ?? API_DEFAULTS.streamAudioChannels}`}
          options={[
            { value: '1', label: t('audio.mono') },
            { value: '2', label: t('audio.stereo') },
          ]}
          disabled={!webStreamingEnabled()}
          onChange={(v) => props.updateStreamingAndSave({ streamAudioChannels: parseInt(v) })}
          ariaLabel={t('audio.channels')}
        />
      </FieldRow>

      <ToggleField
        label={t('audio.includeRecordings')}
        checked={s()?.streaming?.recordingAudioEnabled ?? API_DEFAULTS.recordingAudioEnabled}
        onChange={() => {
          const newVal = !(s()?.streaming?.recordingAudioEnabled ?? API_DEFAULTS.recordingAudioEnabled)
          props.updateStreamingAndSave({ recordingAudioEnabled: newVal })
          props.setRecordingConfigAudio(newVal)
        }}
      />

      <ToggleField
        label={t('webstream.encryptCaptures')}
        hint={t('webstream.encryptCapturesDesc')}
        checked={mediaEncryption.checked()}
        onChange={mediaEncryption.onToggle}
      />
    </Panel>
  )
}

export function HttpsCard(props: StreamingCardProps) {
  const { httpsToggle } = useToggles(props)

  return (
    <Panel title={t('https.title')} badge={<Led tone={httpsToggle.checked() ? 'ok' : 'off'} />}>
      <ToggleField
        label={t('https.serve')}
        hint={t('https.desc')}
        checked={httpsToggle.checked()}
        onChange={httpsToggle.onToggle}
      />
    </Panel>
  )
}

export function RtspCard(props: StreamingCardProps) {
  const { s, rtspSubStream } = useToggles(props)
  const rtspVideoCodec = () => s()?.streaming?.rtspVideoCodec ?? API_DEFAULTS.rtspVideoCodec

  return (
    <Panel title={t('rtsp.title')} badge={<Led tone={s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled ? 'ok' : 'off'} />}>
      <ToggleField
        label={t('rtsp.enable')}
        checked={s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled}
        onChange={() => props.updateStreamingAndSave({ rtspEnabled: !(s()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled) })}
      />

      <Show when={s()?.streaming?.rtspEnabled}>
        <ToggleField
          label={t('rtsp.subStream')}
          hint={t('rtsp.subStreamDesc')}
          checked={rtspSubStream.checked()}
          onChange={rtspSubStream.onToggle}
        />

        <SliderField
          label={t('rtsp.port')}
          display={`${s()?.streaming?.rtspPort ?? API_DEFAULTS.rtspPort}`}
          min={API_DEFAULTS.rtspPortMin}
          max={API_DEFAULTS.rtspPortMax}
          step={1}
          value={s()?.streaming?.rtspPort ?? API_DEFAULTS.rtspPort}
          onChange={(v) => props.updateStreamingDebounced({ rtspPort: v })}
        />

        <FieldRow label={t('rtsp.inputFormat')}>
          <Select
            value={s()?.streaming?.rtspInputFormat ?? API_DEFAULTS.rtspInputFormat}
            options={[
              { value: 'AUTO', label: t('common.auto') },
              { value: 'NV21', label: 'NV21' },
              { value: 'NV12', label: 'NV12' },
              { value: 'I420', label: 'I420' },
            ]}
            onChange={(v) => props.updateStreamingAndSave({ rtspInputFormat: v as RtspInputFormat })}
            ariaLabel={t('rtsp.inputFormat')}
          />
        </FieldRow>

        {/* Resolution applies with an RTSP restart — the running pipeline
            keeps streaming at the previous size until it is restarted. */}
        <FieldRow label={t('common.resolution')} hint={t('rtsp.restartHint')}>
          <Select
            value={s()?.streaming?.rtspResolution ?? API_DEFAULTS.rtspResolution}
            options={[
              { value: '480p', label: '480p (4:3)' },
              { value: '720p', label: '720p' },
              { value: '720p43', label: '720p (4:3)' },
              { value: '1080p', label: '1080p' },
              { value: '1080p43', label: '1080p (4:3)' },
            ]}
            onChange={(v) => props.updateStreamingAndSave({ rtspResolution: v as RtspResolution })}
            ariaLabel={t('common.resolution')}
          />
        </FieldRow>

        <FieldRow label={t('rtsp.codec')}>
          <Select
            value={rtspVideoCodec()}
            options={[
              { value: 'h264', label: t('rtsp.codecH264') },
              { value: 'h265', label: 'H.265' },
            ]}
            onChange={(v) => props.updateStreamingAndSave({ rtspVideoCodec: v as RtspVideoCodec })}
            ariaLabel={t('rtsp.codec')}
          />
        </FieldRow>
      </Show>
    </Panel>
  )
}

export function KeepAwakeCard(props: StreamingCardProps) {
  const { keepAwake } = useToggles(props)

  return (
    <Panel title={t('keepawake.title')} badge={<Led tone={keepAwake.checked() ? 'ok' : 'off'} />}>
      <ToggleField
        label={t('keepawake.enable')}
        hint={t('keepawake.desc')}
        checked={keepAwake.checked()}
        onChange={keepAwake.onToggle}
      />
    </Panel>
  )
}

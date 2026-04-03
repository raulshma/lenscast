import SettingsCard from './SettingsCard'
import type { AllSettings } from '../types'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  setRecordingConfigAudio: (v: boolean) => void
}

export default function StreamingCard(props: Props) {
  const s = () => props.settings()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      }
      title="Streaming"
    >
      {/* JPEG Quality */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">JPEG Quality</span>
          <span class="field-value">{s()?.streaming?.jpegQuality ?? 80}%</span>
        </div>
        <input
          id="jpeg-quality-slider"
          type="range"
          class="custom-range"
          min={10}
          max={100}
          step={5}
          value={s()?.streaming?.jpegQuality ?? 80}
          onInput={(e) => {
            const v = parseInt(e.currentTarget.value)
            props.updateStreamingDebounced({ jpegQuality: v })
          }}
        />
      </div>

      {/* Show Preview */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Show Preview on Device</span>
          <label class="toggle-switch" for="show-preview-toggle">
            <input
              id="show-preview-toggle"
              type="checkbox"
              checked={s()?.streaming?.showPreview ?? true}
              onChange={() => props.updateStreamingAndSave({ showPreview: !(s()?.streaming?.showPreview ?? true) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* Include Audio */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Live Audio</span>
          <label class="toggle-switch" for="stream-audio-toggle">
            <input
              id="stream-audio-toggle"
              type="checkbox"
              checked={s()?.streaming?.streamAudioEnabled ?? true}
              onChange={() => props.updateStreamingAndSave({ streamAudioEnabled: !(s()?.streaming?.streamAudioEnabled ?? true) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* Audio Bitrate */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Audio Bitrate</span>
          <span class="field-value">{s()?.streaming?.streamAudioBitrateKbps ?? 128} kbps</span>
        </div>
        <input
          id="audio-bitrate-slider"
          type="range"
          class="custom-range"
          min={32}
          max={320}
          step={16}
          value={s()?.streaming?.streamAudioBitrateKbps ?? 128}
          onInput={(e) => {
            const v = parseInt(e.currentTarget.value)
            props.updateStreamingDebounced({ streamAudioBitrateKbps: v })
          }}
        />
      </div>

      {/* Channels */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Audio Channels</span>
        </div>
        <select
          id="audio-channels-select"
          class="field-select field-select-full"
          value={`${s()?.streaming?.streamAudioChannels ?? 1}`}
          onChange={(e) => {
            const v = parseInt(e.currentTarget.value)
            props.updateStreamingAndSave({ streamAudioChannels: v })
          }}
        >
          <option value="1">Mono</option>
          <option value="2">Stereo</option>
        </select>
      </div>

      {/* Echo Cancellation */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Echo Cancellation</span>
          <label class="toggle-switch" for="echo-cancel-toggle">
            <input
              id="echo-cancel-toggle"
              type="checkbox"
              checked={s()?.streaming?.streamAudioEchoCancellation ?? true}
              onChange={() => props.updateStreamingAndSave({ streamAudioEchoCancellation: !(s()?.streaming?.streamAudioEchoCancellation ?? true) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* Recording Audio */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Default Recording Audio</span>
          <label class="toggle-switch" for="rec-audio-toggle">
            <input
              id="rec-audio-toggle"
              type="checkbox"
              checked={s()?.streaming?.recordingAudioEnabled ?? true}
              onChange={() => {
                const newVal = !(s()?.streaming?.recordingAudioEnabled ?? true)
                props.updateStreamingAndSave({ recordingAudioEnabled: newVal })
                props.setRecordingConfigAudio(newVal)
              }}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* RTSP Stream */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">RTSP Stream</span>
          <label class="toggle-switch" for="rtsp-toggle">
            <input
              id="rtsp-toggle"
              type="checkbox"
              checked={s()?.streaming?.rtspEnabled ?? false}
              onChange={() => props.updateStreamingAndSave({ rtspEnabled: !(s()?.streaming?.rtspEnabled ?? false) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      <Show when={s()?.streaming?.rtspEnabled}>
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">RTSP Port</span>
            <span class="field-value">{s()?.streaming?.rtspPort ?? 8554}</span>
          </div>
          <input
            id="rtsp-port-slider"
            type="range"
            class="custom-range"
            min={1024}
            max={65535}
            step={1}
            value={s()?.streaming?.rtspPort ?? 8554}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingDebounced({ rtspPort: v })
            }}
          />
        </div>
      </Show>
    </SettingsCard>
  )
}

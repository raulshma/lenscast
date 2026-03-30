import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { RecordingConfig, RecordingQuality } from '../types'
import { RECORDING_QUALITY_LABELS } from '../types'

interface Props {
  recordingConfig: () => RecordingConfig
  setRecordingConfig: (v: RecordingConfig) => void
  isRecording: () => boolean
  recordingTimer: { formatElapsed: () => string }
  handleStartRecording: () => void
  handleStopRecording: () => void
}

export default function RecordingCard(props: Props) {
  const cfg = () => props.recordingConfig()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10" />
          <circle cx="12" cy="12" r="4" fill="currentColor" opacity="0.3" />
        </svg>
      }
      title="Recording"
    >
      <Show when={props.isRecording()}>
        <div class="status-banner status-banner-danger">
          <span class="recording-dot" />
          Recording — {props.recordingTimer.formatElapsed()}
        </div>
      </Show>

      {/* Include Audio */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Include Audio</span>
          <label class="toggle-switch" for="recording-audio-toggle">
            <input
              id="recording-audio-toggle"
              type="checkbox"
              checked={cfg().includeAudio}
              onChange={() => props.setRecordingConfig({ ...cfg(), includeAudio: !cfg().includeAudio })}
              disabled={props.isRecording()}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* Quality */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Quality</span>
        </div>
        <select
          id="recording-quality-select"
          class="field-select field-select-full"
          value={cfg().quality}
          onChange={(e) => props.setRecordingConfig({ ...cfg(), quality: e.currentTarget.value as RecordingQuality })}
          disabled={props.isRecording()}
        >
          {Object.entries(RECORDING_QUALITY_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>

      {/* Duration */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Duration</span>
          <span class="field-value">
            {cfg().durationSeconds === 0 ? '∞' : `${cfg().durationSeconds}s`}
          </span>
        </div>
        <input
          id="recording-duration-slider"
          type="range"
          class="custom-range"
          min={0}
          max={3600}
          value={cfg().durationSeconds}
          onInput={(e) => props.setRecordingConfig({ ...cfg(), durationSeconds: parseInt(e.currentTarget.value) })}
          disabled={props.isRecording()}
        />
      </div>

      {/* Repeat Interval */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Repeat Interval</span>
          <span class="field-value">
            {cfg().repeatIntervalSeconds === 0 ? 'None' : `${cfg().repeatIntervalSeconds}s`}
          </span>
        </div>
        <input
          id="recording-repeat-slider"
          type="range"
          class="custom-range"
          min={0}
          max={3600}
          value={cfg().repeatIntervalSeconds}
          onInput={(e) => props.setRecordingConfig({ ...cfg(), repeatIntervalSeconds: parseInt(e.currentTarget.value) })}
          disabled={props.isRecording()}
        />
      </div>

      {/* Start/Stop */}
      <div class="card-action">
        {props.isRecording() ? (
          <button id="stop-recording-btn" class="card-btn card-btn-danger" onClick={props.handleStopRecording}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
            Stop Recording
          </button>
        ) : (
          <button id="start-recording-btn" class="card-btn card-btn-primary" onClick={props.handleStartRecording}>
            <span class="rec-dot-icon" />
            Start Recording
          </button>
        )}
      </div>
    </SettingsCard>
  )
}

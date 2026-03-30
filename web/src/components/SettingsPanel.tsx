import { Show } from 'solid-js'
import type { AllSettings, CameraSettings, IntervalCaptureConfig, RecordingConfig } from '../types'
import LensSelector from './LensSelector'
import ExposureCard from './ExposureCard'
import FocusCard from './FocusCard'
import WhiteBalanceCard from './WhiteBalanceCard'
import ZoomFrameCard from './ZoomFrameCard'
import EffectsCard from './EffectsCard'
import StreamingCard from './StreamingCard'
import IntervalCaptureCard from './IntervalCaptureCard'
import RecordingCard from './RecordingCard'
import type { LensInfo } from '../types'

interface Props {
  settings: () => AllSettings | null
  lenses: () => LensInfo[]
  error: () => string
  updateCamera: (patch: Partial<CameraSettings>) => void
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  handleSelectLens: (index: number) => void
  handleResetDefaults: () => void
  // Interval
  intervalConfig: () => IntervalCaptureConfig
  setIntervalConfig: (v: IntervalCaptureConfig) => void
  intervalRunning: () => boolean
  intervalCompleted: () => number
  handleStartIntervalCapture: () => void
  handleStopIntervalCapture: () => void
  // Recording
  recordingConfig: () => RecordingConfig
  setRecordingConfig: (v: RecordingConfig) => void
  isRecording: () => boolean
  isScheduled: () => boolean
  scheduledStartTimeMs: () => number | null
  recordingTimer: { formatElapsed: () => string }
  handleStartRecording: () => void
  handleStopRecording: () => void
}

export default function SettingsPanel(props: Props) {
  return (
    <section class="settings-panel" id="settings-panel">
      {/* Error */}
      <Show when={props.error()}>
        <div class="error-banner">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <span>{props.error()}</span>
        </div>
      </Show>

      {/* Lens Selector */}
      <Show when={props.lenses().length > 0}>
        <LensSelector lenses={props.lenses} handleSelectLens={props.handleSelectLens} />
      </Show>

      {/* Settings Cards */}
      <ExposureCard settings={props.settings} updateCamera={props.updateCamera} />
      <FocusCard settings={props.settings} updateCamera={props.updateCamera} />
      <WhiteBalanceCard settings={props.settings} updateCamera={props.updateCamera} />
      <ZoomFrameCard settings={props.settings} updateCamera={props.updateCamera} />
      <EffectsCard settings={props.settings} updateCamera={props.updateCamera} />
      <StreamingCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        setRecordingConfigAudio={(v) => props.setRecordingConfig({ ...props.recordingConfig(), includeAudio: v })}
      />
      <IntervalCaptureCard
        intervalConfig={props.intervalConfig}
        setIntervalConfig={props.setIntervalConfig}
        intervalRunning={props.intervalRunning}
        intervalCompleted={props.intervalCompleted}
        handleStartIntervalCapture={props.handleStartIntervalCapture}
        handleStopIntervalCapture={props.handleStopIntervalCapture}
      />
      <RecordingCard
        recordingConfig={props.recordingConfig}
        setRecordingConfig={props.setRecordingConfig}
        isRecording={props.isRecording}
        isScheduled={props.isScheduled}
        scheduledStartTimeMs={props.scheduledStartTimeMs}
        recordingTimer={props.recordingTimer}
        handleStartRecording={props.handleStartRecording}
        handleStopRecording={props.handleStopRecording}
      />

      {/* Reset */}
      <div class="settings-footer">
        <button id="reset-defaults-btn" class="card-btn card-btn-ghost" onClick={props.handleResetDefaults}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M1 4v6h6" />
            <path d="M3.51 15a9 9 0 102.13-9.36L1 10" />
          </svg>
          Reset to Defaults
        </button>
      </div>
    </section>
  )
}

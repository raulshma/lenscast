import { Show } from 'solid-js'
import type { AllSettings, CameraSettings, IntervalCaptureConfig, RecordingConfig, NightVisionMode } from '../types'
import LensSelector from './LensSelector'
import ExposureCard from './ExposureCard'
import FocusCard from './FocusCard'
import WhiteBalanceCard from './WhiteBalanceCard'
import ZoomFrameCard from './ZoomFrameCard'
import EffectsCard from './EffectsCard'
import { NightVisionCard } from './NightVisionCard'
import IntervalCaptureCard from './IntervalCaptureCard'
import RecordingCard from './RecordingCard'
import OverlayCard from './OverlayCard'
import PrivacyMaskingCard from './PrivacyMaskingCard'
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
  // Navigation
  activeTab: () => 'camera' | 'app'
  setActiveTab: (v: 'camera' | 'app') => void
}

export default function SettingsPanel(props: Props) {
  return (
    <section class="settings-panel" id="settings-panel">
      {/* Tab Navigation */}
      <div class="settings-tabs">
        <button
          class="settings-tab-btn"
          classList={{ 'settings-tab-btn-active': props.activeTab() === 'camera' }}
          onClick={() => props.setActiveTab('camera')}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style={{ width: '16px', height: '16px' }}>
            <path d="M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z" />
            <circle cx="12" cy="13" r="4" />
          </svg>
          Camera
        </button>
        <button
          class="settings-tab-btn"
          classList={{ 'settings-tab-btn-active': props.activeTab() === 'app' }}
          onClick={() => props.setActiveTab('app')}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style={{ width: '16px', height: '16px' }}>
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
          </svg>
          App
        </button>
      </div>

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

      {/* Camera Tab Content */}
      <Show when={props.activeTab() === 'camera'}>
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
        <NightVisionCard
          value={props.settings()?.camera?.nightVisionMode ?? 'OFF'}
          onChange={(mode: NightVisionMode) => props.updateCamera({ nightVisionMode: mode })}
        />
        <Show when={props.settings()?.streaming}>
          <OverlayCard
            streaming={() => props.settings()!.streaming}
            onUpdate={props.updateStreamingDebounced}
          />
          <PrivacyMaskingCard
            streaming={() => props.settings()!.streaming}
            onUpdate={props.updateStreamingDebounced}
          />
        </Show>
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
      </Show>

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

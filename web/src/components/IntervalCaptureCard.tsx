import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { IntervalCaptureConfig, CaptureMode, FlashMode } from '../types'
import { CAPTURE_MODE_LABELS, FLASH_MODE_LABELS } from '../types'

interface Props {
  intervalConfig: () => IntervalCaptureConfig
  setIntervalConfig: (v: IntervalCaptureConfig) => void
  intervalRunning: () => boolean
  intervalCompleted: () => number
  handleStartIntervalCapture: () => void
  handleStopIntervalCapture: () => void
}

export default function IntervalCaptureCard(props: Props) {
  const cfg = () => props.intervalConfig()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
      }
      title="Interval Capture"
    >
      <Show when={props.intervalRunning()}>
        <div class="status-banner status-banner-info">
          <span class="status-banner-dot" />
          Running — {props.intervalCompleted()} captures completed
        </div>
      </Show>

      {/* Interval */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Interval</span>
          <span class="field-value">{cfg().intervalSeconds}s</span>
        </div>
        <input
          id="interval-seconds-slider"
          type="range"
          class="custom-range"
          min={1}
          max={3600}
          value={cfg().intervalSeconds}
          onInput={(e) => props.setIntervalConfig({ ...cfg(), intervalSeconds: parseInt(e.currentTarget.value) })}
          disabled={props.intervalRunning()}
        />
      </div>

      {/* Total Captures */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Total Captures</span>
          <span class="field-value">{cfg().totalCaptures}</span>
        </div>
        <input
          id="total-captures-slider"
          type="range"
          class="custom-range"
          min={1}
          max={1000}
          value={cfg().totalCaptures}
          onInput={(e) => props.setIntervalConfig({ ...cfg(), totalCaptures: parseInt(e.currentTarget.value) })}
          disabled={props.intervalRunning()}
        />
      </div>

      {/* Image Quality */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Image Quality</span>
          <span class="field-value">{cfg().imageQuality}%</span>
        </div>
        <input
          id="interval-quality-slider"
          type="range"
          class="custom-range"
          min={10}
          max={100}
          value={cfg().imageQuality}
          onInput={(e) => props.setIntervalConfig({ ...cfg(), imageQuality: parseInt(e.currentTarget.value) })}
          disabled={props.intervalRunning()}
        />
      </div>

      {/* Capture Mode */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Capture Mode</span>
        </div>
        <select
          id="interval-capture-mode"
          class="field-select field-select-full"
          value={cfg().captureMode}
          onChange={(e) => props.setIntervalConfig({ ...cfg(), captureMode: e.currentTarget.value as CaptureMode })}
          disabled={props.intervalRunning()}
        >
          {Object.entries(CAPTURE_MODE_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>

      {/* Flash Mode */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Flash</span>
        </div>
        <select
          id="interval-flash-mode"
          class="field-select field-select-full"
          value={cfg().flashMode}
          onChange={(e) => props.setIntervalConfig({ ...cfg(), flashMode: e.currentTarget.value as FlashMode })}
          disabled={props.intervalRunning()}
        >
          {Object.entries(FLASH_MODE_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>

      {/* Start/Stop */}
      <div class="card-action">
        {props.intervalRunning() ? (
          <button id="stop-interval-btn" class="card-btn card-btn-danger" onClick={props.handleStopIntervalCapture}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
            Stop Interval Capture
          </button>
        ) : (
          <button id="start-interval-btn" class="card-btn card-btn-primary" onClick={props.handleStartIntervalCapture}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
            Start Interval Capture
          </button>
        )}
      </div>
    </SettingsCard>
  )
}

import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { IntervalCaptureConfig, FlashMode } from '../types'
import { FLASH_MODE_LABELS } from '../types'
import { t } from '../lib/i18n'
import type { FlashMode as FlashModeType } from '../types'

/** Wire value → i18n key for the flash options. */
const FLASH_KEYS: Record<FlashMode, string> = {
  OFF: 'common.off',
  ON: 'common.on',
  AUTO: 'common.auto',
}

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
      title={t('interval.title')}
    >
      <Show when={props.intervalRunning()}>
        <div class="status-banner status-banner-info">
          <span class="status-banner-dot" />
          {t('interval.running', { count: props.intervalCompleted() })}
        </div>
      </Show>

      {/* Interval */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('interval.interval')}</span>
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
          <span class="field-label">{t('interval.total')}</span>
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

      {/* Flash Mode */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('interval.flash')}</span>
        </div>
        <select
          id="interval-flash-mode"
          class="field-select field-select-full"
          value={cfg().flashMode}
          onChange={(e) => props.setIntervalConfig({ ...cfg(), flashMode: e.currentTarget.value as FlashMode })}
          disabled={props.intervalRunning()}
        >
          {Object.keys(FLASH_MODE_LABELS).map((k) => (
            <option value={k}>{t(FLASH_KEYS[k as FlashModeType])}</option>
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
            {t('interval.stop')}
          </button>
        ) : (
          <button id="start-interval-btn" class="card-btn card-btn-primary" onClick={props.handleStartIntervalCapture}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polygon points="5 3 19 12 5 21 5 3" />
            </svg>
            {t('interval.start')}
          </button>
        )}
      </div>
    </SettingsCard>
  )
}

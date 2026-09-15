import { Show, createSignal } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { RecordingConfig, RecordingQuality } from '../types'
import { RECORDING_QUALITY_LABELS } from '../types'
import { formatTime, t } from '../lib/i18n'

/** Wire value → i18n key for the recording-quality options. */
const QUALITY_KEYS: Record<RecordingQuality, string> = {
  HIGH: 'option.quality.high',
  MEDIUM: 'option.quality.medium',
  LOW: 'option.quality.low',
}

interface Props {
  recordingConfig: () => RecordingConfig
  setRecordingConfig: (v: RecordingConfig) => void
  isRecording: () => boolean
  isScheduled: () => boolean
  scheduledStartTimeMs: () => number | null
  recordingTimer: { formatElapsed: () => string }
  handleStartRecording: () => void
  handleStopRecording: () => void
}

export default function RecordingCard(props: Props) {
  const cfg = () => props.recordingConfig()
  const [scheduleTimeStr, setScheduleTimeStr] = createSignal('')

  const handleStartAction = () => {
    const timeStr = scheduleTimeStr()
    if (timeStr) {
      const now = new Date()
      const [hours, minutes] = timeStr.split(':').map(Number)
      const scheduledTime = new Date()
      scheduledTime.setHours(hours, minutes, 0, 0)
      if (scheduledTime.getTime() <= now.getTime()) {
        scheduledTime.setDate(scheduledTime.getDate() + 1)
      }
      props.setRecordingConfig({ ...cfg(), startTimeMs: scheduledTime.getTime() })
    } else {
      props.setRecordingConfig({ ...cfg(), startTimeMs: null })
    }
    props.handleStartRecording()
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10" />
          <circle cx="12" cy="12" r="4" fill="currentColor" opacity="0.3" />
        </svg>
      }
      title={t('recording.title')}
    >
      <Show when={props.isRecording()}>
        <div class="status-banner status-banner-danger">
          <span class="recording-dot" />
          {t('recording.active', { time: props.recordingTimer.formatElapsed() })}
        </div>
      </Show>

      <Show when={props.isScheduled()}>
        <div class="status-banner status-banner-warning">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style={{ "flex-shrink": 0, width: '16px', height: '16px' }}>
            <circle cx="12" cy="12" r="10" />
            <path d="M12 6v6l4 2" />
          </svg>
          {t('recording.scheduledFor', {
            time: props.scheduledStartTimeMs()
              ? formatTime(props.scheduledStartTimeMs()!, { hour: '2-digit', minute: '2-digit' })
              : '...',
          })}
        </div>
      </Show>

      {/* Include Audio */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('recording.audio')}</span>
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
          <span class="field-label">{t('recording.quality')}</span>
        </div>
        <select
          id="recording-quality-select"
          class="field-select field-select-full"
          value={cfg().quality}
          onChange={(e) => props.setRecordingConfig({ ...cfg(), quality: e.currentTarget.value as RecordingQuality })}
          disabled={props.isRecording()}
        >
          {Object.keys(RECORDING_QUALITY_LABELS).map((k) => (
            <option value={k}>{t(QUALITY_KEYS[k as RecordingQuality])}</option>
          ))}
        </select>
      </div>

      {/* Duration */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('recording.duration')}</span>
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
          <span class="field-label">{t('recording.repeat')}</span>
          <span class="field-value">
            {cfg().repeatIntervalSeconds === 0 ? t('recording.none') : `${cfg().repeatIntervalSeconds}s`}
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
          disabled={props.isRecording() || props.isScheduled()}
        />
      </div>

      {/* Schedule Time */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('recording.scheduleTime')}</span>
        </div>
        <input
          id="recording-schedule-time"
          type="time"
          class="field-input field-select-full"
          style={{ width: '100%' }}
          value={scheduleTimeStr()}
          onInput={(e) => setScheduleTimeStr(e.currentTarget.value)}
          disabled={props.isRecording() || props.isScheduled()}
        />
      </div>

      {/* Start/Stop */}
      <div class="card-action">
        {props.isRecording() || props.isScheduled() ? (
          <button id="stop-recording-btn" class="card-btn card-btn-danger" onClick={props.handleStopRecording}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
            {props.isRecording() ? t('recording.stop') : t('recording.stopScheduled')}
          </button>
        ) : (
          <button id="start-recording-btn" class="card-btn card-btn-primary" onClick={handleStartAction}>
            <span class="rec-dot-icon" />
            {scheduleTimeStr() ? t('recording.schedule') : t('recording.start')}
          </button>
        )}
      </div>
    </SettingsCard>
  )
}

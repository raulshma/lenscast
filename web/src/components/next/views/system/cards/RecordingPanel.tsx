import { Show, createSignal } from 'solid-js'
import type { RecordingConfig, RecordingQuality } from '../../../../../types'
import { RECORDING_QUALITY_LABELS } from '../../../../../types'
import { formatTime, t } from '../../../../../lib/i18n'
import { Panel, ToggleField, SliderField, FieldRow, Field, Select, TextInput, Button, Badge, Led, type SelectOption } from '../../../ui'

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

/**
 * Manual recording, ported from the classic RecordingCard onto the kit with
 * every binding unchanged: the include-audio toggle, quality select,
 * duration/repeat sliders (0 = ∞ / none), the schedule-time draft, and the
 * start/stop choreography that materializes the picked time (rolling to
 * tomorrow when it already passed today) into startTimeMs at start.
 */
export default function RecordingPanel(props: Props) {
  const cfg = () => props.recordingConfig()
  const [scheduleTimeStr, setScheduleTimeStr] = createSignal('')

  const qualityOptions = (): SelectOption[] =>
    Object.keys(RECORDING_QUALITY_LABELS).map((k) => ({ value: k, label: t(QUALITY_KEYS[k as RecordingQuality]) }))

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
    <Panel
      title={t('recording.title')}
      badge={
        <Show
          when={props.isRecording()}
          fallback={
            <Show when={props.isScheduled()}>
              <Badge tone="warning" mono><Led tone="warn" />{t('recording.badge.scheduled')}</Badge>
            </Show>
          }
        >
          <Badge tone="danger" mono><Led tone="rec" />{t('recording.badge.rec')}</Badge>
        </Show>
      }
    >
      <Show when={props.isRecording()}>
        <div class="nx-banner nx-banner-danger" role="status" aria-live="polite">
          <Led tone="rec" />
          {t('recording.active', { time: props.recordingTimer.formatElapsed() })}
        </div>
      </Show>

      <Show when={props.isScheduled()}>
        <div class="nx-banner nx-banner-warning" role="status" aria-live="polite">
          {t('recording.scheduledFor', {
            time: props.scheduledStartTimeMs()
              ? formatTime(props.scheduledStartTimeMs()!, { hour: '2-digit', minute: '2-digit' })
              : '...',
          })}
        </div>
      </Show>

      {/* Include Audio */}
      <ToggleField
        label={t('recording.audio')}
        checked={cfg().includeAudio}
        disabled={props.isRecording()}
        onChange={() => props.setRecordingConfig({ ...cfg(), includeAudio: !cfg().includeAudio })}
      />

      {/* Quality */}
      <FieldRow label={t('recording.quality')}>
        <Select
          value={cfg().quality}
          options={qualityOptions()}
          disabled={props.isRecording()}
          onChange={(v) => props.setRecordingConfig({ ...cfg(), quality: v as RecordingQuality })}
          ariaLabel={t('recording.quality')}
        />
      </FieldRow>

      {/* Duration */}
      <SliderField
        label={t('recording.duration')}
        display={cfg().durationSeconds === 0 ? '∞' : `${cfg().durationSeconds}s`}
        min={0}
        max={3600}
        value={cfg().durationSeconds}
        disabled={props.isRecording()}
        onChange={(v) => props.setRecordingConfig({ ...cfg(), durationSeconds: v })}
      />

      {/* Repeat Interval */}
      <SliderField
        label={t('recording.repeat')}
        display={cfg().repeatIntervalSeconds === 0 ? t('recording.none') : `${cfg().repeatIntervalSeconds}s`}
        min={0}
        max={3600}
        value={cfg().repeatIntervalSeconds}
        disabled={props.isRecording() || props.isScheduled()}
        onChange={(v) => props.setRecordingConfig({ ...cfg(), repeatIntervalSeconds: v })}
      />

      {/* Schedule Time */}
      <Field label={t('recording.scheduleTime')}>
        <TextInput
          type="time"
          value={scheduleTimeStr()}
          disabled={props.isRecording() || props.isScheduled()}
          onInput={setScheduleTimeStr}
          ariaLabel={t('recording.scheduleTime')}
        />
      </Field>

      {/* Start/Stop */}
      {props.isRecording() || props.isScheduled() ? (
        <Button
          variant="danger"
          size="sm"
          onClick={props.handleStopRecording}
          ariaLabel={props.isRecording() ? t('recording.stop') : t('recording.stopScheduled')}
        >
          {props.isRecording() ? t('recording.stop') : t('recording.stopScheduled')}
        </Button>
      ) : (
        <Button
          variant="primary"
          size="sm"
          onClick={handleStartAction}
          ariaLabel={scheduleTimeStr() ? t('recording.schedule') : t('recording.start')}
        >
          {scheduleTimeStr() ? t('recording.schedule') : t('recording.start')}
        </Button>
      )}
    </Panel>
  )
}

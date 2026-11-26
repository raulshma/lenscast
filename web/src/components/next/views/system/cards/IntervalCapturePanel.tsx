import { Show } from 'solid-js'
import type { FlashMode, IntervalCaptureConfig } from '../../../../../types'
import { FLASH_MODE_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { Panel, SliderField, FieldRow, Select, Button, Led, type SelectOption } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

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

/**
 * Timelapse capture, ported from the classic IntervalCaptureCard onto the
 * kit with every binding unchanged: interval and total-captures sliders,
 * the flash-mode select, and the start/stop action — all frozen while the
 * run is live, with a rec LED on the header while it is.
 */
export default function IntervalCapturePanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const setConfig = (value: IntervalCaptureConfig) => {
    if (!readOnly()) props.setIntervalConfig(value)
  }
  const cfg = () => props.intervalConfig()

  const flashOptions = (): SelectOption[] =>
    Object.keys(FLASH_MODE_LABELS).map((k) => ({ value: k, label: t(FLASH_KEYS[k as FlashMode]) }))

  return (
    <Panel
      title={t('interval.title')}
      badge={<Show when={props.intervalRunning()}><Led tone="rec" /></Show>}
    >
      <SettingItem id="interval.status">
      <Show when={props.intervalRunning()}>
        {/* The run is the operator's on-air process: the amber presence,
            same voice as the recording panel's REC banner. */}
        <div class="nx-banner nx-banner-rec" role="status" aria-live="polite">
          {t('interval.running', { count: props.intervalCompleted() })}
        </div>
      </Show>
      </SettingItem>

      <SettingItem id="interval.config">
      <>
      {/* Interval */}
      <SliderField
        label={t('interval.interval')}
        display={`${cfg().intervalSeconds}s`}
        min={1}
        max={3600}
        value={cfg().intervalSeconds}
        disabled={props.intervalRunning() || readOnly()}
        onChange={(v) => setConfig({ ...cfg(), intervalSeconds: v })}
      />

      {/* Total Captures */}
      <SliderField
        label={t('interval.total')}
        display={`${cfg().totalCaptures}`}
        min={1}
        max={1000}
        value={cfg().totalCaptures}
        disabled={props.intervalRunning() || readOnly()}
        onChange={(v) => setConfig({ ...cfg(), totalCaptures: v })}
      />

      {/* Flash Mode */}
      <FieldRow label={t('interval.flash')}>
        <Select
          value={cfg().flashMode}
          options={flashOptions()}
          disabled={props.intervalRunning() || readOnly()}
          onChange={(v) => setConfig({ ...cfg(), flashMode: v as FlashMode })}
          ariaLabel={t('interval.flash')}
        />
      </FieldRow>
      </>
      </SettingItem>

      <SettingItem id="interval.start-stop">
      {/* Start/Stop — a quiet outline start: this rack pairs the verb with
          the recording panel's, and two amber primaries would shout. */}
      {props.intervalRunning() ? (
        <Button
          variant="danger"
          size="sm"
          disabled={readOnly()}
          onClick={() => { if (!readOnly()) props.handleStopIntervalCapture() }}
          ariaLabel={t('interval.stop')}
        >
          {t('interval.stop')}
        </Button>
      ) : (
        <Button
          variant="secondary"
          size="sm"
          disabled={readOnly()}
          onClick={() => { if (!readOnly()) props.handleStartIntervalCapture() }}
          ariaLabel={t('interval.start')}
        >
          {t('interval.start')}
        </Button>
      )}
      </SettingItem>
    </Panel>
  )
}

import { Show } from 'solid-js'
import type { FlashMode, IntervalCaptureConfig } from '../../../../../types'
import { FLASH_MODE_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { Panel, SliderField, FieldRow, Select, Button, Led, type SelectOption } from '../../../ui'

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
  const cfg = () => props.intervalConfig()

  const flashOptions = (): SelectOption[] =>
    Object.keys(FLASH_MODE_LABELS).map((k) => ({ value: k, label: t(FLASH_KEYS[k as FlashMode]) }))

  return (
    <Panel
      title={t('interval.title')}
      badge={<Show when={props.intervalRunning()}><Led tone="rec" /></Show>}
    >
      <Show when={props.intervalRunning()}>
        <div class="nx-banner nx-banner-info" role="status" aria-live="polite">
          {t('interval.running', { count: props.intervalCompleted() })}
        </div>
      </Show>

      {/* Interval */}
      <SliderField
        label={t('interval.interval')}
        display={`${cfg().intervalSeconds}s`}
        min={1}
        max={3600}
        value={cfg().intervalSeconds}
        disabled={props.intervalRunning()}
        onChange={(v) => props.setIntervalConfig({ ...cfg(), intervalSeconds: v })}
      />

      {/* Total Captures */}
      <SliderField
        label={t('interval.total')}
        display={`${cfg().totalCaptures}`}
        min={1}
        max={1000}
        value={cfg().totalCaptures}
        disabled={props.intervalRunning()}
        onChange={(v) => props.setIntervalConfig({ ...cfg(), totalCaptures: v })}
      />

      {/* Flash Mode */}
      <FieldRow label={t('interval.flash')}>
        <Select
          value={cfg().flashMode}
          options={flashOptions()}
          disabled={props.intervalRunning()}
          onChange={(v) => props.setIntervalConfig({ ...cfg(), flashMode: v as FlashMode })}
          ariaLabel={t('interval.flash')}
        />
      </FieldRow>

      {/* Start/Stop */}
      {props.intervalRunning() ? (
        <Button variant="danger" size="sm" onClick={props.handleStopIntervalCapture} ariaLabel={t('interval.stop')}>
          {t('interval.stop')}
        </Button>
      ) : (
        <Button variant="primary" size="sm" onClick={props.handleStartIntervalCapture} ariaLabel={t('interval.start')}>
          {t('interval.start')}
        </Button>
      )}
    </Panel>
  )
}

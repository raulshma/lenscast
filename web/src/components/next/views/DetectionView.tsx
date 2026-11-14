import { createSignal, onCleanup, Show } from 'solid-js'
import type { NextState } from '../NextState'
import { isArmed } from '../../../armDays'
import { t } from '../../../lib/i18n'
import StatsPanel from './detection/StatsPanel'
import EventLog from './detection/EventLog'
import SecurityPanel from './detection/SecurityPanel'
import AutomationPanel from './detection/AutomationPanel'

/** How often the armed strip re-checks the clock (the window boundary). */
const ARM_TICK_MS = 30_000

/** Minute of day 0..1439 from the wall clock. */
function minuteOfDay(now: Date): number {
  return now.getHours() * 60 + now.getMinutes()
}

/**
 * Detection view — the surface leads with state. A signal-magenta tick
 * strip runs across the stats head while armed motion detection is
 * inside its live window (schedule-aware: motion on, and when the arm
 * schedule gates it, today armed between the hours); the surface is
 * quiet when idle. Below, the stats header spans the full width and
 * the arming panel takes the main column with the event log and
 * automation rules packed in a side column ≥1100px so toggle rows
 * don't stretch the full width.
 */
export default function DetectionView(props: { state: NextState }) {
  const state = props.state

  // The armed verdict rides the settings poll plus a 30s clock ticker, so
  // the strip opens and closes with the clock, not just the poll. The
  // ticker holds one Date sample: weekday and minute must agree — two
  // samples can straddle midnight and arm Sunday with Saturday's minute.
  const [clock, setClock] = createSignal(new Date())
  const armTick = setInterval(() => setClock(new Date()), ARM_TICK_MS)
  onCleanup(() => clearInterval(armTick))

  const armedNow = () => {
    const stream = state.settings()?.streaming
    // The clock signal is read unconditionally, before any verdict: an
    // excluded day must still track the ticker so the strip wakes the
    // moment an included day (or window boundary) begins.
    const now = clock()
    if (!stream) return false
    // The server's own verdict, mirrored (MotionArmingPolicy.isArmed) so the
    // strip reads armed exactly when detection actually runs — inclusive
    // window bounds, ISO day order (bit 0 = Monday … bit 6 = Sunday).
    return isArmed(
      stream.motionDetectionEnabled,
      stream.motionArmScheduleEnabled,
      stream.motionArmStartMinute,
      stream.motionArmEndMinute,
      minuteOfDay(now),
      stream.motionArmDaysMask,
      (now.getDay() + 6) % 7,
    )
  }

  return (
    <div class="nx-view">
      <div class="nx-view-inner">
        <div class="nx-detect-head" classList={{ 'nx-detect-armed': armedNow() }}>
          {/* The strip is pure decoration; the spoken state rides the status
              line below it so assistive tech reads what the strip shows. */}
          <div class="nx-arm-strip" aria-hidden="true" />
          <span class="nx-detect-status" role="status">
            {armedNow() ? t('security.armedNow') : t('security.armedIdle')}
          </span>
          <StatsPanel />
        </div>
        <div class="nx-detection-grid">
          <Show when={state.settings()?.streaming}>
            <SecurityPanel
              settings={state.settings}
              status={state.status}
              updateStreamingAndSave={state.updateStreamingAndSave}
              updateStreamingDebounced={state.updateStreamingDebounced}
            />
          </Show>
          <div class="nx-detection-side">
            <EventLog readOnly={state.isViewer} />
            <AutomationPanel readOnly={state.isViewer} />
          </div>
        </div>
      </div>
    </div>
  )
}

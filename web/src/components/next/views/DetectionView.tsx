import { createSignal, onCleanup, Show } from 'solid-js'
import type { NextState } from '../NextState'
import { isArmed } from '../../../armDays'
import { t } from '../../../lib/i18n'
import { Led, Skeleton } from '../ui'
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
 * Detection view — information ordered by value to the operator. A quiet
 * armed-state line opens the surface (the amber tick strip + spoken verdict
 * while armed motion detection is inside its live window; schedule-aware:
 * motion on, and when the arm schedule gates it, today armed between the
 * hours). The news leads: the event journal with its aggregate stats beside
 * it ≥1100px, one cut surface. Configuration follows: the security form,
 * then automation as the deepest layer in the side column ≥1100px.
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
        {/* The one-glance verdict: the strip is pure decoration; the status
            line beside it speaks for assistive tech and sighted operators
            alike. Amber only while armed — red stays for faults. */}
        <div class="nx-detect-head" classList={{ 'nx-detect-armed': armedNow() }}>
          <div class="nx-arm-strip" aria-hidden="true" />
          <span class="nx-detect-status" role="status">
            <Led tone={armedNow() ? 'accent' : 'off'} />
            {armedNow() ? t('security.armedNow') : t('security.armedIdle')}
          </span>
        </div>

        {/* The news: the journal leads, its aggregate reads beside it. One
            cut surface — two panels joined by the 1px seam. */}
        <div class="nx-cut nx-detect-news">
          <EventLog readOnly={state.isViewer} />
          <StatsPanel />
        </div>

        {/* The config: the security form in the main column, automation —
            the deepest layer — in the side column ≥1100px. */}
        <div class="nx-detection-grid">
          <Show
            when={state.settings()?.streaming}
            fallback={
              <div class="nx-detect-loading" aria-hidden="true">
                <Skeleton h="40px" />
                <Skeleton h="52px" />
                <Skeleton h="52px" />
                <Skeleton h="52px" />
              </div>
            }
          >
            <SecurityPanel
              settings={state.settings}
              status={state.status}
              updateStreamingAndSave={state.updateStreamingAndSave}
              updateStreamingDebounced={state.updateStreamingDebounced}
            />
          </Show>
          <div class="nx-detection-side">
            <AutomationPanel readOnly={state.isViewer} />
          </div>
        </div>
      </div>
    </div>
  )
}
